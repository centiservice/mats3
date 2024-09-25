package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
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
 * {@link #addSiblingCommandListener(Consumer) addSiblingCommandListener(..)} method. All instances - including the
 * originator - will then receive the command, and can either refresh the source data from database, or apply the update
 * directly to the source data. It is beneficial if the originator also employs this event to update its version of the
 * source data, to ensure consistency between the nodes. A boolean will tell whether the command originated on this
 * instance - the one that originated (=<code>true</code>) will then propagate the update to the MatsEagerCache via
 * {@link #scheduleBroadcastFullUpdate()} or {@link #broadcastPartialUpdate(CacheSourceDataCallback)}.
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
    private final ThreadPoolExecutor _produceAndSendExecutor;

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

        // Create the single-threaded executor for producing and sending updates.
        _produceAndSendExecutor = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread t = new Thread(runnable, "MatsEagerCacheServer." + dataName + "-ProduceAndSendUpdate");
                    t.setDaemon(true);
                    return t;
                });
    }

    private volatile boolean _running;
    private volatile boolean _initialTestSourceDataProvided;
    private volatile MatsEndpoint<Void, Void> _broadcastTerminator;
    private volatile MatsEndpoint<Void, Void> _requestTerminator;

    private final CopyOnWriteArrayList<Consumer<SiblingCommand>> _siblingCommandEventListeners = new CopyOnWriteArrayList<>();

    private static final int DEFAULT_SHORT_DELAY = 1000;
    private static final int DEFAULT_LONG_DELAY = 7000;

    private int _shortDelay = DEFAULT_SHORT_DELAY;
    private int _longDelay = DEFAULT_LONG_DELAY;

    void _setDelays(int shortDelay, int longDelay) {
        _shortDelay = shortDelay;
        _longDelay = longDelay;
    }

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
         * Provide the count of the data (i.e. how many entities), if known beforehand. The default implementation
         * returns {@code -1}, which means that the count is not known. If the size is known, it may be used to optimize
         * the cache server's handling of message byte arrays when getting data, e.g. not resize to 2x if there is only
         * one of 1000s entities left. Note: Do <b>not</b> make any effort to get this value if not immediately
         * available, e.g. by issuing a SELECT COUNT(1), as any memory savings will not be worth the performance
         * penalty.
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
         * Provide the metadata. This is an optional method that can be used to provide some metadata about the data,
         * which the client can use to log and display to the user (i.e. developers/operators) in the cache GUI. The
         * default implementation returns {@code null}.
         *
         * @return the metadata, or {@code null} if not provided.
         */
        default String provideMetadata() {
            return null;
        }

        /**
         * Provide the actual data: The cache server will invoke this method to retrieve the source data to send to
         * cache clients. You invoke the supplied consumer repeatedly until you've provided the entire dataset (for full
         * updates), or the partial dataset (for partial updates), and then close any resources (e.g. SQL Connection),
         * and return - thus marking the end of data, which will be sent to the cache clients
         * <p>
         * Care must be taken to ensure that the stream represent a consistent snapshot of the data, and that the data
         * is not modified while the stream is being read, so some kind of synchronization or locking of the source data
         * should probably be employed (mainly relevant if the source data is held in memory).
         */
        void provideSourceData(Consumer<SRC> consumer);
    }

    /**
     * Add a listener for sibling commands. {@link SiblingCommand Sibling commands} are messages sent from one sibling
     * to all the other siblings, including the one that originated the command (you may ask
     * {@link SiblingCommand#commandOriginatedOnThisInstance() "whether it was you"}). This can be useful to propagate
     * updates to the source data to all the siblings, to ensure that the source data is consistent between the
     * siblings.
     *
     * @param siblingCommandEventListener
     *            the listener to add.
     */
    public void addSiblingCommandListener(Consumer<SiblingCommand> siblingCommandEventListener) {
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

        // Construct the broadcast DTO
        BroadcastDto broadcast = new BroadcastDto();
        broadcast.command = BroadcastDto.COMMAND_SIBLING_COMMAND;
        broadcast.siblingCommand = command;
        broadcast.siblingStringData = stringData;
        broadcast.sentTimestamp = System.currentTimeMillis();
        broadcast.sentNanoTime = System.nanoTime();
        broadcast.sentPrivateNodename = _privateNodename;

        // Ensure that we ourselves can get the ping.
        _broadcastTerminator.waitForReceiving(30_000);

        // Send the broadcast
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(TraceId.create("EagerCache." + _dataName, "SiblingCommand").add("cmd", command))
                    .addBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES, binaryData)
                    .from("MatsEagerCacheServer." + _dataName + ".SiblingCommand")
                    .to(getBroadcastTopic(_dataName))
                    .publish(broadcast);
        });
    }

    /**
     * A sibling command. This is a message sent from one sibling to all the other siblings, including the one that
     * originated the command. You may use the method {@link #commandOriginatedOnThisInstance()} to determine whether
     * this instance originated the command. This is relevant if you have the source data in memory: If for example a
     * user makes a change to the source data using the service's GUI, you must now apply it to yourself and all the
     * siblings. Instead of applying the change directly, you do it via a sibling command, which will then be sent to
     * all the siblings, including yourself - and apply the change in the sibling command listener. The data will thus
     * be consistent between all siblings. You then need to propagate the change to the cache clients - and it doesn't
     * make sense that all the siblings initiate this - so you can use the {@link #commandOriginatedOnThisInstance()} to
     * determine whether this instance should initiate the cache update.
     */
    public interface SiblingCommand {
        /**
         * Since all nodes will receive the command, including the one that originated it, this method tells whether the
         * command originated on this instance.
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
         * instance that {@link #commandOriginatedOnThisInstance() originated the command}, since nano time is not
         * comparable between different JVM instances.
         * 
         * @return the nano time when the command was sent.
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
     * The data to send is retrieved by the cache server using the {@link CacheSourceDataCallback} supplier provided
     * when constructing the cache server.
     * <p>
     * The update is asynchronous - this method returns immediately.
     */
    public void scheduleBroadcastFullUpdate() {
        _scheduleBroadcastFullUpdate(null);
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
        // TODO: Implement
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
        return _start();
    }

    public void close() {
        synchronized (this) {
            // ?: Are we running? Note: we accept multiple close() invocations, as the close-part is harder to lifecycle
            // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
            if (_running) {
                // -> Yes, we are started, so close down.
                _broadcastTerminator.stop(30_000);
                _requestTerminator.stop(30_000);
                _running = false;
            }
        }
    }

    // ======== Implementation / Internal methods ========

    static String getCacheRequestQueue(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
    }

    static String getBroadcastTopic(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".BroadcastUpdate";
    }

    private MatsEagerCacheServer _start() {
        // ?: Have we already started?
        if (_running) {
            // -> Yes, we have already started - so you evidently have no control over the lifecycle of this object!
            throw new IllegalStateException("The MatsEagerCacheServer has already been started.");
        }
        // ::: Create the Mats endpoints
        // :: The endpoint that the clients will send requests to
        // Note: We set concurrency to 1. We have this anti-thundering-herd mechanism whereby if many clients request
        // updates at the same time, we will only send one update satisfying them all. There is a mechanism to handle
        // this across multiple cache servers, whereby we broadcast "I'm going to do it". But having multiple stage
        // processors for the cache request terminator on each node makes no sense.
        _requestTerminator = _matsFactory.terminator(getCacheRequestQueue(_dataName), void.class,
                CacheRequestDto.class,
                endpointConfig -> endpointConfig.setConcurrency(1),
                MatsFactory.NO_CONFIG, (ctx, state, msg) -> {
                    _scheduleBroadcastFullUpdate(msg);
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
                    log.info("Got a broadcast: " + broadcastDto.command);
                    // ?: Is this a sibling command?
                    if (BroadcastDto.COMMAND_SIBLING_COMMAND.equals(broadcastDto.command)) {
                        // -> Yes, this is a sibling command.
                        _handleSiblingCommand(ctx, broadcastDto);
                    }
                    else if (BroadcastDto.COMMAND_SCHEDULE_REQUEST_RECEIVED.equals(broadcastDto.command)) {
                        _msg_scheduleRequestReceived(broadcastDto);
                    }
                    else if (BroadcastDto.COMMAND_SCHEDULE_SENDING_NOW.equals(broadcastDto.command)) {
                        _msg_schedule_SendNow(broadcastDto);
                    }
                });

        // We're now started.
        _running = true;

        // Create thread that checks if we actually can request the Source Data
        Thread checkThread = new Thread(() -> {
            // No use in doing this until the system has fully started up.
            // Proxy-checking this by the user having started the MatsFactory, thus that our endpoints are up.
            _waitForReceiving();

            // We'll keep trying until we succeed.
            long sleepTimeBetweenAttempts = 2000;
            while (_running) {
                // Try to get the data from the source provider.
                try {
                    log.info("Asserting that we can get Source Data.");
                    SourceDataResult result = _createSourceDataResult();
                    log.info("Success: We asserted that we can get Source Data! Data count:["
                            + result.dataCountFromSourceProvider + "]");
                    _initialTestSourceDataProvided = true;
                    // We're done - it is possible to get source data.
                    break;
                }
                catch (Throwable t) {
                    // TODO: Log exception to monitor and HealthCheck.
                    log.error("Got exception while trying to assert that we could call the source"
                            + " provider and get data.", t);
                }
                // Wait a bit before trying again.
                try {
                    Thread.sleep(sleepTimeBetweenAttempts);
                }
                catch (InterruptedException e) {
                    // TODO: Log exception to monitor and HealthCheck.
                    log.error("Got interrupted while waiting for initial population to be done.", e);
                    // The _running flag will be checked in the next iteration.
                }
                // Increase sleep time between attempts, but cap it at 30 seconds.
                sleepTimeBetweenAttempts = (long) Math.min(30_000, sleepTimeBetweenAttempts * 1.5);
            }
        }, "MatsEagerCacheServer." + _dataName + "-InitialPopulationCheck");
        checkThread.setDaemon(true);
        checkThread.start();

        // For chaining
        return this;
    }

    // These are synched on this.
    private int _updateRequest_OutstandingCount;
    private volatile long _lastFullUpdateStartedTimestamp;

    /**
     * We use the broadcast channel to sequence the incoming requests for updates. This should ensure that we get a
     * unified understanding of the order of incoming requests, and who should handle the update.
     */
    private void _scheduleBroadcastFullUpdate(CacheRequestDto incomingCacheRequest) {
        log.info("\n\n======== Scheduling full update of cache [" + _dataName + "].\n\n");
        // ?: Have we started?
        if (!_running) {
            // -> No, we have not started - so you evidently have no control over the lifecycle of this object!
            throw new IllegalStateException("The MatsEagerCacheServer has not been started yet.");
        }
        // Ensure that we ourselves can get the ping.
        _broadcastTerminator.waitForReceiving(30_000);

        // Send a message, that we ourselves will get.
        BroadcastDto broadcast = new BroadcastDto();
        broadcast.command = BroadcastDto.COMMAND_SCHEDULE_REQUEST_RECEIVED;
        broadcast.handlingNodename = _privateNodename;
        broadcast.sentTimestamp = System.currentTimeMillis();
        broadcast.sentNanoTime = System.nanoTime();
        if (incomingCacheRequest != null) {
            broadcast.correlationId = incomingCacheRequest.correlationId;
            broadcast.requestNodename = incomingCacheRequest.nodename;
            broadcast.requestSentTimestamp = incomingCacheRequest.sentTimestamp;
            broadcast.requestSentNanoTime = incomingCacheRequest.sentNanoTime;
        }
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(TraceId.create("EagerCache." + _dataName, "ScheduleRequest"))
                    .from("EagerCache." + _dataName)
                    .to(getBroadcastTopic(_dataName))
                    .publish(broadcast);
        });
    }

    private void _msg_scheduleRequestReceived(BroadcastDto broadcastDto) {
        log.info("\n\n======== Got a schedule request received: " + broadcastDto.handlingNodename
                + (_privateNodename.equals(broadcastDto.handlingNodename) ? " (THIS IS US!)" : "(Not us!)")
                + "\n\n");
        // Should we fire off the thread?
        boolean weAreInChargeOfHandlingUpdate = false;
        synchronized (this) {
            // Increase the count of outstanding requests.
            _updateRequest_OutstandingCount++;
            // ?: If this went from 0 to 1, the process is just started.
            if (_updateRequest_OutstandingCount == 1) {
                // -> Yes, it went from 0 to 1, so the process just got started!
                // The one that started it is the one that will handle it. Is it us?
                weAreInChargeOfHandlingUpdate = _privateNodename.equals(broadcastDto.handlingNodename);
            }
        }

        /*
         * Brute-force solution at ensuring that if the responsible node doesn't manage to do it, someone will. Make a
         * thread on ALL nodes that in some minutes will check _lastFullUpdateSentTimestamp, and if it is not higher
         * than when this thread started (which it should be if anyone performed any update), it will initiate a new
         * full update.
         */
        Thread ensurerThread = new Thread(() -> {
            long timestampWhenEnsurerStarted = System.currentTimeMillis();
            _takeNap(180_000);
            // ?: Have anyone already sent the full update?
            if (_lastFullUpdateStartedTimestamp < timestampWhenEnsurerStarted) {
                // -> No, we have not sent the full update yet, so we'll do it now.
                _scheduleBroadcastFullUpdate(null);
            }
        }, "MatsEagerCacheServer." + _dataName + "-Ensurer");
        ensurerThread.setDaemon(true);
        ensurerThread.start();

        // ?: Are we in charge of handling it?
        if (weAreInChargeOfHandlingUpdate) {
            // -> Yes, we are in charge of handling it, so fire off the delay-thread.
            // The delay-stuff is to handle the "thundering herd" problem, where all clients request a full update at
            // the same time - which otherwise would result in a lot of full updates being sent in parallel.

            // "If it is a long time since last invocation, it will be scheduled to run soon (~ within 1 second), while
            // if the previous time was a short time ago, it will be scheduled to run a bit later (~ within 7 seconds)."
            int initialDelay = (System.currentTimeMillis() - _lastFullUpdateStartedTimestamp) > 30_000
                    ? _shortDelay
                    : _longDelay;
            Thread scheduledUpdateThread = new Thread(() -> {
                // First sleep the initial delay.
                _takeNap(initialDelay);

                // ?: Was this a short sleep?
                if (initialDelay == _shortDelay) {
                    // -> Yes, short - now evaluate whether there have come in more requests while we slept.
                    int outstandingCount;
                    synchronized (this) {
                        outstandingCount = _updateRequest_OutstandingCount;
                    }
                    // ?: Have there come in more than the one that started the process?
                    if (outstandingCount > 1) {
                        // -> Yes, more requests incoming, so we'll do the long delay anyway
                        _takeNap(_longDelay - _shortDelay);
                    }
                }

                // ----- Okay, we've waited for more requests, now we'll initiate the update.
                // We also do this over broadcast, so that all siblings can see that we're doing it.

                BroadcastDto broadcast = new BroadcastDto();
                broadcast.command = BroadcastDto.COMMAND_SCHEDULE_SENDING_NOW;
                broadcast.handlingNodename = _privateNodename;
                broadcast.sentTimestamp = System.currentTimeMillis();
                broadcast.sentNanoTime = System.nanoTime();
                // Transfer the correlationId and requestNodename from the incoming message, if present.
                broadcast.correlationId = broadcastDto.correlationId;
                broadcast.requestNodename = broadcastDto.requestNodename;
                broadcast.requestSentTimestamp = broadcastDto.requestSentTimestamp;
                broadcast.requestSentNanoTime = broadcastDto.requestSentNanoTime;

                _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                    init.traceId(TraceId.create("EagerCache." + _dataName, "ScheduleRequest"))
                            .from("EagerCache." + _dataName)
                            .to(getBroadcastTopic(_dataName))
                            .publish(broadcast);
                });

            }, "MatsEagerCacheServer." + _dataName + "-ScheduledUpdateDelayer");
            scheduledUpdateThread.setDaemon(true);
            scheduledUpdateThread.start();
        }
    }

    private void _msg_schedule_SendNow(BroadcastDto broadcastDto) {
        log.info("\n\n======== Got a schedule sending now: " + broadcastDto.handlingNodename
                + (broadcastDto.handlingNodename.equals(_privateNodename) ? " (THIS IS US!)" : "(Not us!)"));

        // Reset count, starting process over.
        synchronized (this) {
            _updateRequest_OutstandingCount = 0;
        }
        // A full-update will be sent now, make note (for initialDelay evaluation)
        _lastFullUpdateStartedTimestamp = System.currentTimeMillis();

        // ?: So, is it us that should handle the actual broadcast of update
        // AND is there no other update in the queue? (If there are, that task will already send most recent data)
        if (_privateNodename.equals(broadcastDto.handlingNodename)
                && _produceAndSendExecutor.getQueue().isEmpty()) {
            // -> Yes it was us, and there are no other updates already in the queue.
            _produceAndSendExecutor.execute(() -> {
                log.info("\n\n======== Sending full update of cache [" + _dataName + "].\n\n");
                // Get the data

                SourceDataResult result = _createSourceDataResult();

                // Create the Broadcast message (which doesn't contain the actual data, but the metadata).
                BroadcastDto broadcast = new BroadcastDto();
                broadcast.command = BroadcastDto.COMMAND_UPDATE_FULL;
                broadcast.sentTimestamp = System.currentTimeMillis();
                broadcast.sentNanoTime = System.nanoTime();
                broadcast.dataCount = result.dataCountFromSourceProvider;
                broadcast.compressedSize = result.compressedSize;
                broadcast.uncompressedSize = result.uncompressedSize;
                broadcast.metadata = result.metadata;
                broadcast.millisSourceDataAndCompress = result.millisSourceDataAndCompress;
                broadcast.millisDeflateAndWrite = result.millisDeflateAndWrite;
                // Transfer the correlationId and requestNodename from the incoming message, if present.
                broadcast.correlationId = broadcastDto.correlationId;
                broadcast.requestNodename = broadcastDto.requestNodename;
                broadcast.requestSentTimestamp = broadcastDto.requestSentTimestamp;
                broadcast.requestSentNanoTime = broadcastDto.requestSentNanoTime;

                _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                    init.traceId(TraceId.create("EagerCache." + _dataName, "FullUpdate"))
                            .from("EagerCache." + _dataName + ".ScheduledFullUpdate")
                            .to(getBroadcastTopic(_dataName))
                            .addBytes(MATS_EAGER_CACHE_PAYLOAD, result.byteArray)
                            .publish(broadcast);
                });
            });
        }
    }

    private static void _takeNap(int millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            log.warn("Got interrupted while taking nap.", e);
            throw new IllegalStateException("Got interrupted while taking nap, unexpected.", e);
        }
    }

    private void _handleSiblingCommand(ProcessContext<Void> ctx, BroadcastDto broadcastDto) {
        byte[] bytes = ctx.getBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES);
        MatsEagerCacheServer.SiblingCommand siblingCommand = new MatsEagerCacheServer.SiblingCommand() {
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
        for (Consumer<MatsEagerCacheServer.SiblingCommand> listener : _siblingCommandEventListeners) {
            try {
                listener.accept(siblingCommand);
            }
            catch (Throwable t) {
                log.error("Got exception from SiblingCommandEventListener [" + listener
                        + "], ignoring.", t);
            }
        }
    }

    private SourceDataResult _createSourceDataResult() {
        MatsEagerCacheServer.CacheSourceDataCallback<?> sourceProvider = _dataSupplier.get();
        // We checked these at construction time. We'll just have to live with the uncheckedness.
        @SuppressWarnings("unchecked")
        MatsEagerCacheServer.CacheSourceDataCallback<Object> uncheckedSourceProvider = (CacheSourceDataCallback<Object>) sourceProvider;
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Function<Object, Object> uncheckedDataTypeMapper = o -> ((Function) _dataTypeMapper).apply(o);

        long nanosAsStart_gettingAndCompressingSourceData = System.nanoTime();
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
        int[] dataCount = new int[1];
        try {
            SequenceWriter jacksonSeq = _sentDataTypeWriter.writeValues(out);
            Consumer<Object> consumer = o -> {
                try {
                    jacksonSeq.write(uncheckedDataTypeMapper.apply(o));
                }
                catch (IOException e) {
                    // TODO: Log exception to monitor and HealthCheck.
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

        // Actual data count, not the guesstimate from the sourceProvider.
        int dataCountFromSourceProvider = dataCount[0];
        // Fetch the resulting byte array.
        byte[] byteArray = out.toByteArray();
        // Sizes in bytes
        long compressedSize = out.getCompressedBytesOutput();
        long uncompressedSize = out.getUncompressedBytesInput();
        // Timings
        double millisTaken_DeflateAndWrite = out.getDeflateAndWriteTimeNanos() / 1_000_000d;
        double millisTaken_gettingAndCompressingSourceData = (System.nanoTime()
                - nanosAsStart_gettingAndCompressingSourceData) / 1_000_000d;
        // Metadata from the source provider
        String metadata = sourceProvider.provideMetadata();
        return new SourceDataResult(dataCountFromSourceProvider, byteArray,
                compressedSize, uncompressedSize, metadata,
                millisTaken_gettingAndCompressingSourceData, millisTaken_DeflateAndWrite);
    }

    private static class SourceDataResult {
        public final int dataCountFromSourceProvider;
        public final byte[] byteArray;
        public final long compressedSize;
        public final long uncompressedSize;
        public final String metadata;
        public final double millisSourceDataAndCompress;
        public final double millisDeflateAndWrite;

        public SourceDataResult(int dataCountFromSourceProvider, byte[] byteArray,
                long compressedSize, long uncompressedSize, String metadata,
                double millisSourceDataAndCompress, double millisDeflateAndWrite) {
            this.dataCountFromSourceProvider = dataCountFromSourceProvider;
            this.byteArray = byteArray;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.metadata = metadata;
            this.millisSourceDataAndCompress = millisSourceDataAndCompress;
            this.millisDeflateAndWrite = millisDeflateAndWrite;
        }
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
        static final String COMMAND_SCHEDULE_REQUEST_RECEIVED = "SYNCH_NOTIFY";
        static final String COMMAND_SCHEDULE_SENDING_NOW = "SYNCH_SEND";

        String command;
        String correlationId;

        String requestNodename;
        long requestSentTimestamp;
        long requestSentNanoTime;

        long sentTimestamp;
        long sentNanoTime;

        // ===== For the actual updates to clients.
        int dataCount;
        String metadata;
        long uncompressedSize;
        long compressedSize;
        double millisSourceDataAndCompress;
        double millisDeflateAndWrite;
        // Note: The actual data is added as a sideload.

        // ====== For sibling commands
        String siblingCommand;
        String siblingStringData;
        // Note: Bytes are sideloaded
        String sentPrivateNodename;

        // ===== Mechanism to synchronize the updates
        String handlingNodename;
    }
}
