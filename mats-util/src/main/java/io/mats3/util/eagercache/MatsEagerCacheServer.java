package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.EnumSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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
 * {@link #scheduleFullUpdate()} or {@link #sendPartialUpdate(CacheSourceDataCallback)}.
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

    /**
     * The compressed data is added as a binary sideload, with this key.
     */
    static final String SIDELOAD_KEY_DATA_PAYLOAD = "dataPayload";
    /**
     * Solution to handle a situation where an update were in process of being produced, but the producing node went
     * down before it was sent. The other nodes will then wait for the update to be sent, but it will never be sent.
     * This is the time to wait for the update to be sent, before the other nodes will take over the production of the
     * update. (5 minutes)
     */
    public static final int ENSURER_WAIT_TIME_SHORT = 5 * 60 * 1000;
    /**
     * See {@link #ENSURER_WAIT_TIME_SHORT} - but if we're currently already creating a source data set, or if we
     * currently have problems making source data sets, we'll wait longer - to not loop too fast. (15 minutes)
     */
    public static final int ENSURER_WAIT_TIME_LONG = 15 * 60 * 1000;
    /**
     * If the interval since last update higher than this, the next update will be scheduled to run immediately or soon.
     * (30 seconds).
     */
    public static final int FAST_RESPONSE_LAST_RECV_THRESHOLD = 30_000;
    /**
     * Read {@link #scheduleFullUpdate()} for the rationale behind these delays.
     */
    public static final int DEFAULT_SHORT_DELAY = 2500;
    /**
     * Read {@link #scheduleFullUpdate()} for the rationale behind these delays.
     */
    public static final int DEFAULT_LONG_DELAY = 7000;

    /**
     * During startup, we need to first ensure that we can make a source data set before firing up the endpoints. If it
     * fails, it will try again until it manages - but sleep an amount between each attempt. Capped exponential from 2
     * seconds, this is the max sleep time between attempts. (30 seconds)
     */
    public static final int MAX_INTERVAL_BETWEEN_STARTUP_ATTEMPTS = 30_000;

    private final MatsFactory _matsFactory;
    private final String _dataName;
    private final Supplier<CacheSourceDataCallback<?>> _fullDataCallbackSupplier;
    private final Function<?, ?> _dataTypeMapper;
    private final int _forcedUpdateIntervalMinutes;

    private final String _privateNodename;
    private final ObjectWriter _sentDataTypeWriter;
    private final ThreadPoolExecutor _produceAndSendExecutor;

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <SRC, SENT> MatsEagerCacheServer(MatsFactory matsFactory, String dataName, Class<SENT> sentDataType,
            int forcedUpdateIntervalMinutes, Supplier<CacheSourceDataCallback<SRC>> fullDataCallbackSupplier,
            Function<SRC, SENT> dataTypeMapper) {

        _matsFactory = matsFactory;
        _dataName = dataName;
        _fullDataCallbackSupplier = (Supplier<CacheSourceDataCallback<?>>) (Supplier) fullDataCallbackSupplier;
        _dataTypeMapper = dataTypeMapper;
        _forcedUpdateIntervalMinutes = forcedUpdateIntervalMinutes;

        // Generate private nodename, which is used to identify the instance amongst the cache servers, e.g. with
        // Sibling Commands. Doing this since this Cache API does not expose the nodename, only whether it is "us" or
        // not. This makes testing a tad simpler, than using the default nodename, which is 'hostname'.
        _privateNodename = matsFactory.getFactoryConfig().getNodename() + "."
                + Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);

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

    private enum LifeCycle {
        NOT_YET_STARTED, STARTING, RUNNING, STOPPING, STOPPED;
    }

    private volatile LifeCycle _lifeCycle = LifeCycle.NOT_YET_STARTED;
    private volatile CountDownLatch _waitForRunningLatch = new CountDownLatch(1);
    private volatile MatsEndpoint<Void, Void> _broadcastTerminator;
    private volatile MatsEndpoint<Void, Void> _requestTerminator;

    // Synchronized on 'this' due to transactional needs.
    private int _updateRequest_OutstandingCount;

    private volatile boolean _currentlyMakingSourceDataResult;
    private volatile boolean _currentlyHavingProblemsCreatingSourceDataResult;

    private volatile long _lastUpdateRequestReceivedTimestamp;
    private volatile long _lastUpdateProductionStartedTimestamp;
    private volatile long _lastUpdateReceivedTimestamp;
    private volatile long _lastFullUpdateReceivedTimestamp;

    // Use a lock to make sure that only one thread is producing and sending an update at a time, and make it fair
    // so that entry into the method is sequenced in the order of the requests.
    private final ReentrantLock _produceAndSendUpdateLock = new ReentrantLock(true);

    private final CopyOnWriteArrayList<Consumer<SiblingCommand>> _siblingCommandEventListeners = new CopyOnWriteArrayList<>();

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
        if (_lifeCycle != LifeCycle.RUNNING) {
            throw new IllegalStateException("The MatsEagerCacheServer is not RUNNING, it is [" + _lifeCycle + "].");
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
        _broadcastTerminator.waitForReceiving(FAST_RESPONSE_LAST_RECV_THRESHOLD);

        // Send the broadcast
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(TraceId.create("EagerCache." + _dataName, "SiblingCommand").add("cmd", command))
                    .addBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES, binaryData)
                    .from("MatsEagerCacheServer." + _dataName + ".SiblingCommand")
                    .to(_getBroadcastTopic(_dataName))
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
     * Manually schedules a full update of the cache, typically used to programmatically propagate a change in the
     * source data.
     * <p>
     * It is scheduled to run after a little while, the delay being a function of how soon since the last time a full
     * update was run. If it is a long time since last update was run, it will effectively be run immediately, while if
     * the previous time was a short time ago, it will be scheduled to run a bit later (~ within 7 seconds). The reason
     * for this logic is to try to mitigate "thundering herd" problems, where many update request at the same time -
     * either by this server-side method, or the client's similar method, or more importantly, by multiple booting
     * clients - would result in a lot of full updates being produced, sent, and processed in parallel. Such a situation
     * occurs if many clients boot at the same time, e.g. with a "cold boot" of the entire system. The system attempts
     * to handle multiple servers by synchronizing who sends an update using a small broadcast protocol between them.
     * <p>
     * Note: When clients boot, they ask for a full update with reason "boot". This adjusts the "immediately" timing
     * mentioned above to 2.5 seconds, in a hope of capturing more of the clients that boot at the same time.
     * <p>
     * The data to send is retrieved by the cache server using the {@link CacheSourceDataCallback} supplier provided
     * when constructing the cache server.
     * <p>
     * The update is asynchronous - this method returns immediately.
     */
    public void scheduleFullUpdate() {
        _scheduleFullUpdate(null);
    }

    /**
     * (Optional functionality) Immediately sends a partial update to all cache clients. This should be invoked if the
     * source data has been partially updated (e.g. a user has made an update to a few of the cached entities using some
     * GUI), and we want to propagate this partial update to all cache clients, aiming to reduce the strain on the
     * messaging system and processing and memory churn on the clients.
     * <p>
     * This method is synchronous, and returns when the data has been consumed and the partial update has been sent out
     * to clients. If the cache server is currently in the process of producing and sending another full or partial
     * update, this method will be held until the current update is finished - there is an exclusive lock around the
     * production and sending process.
     * <p>
     * <b>It is imperative that the source data is NOT locked <i>when this method is invoked</i></b> - any locking must
     * ONLY be done when the cache server invokes the supplied
     * {@link CacheSourceDataCallback#provideSourceData(Consumer) partialDataCallback.provideSourceData(Consumer)}
     * method to retrieve the source data. Failure to observe this will eventually result in a deadlock. The reason is
     * that a full update may concurrently be in process, which also will lock the source data when the full update
     * callback is invoked, and if that full update starts before this partial update which wrongly holds the lock,
     * we're stuck.
     * <p>
     * It is also important to let the stream of partial data returned from
     * {@link CacheSourceDataCallback#provideSourceData(Consumer) partialDataCallback.provideSourceData(Consumer)} read
     * directly from the source data structures, and not from some temporary not-applied representation, as otherwise
     * the partial update might send out data that is older than what is currently present and which might have already
     * been sent via a concurrent update (think about races here). Thus, always first apply the update to the source
     * data (on all the instances running the Cache Server) in some atomic fashion (read up on {@link SiblingCommand}s),
     * and then retrieve the partial update from the source data, also in an atomic fashion (e.g. use synchronization or
     * locking).
     * <p>
     * On a general basis, it is important to realize that you are responsible for keeping the source data in sync
     * between the different instances of the service, and that the cache server only serves the data to the clients -
     * and such serving can happen from any one of the service instances. This is however even more important to
     * understand wrt. partial updates: The Cache Clients will all get the partial update - but this does not hold for
     * the instances running the Cache Server: You must first ensure that all these instances have the partial update
     * applied, before propagating it to the Cache Clients. This is not really a problem if you serve the data from a
     * database, as you then have an external single source of truth, but if you serve the data from memory, you must
     * ensure that the data is kept in sync between the instances. The {@link SiblingCommand} feature is meant to help
     * with this.
     * <p>
     * Partial updates is an optional functionality to conserve resources for situations where somewhat frequent partial
     * updates is performed to the source data. The caching system will work just fine with only full updates. If the
     * feature is employed, the client side must also be coded up to handle partial updates, as otherwise the client
     * will end up with stale data.
     * <p>
     * Correctly applying a partial update on the client can be more complex than consuming a full update, as the client
     * must merge the partial update into the existing data structures, taking care to overwrite where appropriate, but
     * insert if the entity is new. This is why the feature is optional, and should only be used if the source data is
     * large and/or updated frequently enough to make the use of partial updates actually have a positive performance
     * impact outweighing the increased complexity on both the server and the client side.
     * <p>
     * It is advisable to not send a lot of partial updates in a short time span, as this will result in memory churn
     * and higher memory usage on the clients due to message reception and partial update merge. Rather coalesce the
     * partial updates into a single update, or use a waiting mechanism until the source data has stabilized before
     * sending out a partial update - or just send a full update.
     * <p>
     * Also, if the partial update is of a substantial part of the full data, it is advisable to send a full update
     * instead - this can actually give lower peak memory load on the clients, as they will then just throw away the old
     * data before updating with the new data.
     * <p>
     * There is no solution for sending partial delete updates from the cache, so to remove an element from the cache, a
     * full update must be performed.
     *
     * @param partialDataCallback
     *            the callback which the cache server invokes to retrieve the source data to send to the clients.
     */
    public <SRC> void sendPartialUpdate(CacheSourceDataCallback<SRC> partialDataCallback) {
        _produceAndSendUpdate(null, () -> partialDataCallback, false);
    }

    /**
     * Starts the cache server. It will first assert that it can get hold of and serialize the source data, and then
     * start the cache server endpoints. If it fails to get the source data, it will keep trying until it succeeds. The
     * startup procedures are asynchronous, and this method returns immediately. HealthChecks will not reply "ready"
     * until the cache server is fully running.
     * <p>
     * This logic is to handle a situation where if we for some reason are unable to perform a full update (e.g. can't
     * load the source data from a database), we don't want to get requests for update since we can't fulfill them, and
     * we don't want to continue any rolling update of the service instances: The other instances might still have the
     * data loaded and can thus still serve the data to cache clients, so better to hold back / break the deploy and
     * "call in the humans".
     * 
     * @return this instance, for chaining.
     */
    public MatsEagerCacheServer start() {
        return _start();
    }

    /**
     * Shuts down the cache server. It stops the endpoints, and the cache server will no longer be able to serve cache
     * clients. Closing is idempotent, and multiple invocations will not have any effect.
     */
    public void close() {
        synchronized (this) {
            // ?: Are we running? Note: we accept multiple close() invocations, as the close-part is harder to lifecycle
            // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
            if (_lifeCycle == LifeCycle.RUNNING) {
                _lifeCycle = LifeCycle.STOPPING;
                // -> Yes, we are started, so close down.
                _broadcastTerminator.stop(30_000);
                _requestTerminator.stop(30_000);
                _produceAndSendExecutor.shutdown();
                // We're now stopped.
                _lifeCycle = LifeCycle.STOPPED;
            }
        }
    }

    // ======== Implementation / Internal methods ========

    static String _getCacheRequestQueue(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
    }

    static String _getBroadcastTopic(String dataName) {
        return "mats.MatsEagerCache." + dataName + ".Broadcast";
    }

    private MatsEagerCacheServer _start() {
        // ?: Have we already started?
        if (_lifeCycle != LifeCycle.NOT_YET_STARTED) {
            // -> Yes, we have already started - so you evidently have no control over the lifecycle of this object!
            throw new IllegalStateException("The MatsEagerCacheServer should be NOT_YET_STARTED when starting, it is ["
                    + _lifeCycle + "].");
        }

        _lifeCycle = LifeCycle.STARTING;

        // Create thread that checks if we actually can request the Source Data
        Thread checkThread = new Thread(() -> {
            // We'll keep trying until we succeed.
            long sleepTimeBetweenAttempts = 2000;
            while (_lifeCycle == LifeCycle.STARTING) {
                // Try to get the data from the source provider.
                try {
                    log.info("Asserting that we can get Source Data.");
                    SourceDataResult result = _createSourceDataResult(_fullDataCallbackSupplier);
                    log.info("Success: We asserted that we can get Source Data! Data count:["
                            + result.dataCountFromSourceProvider + "]");

                    // Start the endpoints
                    _createCacheEndpoints();

                    // We're now running.
                    _lifeCycle = LifeCycle.RUNNING;
                    _waitForRunningLatch.countDown();
                    _waitForRunningLatch = null;
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
                sleepTimeBetweenAttempts = (long) Math.min(MAX_INTERVAL_BETWEEN_STARTUP_ATTEMPTS,
                        sleepTimeBetweenAttempts
                                * 1.5);
            }
        }, "MatsEagerCacheServer." + _dataName + "-InitialPopulationCheck");
        checkThread.setDaemon(true);
        checkThread.start();

        // For chaining
        return this;
    }

    private void _createCacheEndpoints() {
        // ::: Create the Mats endpoints
        // :: The endpoint that the clients will send requests to
        // Note: We set concurrency to 1. We have this anti-thundering-herd mechanism whereby if many clients request
        // updates at the same time, we will only send one update satisfying them all. There is a mechanism to handle
        // this across multiple cache servers, whereby we broadcast "I'm going to do it". But having multiple stage
        // processors for the cache request terminator on each node makes no sense.
        _requestTerminator = _matsFactory.terminator(_getCacheRequestQueue(_dataName), void.class,
                CacheRequestDto.class,
                endpointConfig -> endpointConfig.setConcurrency(1),
                MatsFactory.NO_CONFIG, (ctx, state, msg) -> {
                    _scheduleFullUpdate(msg);
                });

        // :: Listener to the update topic.
        // To be able to see that a sibling has sent an update, we need to listen to the broadcast topic for the
        // updates. This enables us to not send an update if a sibling has already sent one.
        // This is also the topic which will be used by the siblings to send commands to each other (which the clients
        // will ignore).
        // It is a pretty hard negative to receiving the actual updates on the servers, which can be large - even though
        // it already has the data. However, we won't have to decompress/deserialize the data, so the hit won't be that
        // big. The obvious alternative is to have a separate topic for the commands, but that would pollute the MQ
        // Destination namespace with one extra topic per cache.
        _broadcastTerminator = _matsFactory.subscriptionTerminator(_getBroadcastTopic(_dataName), void.class,
                BroadcastDto.class, (ctx, state, broadcastDto) -> {
                    log.info("Got a broadcast: " + broadcastDto.command);
                    // ?: Is this a sibling command?
                    if (BroadcastDto.COMMAND_SIBLING_COMMAND.equals(broadcastDto.command)) {
                        _handleSiblingCommand(ctx, broadcastDto);
                    }
                    // ?: Is this the internal "sync between siblings" abour having received a request for update?
                    else if (BroadcastDto.COMMAND_REQUEST_RECEIVED_BOOT.equals(broadcastDto.command)
                            || BroadcastDto.COMMAND_REQUEST_RECEIVED_MANUAL.equals(broadcastDto.command)
                            || BroadcastDto.COMMAND_REQUEST_ENSURER_FAILED.equals(broadcastDto.command)) {
                        _msg_scheduleRequestReceived(broadcastDto);
                    }
                    // ?: Is this the internal "sync between siblings" about now sending the update?
                    else if (BroadcastDto.COMMAND_REQUEST_SENDING.equals(broadcastDto.command)) {
                        _msg_schedule_SendNow(broadcastDto);
                    }
                    // ?: Is this the actual update sent to the clients - which we also get?
                    else if (BroadcastDto.COMMAND_UPDATE_FULL.equals(broadcastDto.command)
                            || BroadcastDto.COMMAND_UPDATE_PARTIAL.equals(broadcastDto.command)) {
                        // -> Jot down that the clients were sent an update, used when calculating the delays, and in
                        // the health check.
                        _lastUpdateReceivedTimestamp = System.currentTimeMillis();
                        if (broadcastDto.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)) {
                            _lastFullUpdateReceivedTimestamp = _lastUpdateReceivedTimestamp;
                        }
                    }
                    else {
                        log.warn("Got a broadcast with unknown command: " + broadcastDto.command);
                    }
                });
    }

    /**
     * We use the broadcast channel to sequence the incoming requests for updates. This should ensure that we get a
     * unified understanding of the order of incoming requests, and who should handle the update.
     */
    private void _scheduleFullUpdate(CacheRequestDto incomingClientCacheRequest) {
        log.info("\n\n======== Scheduling full update of cache [" + _dataName + "].\n\n");
        // ?: Have we started?
        if (_lifeCycle != LifeCycle.RUNNING) {
            // -> No, we have not started - so you evidently have no control over the lifecycle of this object!
            throw new IllegalStateException("The MatsEagerCacheServer should be RUNNING, it is [" + _lifeCycle + "].");
        }
        // Ensure that we ourselves can get the ping.
        _broadcastTerminator.waitForReceiving(FAST_RESPONSE_LAST_RECV_THRESHOLD);

        // Send a message, that we ourselves will get.
        BroadcastDto broadcast = new BroadcastDto();
        broadcast.handlingNodename = _privateNodename;
        broadcast.sentTimestamp = System.currentTimeMillis();
        broadcast.sentNanoTime = System.nanoTime();
        // ?: Was this a request sent from the client?
        if (incomingClientCacheRequest != null) {
            // -> Yes, this was a request from the client.
            // Manual or boot?
            broadcast.command = CacheRequestDto.COMMAND_REQUEST_MANUAL.equals(incomingClientCacheRequest.command)
                    ? BroadcastDto.COMMAND_REQUEST_RECEIVED_MANUAL
                    : BroadcastDto.COMMAND_REQUEST_RECEIVED_BOOT;
            broadcast.correlationId = incomingClientCacheRequest.correlationId;
            broadcast.requestNodename = incomingClientCacheRequest.nodename;
            broadcast.requestSentTimestamp = incomingClientCacheRequest.sentTimestamp;
            broadcast.requestSentNanoTime = incomingClientCacheRequest.sentNanoTime;
        }
        else {
            // -> This was a programmatic/manual invocation of 'scheduleFullUpdate()' on the server.
            broadcast.command = BroadcastDto.COMMAND_REQUEST_RECEIVED_MANUAL;
        }
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(TraceId.create("MatsEagerCache." + _dataName, "ScheduleFullUpdate")
                    .add("cmd", broadcast.command))
                    .from("MatsEagerCache." + _dataName + ".ScheduleFullUpdate")
                    .to(_getBroadcastTopic(_dataName))
                    .publish(broadcast);
        });
    }

    private void _msg_scheduleRequestReceived(BroadcastDto broadcastDto) {
        log.info("\n\n======== Got a schedule request received: " + broadcastDto.command
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
         * Brute-force solution at ensuring that if the responsible node doesn't manage to send the update (e.g.
         * crashes, boots, redeploys), someone else will: Make a thread on ALL nodes that in some minutes will check if
         * and when we last received a full update (the one meant for the clients, but which the servers also get), and
         * if it is not higher than when this thread started (which it should be if anyone performed any update), it
         * will initiate a new full update to try to remedy the situation.
         */
        // ?: Is this already an "ensurer failed" situation? (If so, no use in looping this)
        if (!BroadcastDto.COMMAND_REQUEST_ENSURER_FAILED.equals(broadcastDto.command)){
            // -> No, this is not an "ensurer failed" situation, so we'll start the ensurer.
            // Adjust the time to check based on situation: if we're currently making a source data set (indicating that
            // it might take very long time (we've experienced 20 minutes in a degenerate situation!), or having
            // problems creating source data, we'll wait longer.
            int waitTime = _currentlyHavingProblemsCreatingSourceDataResult || _currentlyMakingSourceDataResult
                    ? ENSURER_WAIT_TIME_LONG
                    : ENSURER_WAIT_TIME_SHORT;
            // Create the thread. There might be a few of these hanging around, but they are "no-ops" if the update is
            // performed. We could have a more sophisticated solution where we cancel any such ensurer thread if we
            // receive an update, but this will work just fine.
            Thread ensurerThread = new Thread(() -> {
                long timestampWhenEnsurerStarted = System.currentTimeMillis();
                _takeNap(waitTime);
                // ?: Have we received a full update since we started this ensurer?
                if (_lastFullUpdateReceivedTimestamp < timestampWhenEnsurerStarted) {
                    // -> No, we have not seen the full update yet, which is bad. Initiate a new full update.
                    log.warn("Ensurer failed: We have not seen the full update yet, initiating a new full update.");
                    // Note: This is effectively a message to this same handling method.
                    BroadcastDto broadcast = new BroadcastDto();
                    broadcast.handlingNodename = _privateNodename;
                    broadcast.sentTimestamp = System.currentTimeMillis();
                    broadcast.sentNanoTime = System.nanoTime();
                    broadcast.command = BroadcastDto.COMMAND_REQUEST_ENSURER_FAILED;
                    _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                        init.traceId(TraceId.create("MatsEagerCache." + _dataName, "FullUpdateEnsurer"))
                                .from("MatsEagerCache." + _dataName + ".FullUpdateEnsurer")
                                .to(_getBroadcastTopic(_dataName))
                                .publish(broadcast);
                    });
                }
            }, "MatsEagerCacheServer." + _dataName + "-EnsureDataIsSentEventually[" + waitTime + "ms]");
            ensurerThread.setDaemon(true);
            ensurerThread.start();
        }

        // ?: Are we in charge of handling it?
        if (weAreInChargeOfHandlingUpdate) {
            // -> Yes, we are in charge of handling it, so fire off the delay-thread.
            // The delay-stuff is to handle the "thundering herd" problem, where all clients request a full update at
            // the same time - which otherwise would result in a lot of full updates being sent in parallel.

            // "If it is a long time since last invocation, it will be scheduled to run soon, while if the previous time
            // was a short time ago, it will be scheduled to run a bit later (~ within 7 seconds)."

            // First find the latest time anything wrt. an update happened.
            long latestActivityTimestamp = Math.max(_lastUpdateRequestReceivedTimestamp,
                    Math.max(_lastUpdateProductionStartedTimestamp, _lastUpdateReceivedTimestamp));
            // If it is a "long time" since last activity, we'll do a fast response.
            boolean fastResponse = (System.currentTimeMillis()
                    - latestActivityTimestamp) > FAST_RESPONSE_LAST_RECV_THRESHOLD;
            // Calculate initial delay: Two tiers: If "fastResponse", decide by whether it was a manual request or not.
            int initialDelay = fastResponse
                    ? BroadcastDto.COMMAND_REQUEST_RECEIVED_MANUAL.equals(broadcastDto.command)
                            ? 0 // Immediate for manual
                            : _shortDelay // Short delay (i.e. longer) for boot
                    : _longDelay;

            Thread scheduledUpdateThread = new Thread(() -> {
                // First sleep the initial delay.
                _takeNap(initialDelay);

                // ?: Was this a short sleep?
                if (fastResponse) {
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
                broadcast.command = BroadcastDto.COMMAND_REQUEST_SENDING;
                broadcast.handlingNodename = _privateNodename; // It is us that is handling it.
                broadcast.sentTimestamp = System.currentTimeMillis();
                broadcast.sentNanoTime = System.nanoTime();
                // Transfer the correlationId and requestNodename from the incoming message (they might be null)
                broadcast.correlationId = broadcastDto.correlationId;
                broadcast.requestNodename = broadcastDto.requestNodename;
                broadcast.requestSentTimestamp = broadcastDto.requestSentTimestamp;
                broadcast.requestSentNanoTime = broadcastDto.requestSentNanoTime;

                _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                    init.traceId(TraceId.create("EagerCache." + _dataName, "ScheduleRequest"))
                            .from("EagerCache." + _dataName)
                            .to(_getBroadcastTopic(_dataName))
                            .publish(broadcast);
                });

            }, "MatsEagerCacheServer." + _dataName + "-ScheduledUpdateDelayer");
            scheduledUpdateThread.setDaemon(true);
            scheduledUpdateThread.start();
        }

        // Record that we've received a request for update.
        _lastUpdateRequestReceivedTimestamp = System.currentTimeMillis();
    }

    private void _msg_schedule_SendNow(BroadcastDto broadcastDto) {
        log.info("\n\n======== Got a schedule sending now: " + broadcastDto.handlingNodename
                + (broadcastDto.handlingNodename.equals(_privateNodename) ? " (THIS IS US!)" : "(Not us!)"));

        // Reset count, starting process over.
        synchronized (this) {
            _updateRequest_OutstandingCount = 0;
        }
        // A full-update will be sent now, make note (for initialDelay evaluation)
        _lastUpdateProductionStartedTimestamp = System.currentTimeMillis();

        // ?: So, is it us that should handle the actual broadcast of update
        // AND is there no other update in the queue? (If there are, that task will already send most recent data)
        if (_privateNodename.equals(broadcastDto.handlingNodename)
                && _produceAndSendExecutor.getQueue().isEmpty()) {
            // -> Yes it was us, and there are no other updates already in the queue.
            _produceAndSendExecutor.execute(() -> {
                log.info("\n\n======== Sending full update of cache [" + _dataName + "].\n\n");

                _produceAndSendUpdate(broadcastDto, _fullDataCallbackSupplier, true);
            });
        }
    }

    private void _produceAndSendUpdate(BroadcastDto incomingBroadcastDto,
            Supplier<CacheSourceDataCallback<?>> dataCallbackSupplier, boolean fullUpdate) {
        try {
            _produceAndSendUpdateLock.lock();

            // Create the SourceDataResult by asking the source provider for the data.
            SourceDataResult result = _createSourceDataResult(dataCallbackSupplier);

            // Create the Broadcast message (which doesn't contain the actual data, but the metadata).
            BroadcastDto broadcast = new BroadcastDto();
            broadcast.command = fullUpdate ? BroadcastDto.COMMAND_UPDATE_FULL : BroadcastDto.COMMAND_UPDATE_PARTIAL;
            broadcast.sentTimestamp = System.currentTimeMillis();
            broadcast.sentNanoTime = System.nanoTime();
            broadcast.dataCount = result.dataCountFromSourceProvider;
            broadcast.compressedSize = result.compressedSize;
            broadcast.uncompressedSize = result.uncompressedSize;
            broadcast.metadata = result.metadata;
            broadcast.millisTotalProduceAndCompress = result.millisTotalProduceAndCompress;
            broadcast.millisCompress = result.millisCompress;
            // Transfer the correlationId and requestNodename from the incoming message, if present.
            if (incomingBroadcastDto != null) {
                broadcast.correlationId = incomingBroadcastDto.correlationId;
                broadcast.requestNodename = incomingBroadcastDto.requestNodename;
                broadcast.requestSentTimestamp = incomingBroadcastDto.requestSentTimestamp;
                broadcast.requestSentNanoTime = incomingBroadcastDto.requestSentNanoTime;
            }

            String type = fullUpdate ? "Full" : "Partial";

            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                TraceId traceId = TraceId.create("MatsEagerCache." + _dataName, "Update")
                        .add("type", type)
                        .add("count", result.dataCountFromSourceProvider);
                if (result.metadata != null) {
                    traceId.add("meta", result.metadata);
                }
                init.traceId(traceId)
                        .from("MatsEagerCache." + _dataName + ".Update")
                        .to(_getBroadcastTopic(_dataName))
                        .addBytes(SIDELOAD_KEY_DATA_PAYLOAD, result.byteArray)
                        .publish(broadcast);
            });
        }
        finally {
            _produceAndSendUpdateLock.unlock();
        }
    }

    private static void _takeNap(long millis) {
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

    private SourceDataResult _createSourceDataResult(Supplier<CacheSourceDataCallback<?>> dataCallbackSupplier) {
        _currentlyMakingSourceDataResult = true;
        CacheSourceDataCallback<?> dataCallback = dataCallbackSupplier.get();
        // We checked these at construction time. We'll just have to live with the uncheckedness.
        @SuppressWarnings("unchecked")
        CacheSourceDataCallback<Object> uncheckedDataCallback = (CacheSourceDataCallback<Object>) dataCallback;
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Function<Object, Object> uncheckedDataTypeMapper = o -> ((Function) _dataTypeMapper).apply(o);

        long nanosAsStart_totalProduceAndCompressingSourceData = System.nanoTime();
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
        int[] dataCount = new int[1];
        try {
            SequenceWriter jacksonSeq = _sentDataTypeWriter.writeValues(out);
            Consumer<Object> consumer = o -> {
                try {
                    log.info("Type of object: " + o.getClass().getName());
                    Object mapped = uncheckedDataTypeMapper.apply(o);
                    jacksonSeq.write(mapped);
                    // TODO: Log progress to monitor and HealthCheck.
                    // TODO: Log each entity's size to monitor and HealthCheck. (up to max 1_000 entities)
                }
                catch (IOException e) {
                    // TODO: Log exception to monitor and HealthCheck.
                    throw new RuntimeException(e);
                }
                dataCount[0]++;
            };
            uncheckedDataCallback.provideSourceData(consumer);
            jacksonSeq.close();
            _currentlyHavingProblemsCreatingSourceDataResult = false;
        }
        catch (IOException e) {
            _currentlyHavingProblemsCreatingSourceDataResult = true;
            // TODO: Log exception to monitor and HealthCheck.
            throw new RuntimeException("Got interrupted while waiting for initial population to be done.",
                    e);
        }
        finally {
            _currentlyMakingSourceDataResult = false;
        }

        // Actual data count, not the guesstimate from the sourceCallback.
        int dataCountFromSourceProvider = dataCount[0];
        // Fetch the resulting byte array.
        byte[] byteArray = out.toByteArray();
        // Sizes in bytes
        assert byteArray.length == out.getCompressedBytesOutput() : "The byte array length should be the same as the"
                + " compressed size, but it was not. This is a bug.";
        long compressedSize = out.getCompressedBytesOutput();
        long uncompressedSize = out.getUncompressedBytesInput();
        // Timings
        double millisTaken_DeflateAndWrite = out.getDeflateAndWriteTimeNanos() / 1_000_000d;
        double millisTaken_totalProduceAndCompressingSourceData = (System.nanoTime()
                - nanosAsStart_totalProduceAndCompressingSourceData) / 1_000_000d;
        // Metadata from the source provider
        String metadata = dataCallback.provideMetadata();
        return new SourceDataResult(dataCountFromSourceProvider, byteArray,
                compressedSize, uncompressedSize, metadata,
                millisTaken_totalProduceAndCompressingSourceData, millisTaken_DeflateAndWrite);
    }

    private static class SourceDataResult {
        public final int dataCountFromSourceProvider;
        public final byte[] byteArray;
        public final long compressedSize;
        public final long uncompressedSize;
        public final String metadata;
        public final double millisTotalProduceAndCompress; // Total time to produce the Data set
        public final double millisCompress; // Compress (and write to byte array, but that's ~0) only

        public SourceDataResult(int dataCountFromSourceProvider, byte[] byteArray,
                long compressedSize, long uncompressedSize, String metadata,
                double millisTotalProduceAndCompress, double millisCompress) {
            this.dataCountFromSourceProvider = dataCountFromSourceProvider;
            this.byteArray = byteArray;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.metadata = metadata;
            this.millisTotalProduceAndCompress = millisTotalProduceAndCompress;
            this.millisCompress = millisCompress;
        }
    }

    void _waitForReceiving() {
        if (!EnumSet.of(LifeCycle.NOT_YET_STARTED, LifeCycle.STARTING, LifeCycle.RUNNING).contains(_lifeCycle)) {
            throw new IllegalStateException("The MatsEagerCacheServer is not NOT_YET_STARTED, STARTING or RUNNING,"
                    + " it is [" + _lifeCycle + "].");
        }
        try {
            // If the latch is there, we'll wait for it. (fast-path check for null)
            CountDownLatch latch = _waitForRunningLatch;
            if (latch != null) {
                boolean started = latch.await(1, TimeUnit.MINUTES);
                if (!started) {
                    throw new IllegalStateException("Did not start within 1 minute.");
                }
            }
        }
        catch (InterruptedException e) {
            throw new IllegalStateException("Got interrupted while waiting for the system to start.", e);
        }
        _broadcastTerminator.waitForReceiving(FAST_RESPONSE_LAST_RECV_THRESHOLD);
        _requestTerminator.waitForReceiving(FAST_RESPONSE_LAST_RECV_THRESHOLD);
    }

    static final class CacheRequestDto {
        static final String COMMAND_REQUEST_BOOT = "BOOT";
        static final String COMMAND_REQUEST_MANUAL = "MANUAL";

        String command;
        String correlationId;

        String nodename;
        long sentTimestamp;
        long sentNanoTime;
    }

    static final class BroadcastDto {
        static final String COMMAND_REQUEST_RECEIVED_BOOT = "REQ_RECV_BOOT";
        static final String COMMAND_REQUEST_RECEIVED_MANUAL = "REQ_RECV_MANUAL";
        static final String COMMAND_REQUEST_ENSURER_FAILED = "REQ_ENSURER_FAILED";
        static final String COMMAND_REQUEST_SENDING = "REQ_SEND";
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

        // ===== For the actual updates to clients.
        int dataCount;
        String metadata;
        long uncompressedSize;
        long compressedSize;

        double millisTotalProduceAndCompress; // Total time to produce the Data set
        double millisCompress; // Compress (and write to byte array, but that's ~0) only
        // Note: The actual Deflated data is added as a binary sideload: 'SIDELOAD_KEY_SOURCE_DATA'

        // ====== For sibling commands
        String siblingCommand;
        String siblingStringData;
        // Note: Bytes are sideloaded
        String sentPrivateNodename;

        // ===== Mechanism to synchronize the updates
        String handlingNodename;
    }
}
