package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.mats3.MatsFactory;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.compression.ByteArrayDeflaterOutputStreamWithStats;

/**
 * The server side of the Mats Eager Cache system - sitting on the "data owner" source data side. This server will
 * listen for requests for cache updates, and send out a serialization of the source data to the clients, which will
 * deserialize it and make it available. The server will also schedule full updates to be sent out on a regular basis,
 * to ensure that the clients are kept up to date. There's optionally also a feature for sending partial updates, which
 * can be employed if the source data is frequently updated in a way that makes full updates too resource intensive.
 * <p>
 * It is expected that the source data is either held in memory (backed by a database), or read directly from a database
 * or other external source. If held in memory, the source data is also effectively a cache of the database, which leads
 * to a few interesting aspects: As always with systems employing Mats3, it is expected that the source service (the
 * data owner) have multiple instances running, to ensure high availability and scalability. However, events updating
 * the source data will often come in on a single instance (e.g. via a GUI, or via REST), and this instance will then
 * somehow need to propagate the update to the other instances. Typically, the update is first stored to the database by
 * the event receiver, whereupon it should tell all instances to update their view of the source data. The Mats Eager
 * Cache system have a feature for this, where you can send "command messages" to all the siblings via the
 * {@link #sendSiblingCommand(String, String, byte[]) sendSiblingCommand(..)} method and the corresponding
 * {@link #addSiblingCommandListener(SiblingCommandEventListener) addSiblingCommandListener(..)} method. All instances -
 * including the originator - will then receive the command, and can either refresh the source data from database, or
 * apply the update directly to the source data. It is beneficial if the originator also employs this event to update
 * its version of the source data, to ensure consistency between the nodes. A boolean will tell whether the command
 * originated on this instance - the one that originated (=<code>true</code>) will then propagate the update to the
 * MatsEagerCache via {@link #scheduleBroadcastFullUpdate()} or {@link #broadcastPartialUpdate(CacheSourceData)}.
 * <p>
 * The source data is accessed by the cache server via the {@link CacheSourceData} supplier provided in the constructor.
 * It is important that the source data can be read in a consistent manner, so some kind of synchronization or locking
 * of the source data should be employed while the cache server reads it (mainly relevant if the source data is held in
 * memory).
 * 
 * @author Endre Stølsvik 2024-09-03 19:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsEagerCacheServer {
    private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheServer.class);

    static final String MATS_EAGER_CACHE_PAYLOAD = "MatsEagerCache.payload";

    private final MatsFactory _matsFactory;
    private final String _dataName;
    private final Supplier<CacheSourceData<?>> _dataSupplier;
    private final Function<?, ?> _dataTypeMapper;
    private final int _forcedUpdateIntervalMinutes;

    private final ObjectWriter _sentDataTypeWriter;

    static String getCacheRequestTopic(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
    }

    static String getBroadcastTopic(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".BroadcastUpdate";
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <SRC, SENT> MatsEagerCacheServer(MatsFactory matsFactory, String dataName, Class<SENT> sentDataType,
            Supplier<CacheSourceData<SRC>> dataSupplier, Function<SRC, SENT> dataTypeMapper,
            int forcedUpdateIntervalMinutes) {

        _matsFactory = matsFactory;
        _dataName = dataName;
        _dataSupplier = (Supplier<CacheSourceData<?>>) (Supplier) dataSupplier;
        _dataTypeMapper = dataTypeMapper;
        _forcedUpdateIntervalMinutes = forcedUpdateIntervalMinutes;

        // :: Jackson JSON ObjectMapper
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Make specific Writer for the "sentDataType" - this is what we will need to serialize to send to clients.
        // Configure as NDJSON (Newline Delimited JSON), which is a good format for streaming.
        _sentDataTypeWriter = mapper.writerFor(sentDataType).withRootValueSeparator("\n");
    }

    public void addSiblingCommandListener(SiblingCommandEventListener siblingCommandEventListener) {
    }

    @FunctionalInterface
    public interface SiblingCommandEventListener {
        void onSiblingCommand(SiblingCommand command);
    }

    public interface SiblingCommand {
        /**
         * Since all nodes will receive the command, including the one that originated it, this method tells whether the
         * command originated on this instance. This is useful to know whether to propagate the command to the
         * MatsEagerCache via {@link MatsEagerCacheServer#scheduleBroadcastFullUpdate()} or
         * {@link MatsEagerCacheServer#broadcastPartialUpdate(CacheSourceData)}. Also, the {@link #getSentNanoTime()}
         * will only be meaningful on the instance that originated the command.
         * 
         * @return whether the command originated on this instance.
         */
        boolean commandOriginatedOnThisInstance();

        /**
         * This is the timestamp ({@link System#currentTimeMillis()}) when the command was sent.
         * 
         * @return the timestamp when the command was sent.
         */
        long getSentTimestamp();

        /**
         * This is the nanotime ({@link System#nanoTime()}) when the command was sent. This only makes sense on the
         * instance that {@link #commandOriginatedOnThisInstance() originated the command}, since nanotime is not
         * comparable between different JVM instances.
         * 
         * @return the nanotime when the command was sent.
         */
        long getSentNanoTime();

        String getCommand();

        String getStringData();

        byte[] getBinaryData();
    }

    public void sendSiblingCommand(String command, String stringData, byte[] binaryData) {
    }

    /**
     * Schedules a full update of the cache. It is scheduled to run after a little while, the delay being a function of
     * how soon since the last time this was invoked. If it is a long time since last invocation, it will be scheduled
     * to run soon (~ within 1 second), while if the previous time was a short time ago, it will be scheduled to run a
     * bit later (~ within 7 seconds). The reason for this logic is to avoid "thundering herd" problems, where all
     * clients request a full update at the same time (resulting in a lot of full updates being done in parallel), which
     * may happen if all clients are booted at the same time.
     * <p>
     * The data to send is retrieved by the cache server using the {@link CacheSourceData} supplier provided in the
     * constructor.
     * <p>
     * The method is asynchronous, and returns immediately.
     */
    public void scheduleBroadcastFullUpdate() {
    }

    /**
     * (Optional functionality) Immediately sends a partial update to all cache clients. This should be invoked if the
     * source data has been partially updated (e.g. an admin has made an update to a few of the cached entities using
     * some GUI), and we want to propagate this partial update to all cache clients, aiming to reduce the strain on the
     * messaging system and processing and memory churn on the clients.
     * <p>
     * This is an optional functionality to conserve resources for situations where somewhat frequent partial updates is
     * performed to the source data. The caching system will work just fine with only full updates. If the feature is
     * employed, the client side must also be coded up to handle partial updates, as otherwise the client will end up
     * with stale data.
     * <p>
     * Correctly applying a partial update on the client can be more complex than consuming a full update, as the client
     * must merge the partial update into the existing data structures, taking care to overwrite where appropriate, but
     * insert if the entity is new. This is why the feature is optional, and should only be used if the source data is
     * frequently updated in a way that makes the use of partial updates actually make a dent.
     * <p>
     * The method is synchronous, and returns when the data has been consumed and the partial update has been sent out
     * to clients.
     * <p>
     * If the cache server is currently in the process of sending a full or partial update, this method will be held
     * until current update is finished - there is an exclusive lock around sending updates. It is also important to let
     * the stream of partial data returned from {@link CacheSourceData#getSourceDataStream()} read directly from the
     * source data structures, and not from some temporary not-applied representation, as otherwise the partial update
     * might send out data that is older than what is currently present and which might have already been sent via a
     * concurrent update (think about races here). Thus, always first apply the update to the source data in some atomic
     * fashion, and then retrieve the partial update from the source data, also in an atomic fashion.
     * <p>
     * It is advisable to not send a lot of partial updates in a short time span, as this will result in memory churn on
     * the clients. Rather coalesce the partial updates into a single update, or wait until the source data has
     * stabilized before sending out a partial update.
     * <p>
     * Also, if the partial update is of a substantial part of the data, it is advisable to send a full update instead -
     * this can actually give lower peak memory load on the clients, as they can then just throw away the old data
     * before updating with the new data.
     * <p>
     * There is no solution for sending partial delete updates from the cache, so to remove an element from the cache, a
     * full update must be performed.
     *
     * @param data
     *            the data to send out as a partial update.
     */
    public <SRC> void broadcastPartialUpdate(CacheSourceData<SRC> data) {
    }

    public void close() {
    }

    public interface CacheSourceData<T> {
        /**
         * Get the size of the data, if known beforehand. The default implementation returns -1, which means that the
         * size is not known. If the size is known, it may be used to optimize the cache server's handling of the data
         * stream by allocating a buffer of a relevant size. (Note that the corresponding method on the client side is
         * always correct, as we then have read the data stream and know the size).
         *
         * @return the size of the data, or -1 if not known.
         */
        default int getDataCount() {
            return -1;
        }

        /**
         * Get the metadata. This is an optional method that can be used to provide some metadata about the data, which
         * the client can use to e.g. log or display to the user. The default implementation returns null.
         *
         * @return the metadata, or null if not provided.
         */
        default String getMetadata() {
            return null;
        }

        /**
         * Get the source data stream. This is the method that the cache server will invoke to retrieve the source data
         * to send out to the cache clients. Care must be taken to ensure that the stream represent a consistent
         * snapshot of the data, and that the data is not modified while the stream is being read, so some kind of
         * synchronization or locking of the source data should probably be employed (mainly relevant if the source data
         * is held in memory).
         */
        Stream<T> getSourceDataStream();
    }

    /**
     * Starts the cache server. This method is invoked to start the cache server, which will start to listen for
     * requests for cache updates. It will also schedule a full update of the cache to be done soon, sent out on the
     * broadcast topic, to ensure that we're able to do it: We won't report "ok" on the health check before we've
     * successfully performed a full update. This is to avoid a situation where we for some reason are unable to perform
     * a full update (e.g. can't load the source data from a database), we don't want to continue any rolling update of
     * the service instances under the assumption that the other instances might still have the data loaded and can thus
     * serve the data to cache clients.
     * 
     * @return this instance, for chaining.
     */
    public MatsEagerCacheServer start() {
        // ::: Create the Mats endpoints
        // :: The endpoint that the clients will send requests to
        _matsFactory.terminator("mats.MatsEagerCache." + _dataName + ".UpdateRequest", void.class,
                CacheRequestDto.class, (ctx, state, msg) -> {
                    ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();

                    int dataCount = 0;
                    CacheSourceData<?> source = _dataSupplier.get();

                    // We checked this at construction time. We'll just have to live with the uncheckedness.
                    Function<Object, Object> uncheckedApplier = new Function<>() {
                        @SuppressWarnings({ "unchecked", "rawtypes" })
                        @Override
                        public Object apply(Object o) {
                            return ((Function) _dataTypeMapper).apply(o);
                        }
                    };

                    try {
                        SequenceWriter jacksonSeq = _sentDataTypeWriter.writeValues(out);
                        Stream<?> sentData = source.getSourceDataStream().map(uncheckedApplier);
                        Iterator<?> iterator = sentData.iterator();
                        while (iterator.hasNext()) {
                            dataCount++;
                            jacksonSeq.write(iterator.next());
                        }
                        jacksonSeq.close();
                    }
                    catch (IOException e) {
                        // TODO: Log exception to monitor and HealthCheck.
                        throw new RuntimeException("Got interrupted while waiting for initial population to be done.",
                                e);
                    }

                    BroadcastDto broadcast = new BroadcastDto();
                    broadcast.command = BroadcastDto.COMMAND_UPDATE_FULL;
                    broadcast.correlationId = msg.correlationId;
                    broadcast.requestNodename = msg.nodename;
                    broadcast.requestSentTimestamp = msg.sentTimestamp;
                    broadcast.requestSentNanoTime = msg.sentNanoTime;
                    broadcast.sentTimestamp = System.currentTimeMillis();
                    broadcast.sentNanoTime = System.nanoTime();
                    broadcast.dataCount = dataCount; // Actual data count, not the one from the source.
                    broadcast.uncompressedSize = out.getUncompressedBytesInput();
                    broadcast.compressedSize = out.getCompressedBytesOutput();
                    broadcast.metadata = source.getMetadata();

                    ctx.initiate(init -> {
                        init.traceId("RequestReply")
                                .to(getBroadcastTopic(_dataName))
                                .addBytes(MATS_EAGER_CACHE_PAYLOAD, out.toByteArray())
                                .publish(broadcast);
                    });
                });

        // :: Listener to the update topic.
        // To be able to see that a sibling has sent an update, we need to listen to the broadcast topic for the
        // updates. This enables us to not send an update if a sibling has already sent one.
        // This is also the topic which will be used by the siblings to send commands to each other (which the clients
        // will ignore).
        // There is a pretty hard negative to receiving the actual updates on the servers, as this will mean that the
        // server will have to receive the update, even though it already has the data. However, we won't have to
        // deserialize the data, so the hit won't be that big. The obvious alternative is to have a separate topic for
        // the commands, but that would pollute the MQ Destination namespace with one extra topic per cache.
        _matsFactory.subscriptionTerminator("mats.MatsEagerCache." + _dataName + ".BroadcastUpdate", void.class,
                BroadcastDto.class, (ctx, state, msg) -> {
                    log.info("Got a broadcast update: " + msg);
                });

        // For chaining
        return this;
    }

    static final class CacheRequestDto {
        static final String COMMAND_REQUEST_AT_BOOT = "BOOT";
        static final String COMMAND_MANUAL_REQUEST = "MANUAL";

        String command;
        String correlationId;

        String nodename;
        long sentTimestamp;
        long sentNanoTime;
    }

    static final class BroadcastDto {
        static final String COMMAND_UPDATE_FULL = "UPDATE_FULL";
        static final String COMMAND_UPDATE_PARTIAL = "UPDATE_PARTIAL";

        String command;
        String correlationId;

        String requestNodename;
        long requestSentTimestamp;
        long requestSentNanoTime;

        long sentTimestamp;
        long sentNanoTime;

        int dataCount;
        long uncompressedSize;
        long compressedSize;
        String metadata;
    }

}
