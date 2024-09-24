package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.TraceId;
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
 * MatsEagerCache via {@link #scheduleBroadcastFullUpdate()} or
 * {@link #broadcastPartialUpdate(CacheSourceDataCallback)}.
 * <p>
 * The source data is accessed by the cache server via the {@link CacheSourceDataCallback} supplier provided in the
 * constructor. It is important that the source data can be read in a consistent manner, so some kind of synchronization
 * or locking of the source data should be employed while the cache server reads it (mainly relevant if the source data
 * is held in memory).
 * 
 * @author Endre Stølsvik 2024-09-03 19:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsEagerCacheServer {
    private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheServer.class);

    static final String MATS_EAGER_CACHE_PAYLOAD = "MatsEagerCache.payload";

    private final MatsFactory _matsFactory;
    private final String _dataName;
    private final Supplier<CacheSourceDataCallback<?>> _dataSupplier;
    private final Function<?, ?> _dataTypeMapper;
    private final int _forcedUpdateIntervalMinutes;

    private final String _privateNodename;

    private final ObjectWriter _sentDataTypeWriter;

    private final CopyOnWriteArrayList<SiblingCommandEventListener> _siblingCommandEventListeners = new CopyOnWriteArrayList<>();

    static String getCacheRequestQueue(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
    }

    static String getBroadcastTopic(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".BroadcastUpdate";
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <SRC, SENT> MatsEagerCacheServer(MatsFactory matsFactory, String dataName, Class<SENT> sentDataType,
            int forcedUpdateIntervalMinutes, Supplier<CacheSourceDataCallback<SRC>> dataSupplier,
            Function<SRC, SENT> dataTypeMapper) {

        _matsFactory = matsFactory;
        _dataName = dataName;
        _dataSupplier = (Supplier<CacheSourceDataCallback<?>>) (Supplier) dataSupplier;
        _dataTypeMapper = dataTypeMapper;
        _forcedUpdateIntervalMinutes = forcedUpdateIntervalMinutes;

        // Generate private nodename, which is used to identify the instance amongst the cache servers, e.g. with
        // Sibling Commands. Doing this since this Cache API does not expose the nodename, only whether it is "us" or
        // not. This makes testing a tad simpler, than using the default nodename, which is 'hostname'.
        _privateNodename = matsFactory.getFactoryConfig().getNodename() + "."
                + Long.toString(ThreadLocalRandom.current().nextLong(), 36);

        // :: Jackson JSON ObjectMapper
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Make specific Writer for the "sentDataType" - this is what we will need to serialize to send to clients.
        // Configure as NDJSON (Newline Delimited JSON), which is a good format for streaming.
        _sentDataTypeWriter = mapper.writerFor(sentDataType).withRootValueSeparator("\n");
    }

    private volatile boolean _running;
    private volatile MatsEndpoint<Void, Void> _broadcastTerminator;
    private volatile MatsEndpoint<Void, Void> _requestTerminator;

    /**
     * The service must provide an implementation of this interface to the cache-server, via a {@link Supplier}, so that
     * the cache-server may request the information when it needs it. The cache server may ask for the source data at
     * any time, and the supplier must provide a consistent snapshot of the source data. The only required method to
     * implement, {@link #provideSourceData(Consumer)}, is invoked by the cache server to retrieve the source data. The
     * service must invoke the provided DTO-{@link Consumer} repeatedly until the entire dataset (for full updates), or
     * the partial dataset (for partial updates), has been provided, and then close any resources (e.g. SQL Connection),
     * and return - thus marking the end of data, which will be sent to the cache clients.
     *
     * @param <SRC>
     *            the source type of the data.
     */
    @FunctionalInterface
    public interface CacheSourceDataCallback<SRC> {
        /**
         * Get the count of the data (i.e. how many entities), if known beforehand. The default implementation returns
         * {@code -1}, which means that the count is not known. If the size is known, it may be used to optimize the
         * cache server's handling of message byte arrays when getting data, e.g. not resize to 2x if there is only one
         * of 1000s entities left. Note: Do <b>not</b> make any effort to get this value if not immediately available,
         * e.g. by issuing a SELECT COUNT(1), as the memory savings will not be worth the performance penalty.
         * <p>
         * Note: The corresponding method on the client side is always correct, as we then have read the source data and
         * know the count!
         *
         * @return the size of the data, or {@code -1} if not known.
         */
        default int provideDataCount() {
            return -1;
        }

        /**
         * Get the metadata. This is an optional method that can be used to provide some metadata about the data, which
         * the client can use to log and display to the user (i.e. developers/operators) in the cache GUI. The default
         * implementation returns {@code null}.
         *
         * @return the metadata, or {@code null} if not provided.
         */
        default String provideMetadata() {
            return null;
        }

        /**
         * The cache server will invoke this method to retrieve the source data to send to cache clients. You invoke the
         * supplied consumer repeatedly until you've provided the entire dataset (for full updates), or the partial
         * dataset (for partial updates), and then close any resources (e.g. SQL Connection), and return - thus marking
         * the end of data, which will be sent to the cache clients
         * <p>
         * Care must be taken to ensure that the stream represent a consistent snapshot of the data, and that the data
         * is not modified while the stream is being read, so some kind of synchronization or locking of the source data
         * should probably be employed (mainly relevant if the source data is held in memory).
         */
        void provideSourceData(Consumer<SRC> consumer);
    }

    /**
     * Add a listener for sibling commands. {@link SiblingCommand Sibling commands} are messages sent from one sibling
     * to all the other siblings, including the one that originated the command. This can be useful to propagate updates
     * to the source data to all the siblings, to ensure that the source data is consistent between the siblings.
     *
     * @param siblingCommandEventListener
     *            the listener to add.
     */
    public void addSiblingCommandListener(SiblingCommandEventListener siblingCommandEventListener) {
        _siblingCommandEventListeners.add(siblingCommandEventListener);
    }

    private static final String SIDELOAD_KEY_SIBLING_COMMAND_BYTES = "scb";

    /**
     * Sends a {@link SiblingCommand sibling command}. This is a message sent from one sibling to all the other
     * siblings, including the one that originated the command. This can be useful to propagate updates to the source
     * data to all the siblings, to ensure that the source data is consistent between the siblings.
     * <p>
     * Remember that in a boot or redeploy situation, your service instances will typically be started in a staggered
     * fashion - and at any time, a new sibling might be started, or an existing stopped. Thus, you must not expect a
     * stable cluster where a stable set of siblings are always present.
     *
     * @param command
     *            the command name. This is a string that the siblings can use to determine what to do. It has no
     *            meaning to the Cache Server or the Cache Clients.
     * @param stringData
     *            the string data to send with the command. This is a string that the siblings can use to determine what
     *            to do. It has no meaning to the Cache Server or the Cache Clients. This can be {@code null} if no
     *            string data is to be sent.
     * @param binaryData
     *            the binary data to send with the command. This is a byte array that the siblings can use to determine
     *            what to do. It has no meaning to the Cache Server or the Cache Clients. This can be {@code null} if no
     *            binary data is to be sent.
     */
    public void sendSiblingCommand(String command, String stringData, byte[] binaryData) {
        if (!_running) {
            throw new IllegalStateException("The MatsEagerCacheServer has not been started yet.");
        }

        BroadcastDto broadcast = new BroadcastDto();
        broadcast.command = BroadcastDto.COMMAND_SIBLING_COMMAND;
        broadcast.siblingCommand = command;
        broadcast.siblingStringData = stringData;
        broadcast.sentTimestamp = System.currentTimeMillis();
        broadcast.sentNanoTime = System.nanoTime();

        broadcast.sentPrivateNodename = _privateNodename;

        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(TraceId.create("EagerCache." + _dataName, "SiblingCommand").add("cmd", command))
                    .addBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES, binaryData)
                    .from("EagerCache." + _dataName)
                    .to(getBroadcastTopic(_dataName))
                    .publish(broadcast);
        });
    }

    /**
     * The sibling command event listener. This is invoked when a sibling command is received.
     */
    @FunctionalInterface
    public interface SiblingCommandEventListener {
        void onSiblingCommand(SiblingCommand command);
    }

    /**
     * A sibling command. This is a message sent from one sibling to all the other siblings, including the one that
     * originated the command.
     */
    public interface SiblingCommand {
        /**
         * Since all nodes will receive the command, including the one that originated it, this method tells whether the
         * command originated on this instance. This is useful to know whether to propagate the command to the
         * MatsEagerCache via {@link MatsEagerCacheServer#scheduleBroadcastFullUpdate()} or
         * {@link MatsEagerCacheServer#broadcastPartialUpdate(CacheSourceDataCallback)}. Also, the
         * {@link #getSentNanoTime()} will only be meaningful on the instance that originated the command.
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

    /**
     * Schedules a full update of the cache. It is scheduled to run after a little while, the delay being a function of
     * how soon since the last time this was invoked. If it is a long time since last invocation, it will be scheduled
     * to run soon (~ within 1 second), while if the previous time was a short time ago, it will be scheduled to run a
     * bit later (~ within 7 seconds). The reason for this logic is to avoid "thundering herd" problems, where all
     * clients request a full update at the same time (which otherwise would result in a lot of full updates being sent
     * in parallel), which may happen if all clients are booted at the same time.
     * <p>
     * The data to send is retrieved by the cache server using the {@link CacheSourceDataCallback} supplier provided in
     * the constructor.
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
     * frequently updated in a way that makes the use of partial updates actually make a performance dent.
     * <p>
     * The method is synchronous, and returns when the data has been consumed and the partial update has been sent out
     * to clients.
     * <p>
     * If the cache server is currently in the process of sending a full or partial update, this method will be held
     * until current update is finished - there is an exclusive lock around sending updates. It is also important to let
     * the stream of partial data returned from {@link CacheSourceDataCallback#provideSourceData(Consumer)} read
     * directly from the source data structures, and not from some temporary not-applied representation, as otherwise
     * the partial update might send out data that is older than what is currently present and which might have already
     * been sent via a concurrent update (think about races here). Thus, always first apply the update to the source
     * data in some atomic fashion, and then retrieve the partial update from the source data, also in an atomic fashion
     * (e.g. use synchronization or locking).
     * <p>
     * It is advisable to not send a lot of partial updates in a short time span, as this will result in memory churn
     * and higher memory usage on the clients due to message reception and partial update merge. Rather coalesce the
     * partial updates into a single update, or use a waiting mechanism until the source data has stabilized before
     * sending out a partial update - or just send a full update.
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
    public <SRC> void broadcastPartialUpdate(CacheSourceDataCallback<SRC> data) {
    }

    public void close() {
        // ?: Are we started? Note: we accept multiple close() invocations, as the close-part is harder to lifecycle
        // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
        if (_running) {
            // -> Yes, we are started, so close down.
            _broadcastTerminator.stop(30_000);
            _requestTerminator.stop(30_000);
            _running = false;
        }
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
        // ?: Have we already started?
        if (_running) {
            // -> Yes, we have already started - so you evidently have no control over the lifecycle of this object!
            throw new IllegalStateException("The MatsEagerCacheServer has already been started.");
        }
        // ::: Create the Mats endpoints
        // :: The endpoint that the clients will send requests to
        _requestTerminator = _matsFactory.terminator(getCacheRequestQueue(_dataName), void.class,
                CacheRequestDto.class, (ctx, state, msg) -> {
                    ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();

                    int[] dataCount = new int[1];
                    CacheSourceDataCallback<?> sourceProvider = _dataSupplier.get();
                    // We checked these at construction time. We'll just have to live with the uncheckedness.
                    @SuppressWarnings("unchecked")
                    CacheSourceDataCallback<Object> uncheckedSourceProvider = (CacheSourceDataCallback<Object>) sourceProvider;
                    @SuppressWarnings({ "unchecked", "rawtypes" })
                    Function<Object, Object> uncheckedDataTypeMapper = o -> ((Function) _dataTypeMapper).apply(o);

                    try {
                        SequenceWriter jacksonSeq = _sentDataTypeWriter.writeValues(out);
                        Consumer<Object> consumer = o -> {
                            try {
                                jacksonSeq.write(uncheckedDataTypeMapper.apply(o));
                            }
                            catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            dataCount[0]++;
                        };
                        uncheckedSourceProvider.provideSourceData(consumer);
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
                    broadcast.dataCount = dataCount[0]; // Actual data count, not the one from the sourceProvider.
                    broadcast.uncompressedSize = out.getUncompressedBytesInput();
                    broadcast.compressedSize = out.getCompressedBytesOutput();
                    broadcast.metadata = sourceProvider.provideMetadata();

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
        // It is a pretty hard negative to receiving the actual updates on the servers, as this will mean that the
        // server will have to receive the update, even though it already has the data. However, we won't have to
        // deserialize the data, so the hit won't be that big. The obvious alternative is to have a separate topic for
        // the commands, but that would pollute the MQ Destination namespace with one extra topic per cache.
        _broadcastTerminator = _matsFactory.subscriptionTerminator(getBroadcastTopic(_dataName), void.class,
                BroadcastDto.class, (ctx, state, broadcastDto) -> {
                    log.info("Got a broadcast update: " + broadcastDto);
                    // ?: Is this a sibling command?
                    if (broadcastDto.command.equals(BroadcastDto.COMMAND_SIBLING_COMMAND)) {
                        // -> Yes, this is a sibling command.
                        byte[] bytes = ctx.getBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES);
                        SiblingCommand siblingCommand = new SiblingCommand() {
                            @Override
                            public boolean commandOriginatedOnThisInstance() {
                                return broadcastDto.sentPrivateNodename.equals(_privateNodename);
                            }

                            @Override
                            public long getSentTimestamp() {
                                return broadcastDto.sentTimestamp;
                            }

                            @Override
                            public long getSentNanoTime() {
                                return broadcastDto.sentNanoTime;
                            }

                            @Override
                            public String getCommand() {
                                return broadcastDto.siblingCommand;
                            }

                            @Override
                            public String getStringData() {
                                return broadcastDto.siblingStringData;
                            }

                            @Override
                            public byte[] getBinaryData() {
                                return bytes;
                            }
                        };
                        for (SiblingCommandEventListener listener : _siblingCommandEventListeners) {
                            try {
                                listener.onSiblingCommand(siblingCommand);
                            }
                            catch (Throwable t) {
                                log.error("Got exception from SiblingCommandEventListener [" + listener
                                        + "], ignoring.", t);
                            }
                        }
                    }
                });

        // We're now started.
        _running = true;

        // For chaining
        return this;
    }

    void _waitForReceiving() {
        _broadcastTerminator.waitForReceiving(30_000);
        _requestTerminator.waitForReceiving(30_000);
    }

    static final class CacheRequestDto {
        static final String COMMAND_REQUEST_INITIAL = "INITIAL";
        static final String COMMAND_REQUEST_MANUAL = "MANUAL";

        String command;
        String correlationId;

        String nodename;
        long sentTimestamp;
        long sentNanoTime;
    }

    static final class BroadcastDto {
        static final String COMMAND_UPDATE_FULL = "UPDATE_FULL";
        static final String COMMAND_UPDATE_PARTIAL = "UPDATE_PARTIAL";
        static final String COMMAND_SIBLING_COMMAND = "SIBLING_COMMAND";

        String command;
        String correlationId;

        String requestNodename;
        long requestSentTimestamp;
        long requestSentNanoTime;

        long sentTimestamp;
        long sentNanoTime;

        int dataCount;
        String metadata;
        long uncompressedSize;
        long compressedSize;

        // The actual data is added as a sideload.

        // For sibling commands
        String siblingCommand;
        String siblingStringData;
        // Bytes are sideloaded
        String sentPrivateNodename;
    }
}
