package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer.LogLevel.INFO;
import static io.mats3.util.eagercache.MatsEagerCacheServer.LogLevel.WARN;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatBytes;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatMillis;
import static io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl._formatNiceBytes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.TraceId;
import io.mats3.util.compression.InflaterInputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientImpl.CacheUpdatedImpl;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheDataCallback;
import io.mats3.util.eagercache.MatsEagerCacheServer.ExceptionEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.LogEntry;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.BroadcastDto;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.CacheMonitor;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.CacheRequestDto;
import io.mats3.util.eagercache.MatsEagerCacheServer.MonitorCategory;

/**
 * The client for the Mats Eager Cache system. This client will connect to a Mats Eager Cache server, and receive data
 * from it. The client will block {@link #get()}-invocations until the initial full population is done, and during
 * subsequent repopulations.
 * <p>
 * <b>Most of the MatsEagerCache system is documented in the {@link MatsEagerCacheServer} class, which is the server
 * part of the system.</b> Only the client-specific parts are documented here.
 * <p>
 * <b>Note wrt. developing a Client along with the corresponding Server:</b> You'll face a problem where both the server
 * and the client wants to listen to the same topic, which MatsFactory forbids. The solution is to use the "linked
 * forwarding server" functionality, where the linked server forwards the cache updates to the client. This is done by
 * invoking {@link #linkToServer(MatsEagerCacheServer)} before calling {@link #start()}.
 * <p>
 * Thread-safety: This class is thread-safe after construction and {@link #start()} has been invoked, specifically
 * {@link #get()} is meant to be invoked from multiple threads.
 * 
 * @param <DATA>
 *            the type of the data structures object that shall be returned to the "end user" - this is opposed to the
 *            additional {@literal <TRANSFER>} type in the constructor, which is the type that the server sends.
 * 
 * @author Endre Stølsvik 2024-09-03 19:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsEagerCacheClient<DATA> {
    String LOG_PREFIX = "#MatsEagerCache#S ";

    int DEFAULT_SIZE_CUTOVER = 15 * 1024 * 1024; // 15 MB as default.

    /**
     * Factory for the Mats Eager Cache client, only taking full updates. The client will connect to a Mats Eager Cache
     * server, and receive data from it. The client will block {@link #get()}-invocations until the initial full
     * population is done, and during subsequent repopulations.
     * <p>
     * Note that the corresponding server then must only send full updates.
     *
     * @param matsFactory
     *            the MatsFactory to use for the Mats Eager Cache client.
     * @param dataName
     *            the name of the data that the client will receive.
     * @param transferDataType
     *            the type of the received/transfer data (the type that the server sends).
     * @param fullUpdateMapper
     *            the function that will be invoked when a full update is received from the server. It is illegal to
     *            return <code>null</code> or throw from this function, which will result in the client throwing an
     *            exception on {@link #get()}.
     * @param <TRANSFER>
     *            the type of the transferDataType - which obviously must correspond to the type of the data that the
     *            server sends, but importantly, the Jackson serializer is configured very leniently, so it will accept
     *            any kind of JSON structure, and will not fail on missing fields or extra fields.
     * @param <DATA>
     *            the type of the data structures object that shall be returned by {@link #get()}.
     */
    static <TRANSFER, DATA> MatsEagerCacheClient<DATA> create(MatsFactory matsFactory, String dataName,
            Class<TRANSFER> transferDataType, Function<CacheReceivedData<TRANSFER>, DATA> fullUpdateMapper) {
        return new MatsEagerCacheClientImpl<>(matsFactory, dataName, transferDataType, fullUpdateMapper);
    }

    /**
     * Factory for the Mats Eager Cache client, taking both full and partial updates. The client will connect to a Mats
     * Eager Cache server, and receive data from it. The client will block {@link #get()}-invocations until the initial
     * full population is done, and during subsequent repopulations.
     * <p>
     * Read more about the partial update mapper in the JavaDoc for {@link CacheReceivedPartialData} and in particular
     * the JavaDoc for {@link MatsEagerCacheServer#sendPartialUpdate(CacheDataCallback)}, and make sure to heed the
     * warning about not performing in-place updating.
     *
     * @param matsFactory
     *            the MatsFactory to use for the Mats Eager Cache client.
     * @param dataName
     *            the name of the data that the client will receive.
     * @param transferDataType
     *            the type of the received/transfer data (the type that the server sends).
     * @param fullUpdateMapper
     *            the function that will be invoked when a full update is received from the server. It is illegal to
     *            return null or throw from this function, which will result in the client throwing an exception on
     *            {@link #get()}.
     * @param partialUpdateMapper
     *            the function that will be invoked when a partial update is received from the server. It is illegal to
     *            return <code>null</code> or throw from this function, which will result in the client throwing an
     *            exception on {@link #get()}.
     * @param <TRANSFER>
     *            the type of the transferDataType - which obviously must correspond to the type of the data that the
     *            server sends, but importantly, the Jackson serializer is configured very leniently, so it will accept
     *            any kind of JSON structure, and will not fail on missing fields or extra fields.
     * @param <DATA>
     *            the type of the data structures object that shall be returned by {@link #get()}.
     *
     * @see CacheReceivedPartialData
     * @see MatsEagerCacheServer#sendPartialUpdate(CacheDataCallback)
     */
    static <TRANSFER, DATA> MatsEagerCacheClient<DATA> create(MatsFactory matsFactory, String dataName,
            Class<TRANSFER> transferDataType, Function<CacheReceivedData<TRANSFER>, DATA> fullUpdateMapper,
            Function<CacheReceivedPartialData<TRANSFER, DATA>, DATA> partialUpdateMapper) {
        MatsEagerCacheClientImpl<DATA> client = new MatsEagerCacheClientImpl<>(matsFactory, dataName, transferDataType,
                fullUpdateMapper);
        client.setPartialUpdateMapper(partialUpdateMapper);
        return client;
    }

    /**
     * Creates a mock of the MatsEagerCacheClient for testing purposes. This mock is purely in-memory, and will not
     * connect to any server, but instead will use the provided data or data supplier as the data. Data must be provided
     * using one of {@link MatsEagerCacheClientMock#setMockData(Object) setMockData(DATA)} or
     * {@link MatsEagerCacheClientMock#setMockDataSupplier(Supplier) setMockDataSupplier(Supplier&lt;DATA&gt;)} - read
     * the JavaDoc for {@link MatsEagerCacheClientMock} for more information.
     *
     * @param dataName
     *            the name of the data that the client will receive - not really used for the mock.
     * @return a mock of the MatsEagerCacheClient.
     * @param <DATA>
     *            the type of the data structures object that shall be returned by {@link #get()}.
     */
    static <DATA> MatsEagerCacheClientMock<DATA> mock(String dataName) {
        return new MatsEagerCacheClientMockImpl<>(dataName);
    }

    // ----- Interface: Configuration methods

    /**
     * Add a runnable that will be invoked after the initial population is done. If the initial population is already
     * done, it will be invoked immediately by current thread. Note: Run ordering wrt. add ordering is not guaranteed.
     *
     * @param runnable
     *            the runnable to invoke after the initial population is done.
     * @return this instance, for chaining.
     */
    MatsEagerCacheClient<DATA> addAfterInitialPopulationTask(Runnable runnable);

    /**
     * Add a listener for cache updates. The listener will be invoked with the metadata about the cache update. The
     * listener is invoked after the cache content has been updated, and the cache content lock has been released.
     *
     * @param listener
     *            the listener to add.
     * @return this instance, for chaining.
     */
    MatsEagerCacheClient<DATA> addCacheUpdatedListener(Consumer<CacheUpdated> listener);

    /**
     * Sets the size cutover for the cache: The size in bytes for the uncompressed JSON where the cache will consider
     * the update to be "large", and hence use a different scheme for handling the update. The default is 15 MB.
     *
     * @param size
     *            the size hint.
     */
    void setSizeCutover(int size);

    // ----- Interface: Lifecycle methods

    /**
     * Starts the cache client - startup is performed in a separate thread, so this method immediately returns - to wait
     * for initial population, use the {@link #addAfterInitialPopulationTask(Runnable) dedicated functionality}. The
     * cache client creates a SubscriptionTerminator for receiving cache updates based on the dataName. Once we're sure
     * this endpoint {@link MatsEndpoint#waitForReceiving(int) has entered the receive loop}, a message to the server
     * requesting update is sent. The {@link #get()} method will block until the initial full population is received and
     * processed. When the initial population is done, any {@link #addAfterInitialPopulationTask(Runnable)
     * onInitialPopulationTasks} will be invoked.
     *
     * @return this instance, for chaining.
     */
    MatsEagerCacheClient<DATA> start();

    /**
     * Links a client to a server for development and testing of a cache solution with both the server and the client in
     * the same codebase, to circumvent the problem where both the server and the client needs to listen to the same
     * topic, which MatsFactory forbids. By invoking this method before the {@link #start()} call, this client will NOT
     * fire up its SubscriptionTerminator, but will rely on the server to forward the cache updates to the client -
     * otherwise, the client is started as normal. This method is intended for development and testing only, and should
     * not be used in production.
     *
     * @param forwardingServer
     *            the server that will forward the cache updates to this client.
     * @return this instance, for chaining.
     */
    MatsEagerCacheClient<DATA> linkToServer(MatsEagerCacheServer forwardingServer);

    /**
     * Close down the cache client. This will stop and remove the SubscriptionTerminator for receiving cache updates,
     * and shut down the ExecutorService for handling the received data. Closing is idempotent; Multiple invocations
     * will not have any effect. It is not possible to restart the cache client after it has been closed.
     */
    void close();

    // ----- Interface: Client data access

    /**
     * Returns the data - will block until the initial full population is done, and during subsequent repopulations.
     * <b>It is imperative that neither the data object itself, or any of its contained larger data or structures (e.g.
     * any Lists or Maps) aren't read out and stored in any object, or held onto by any threads for any timespan above
     * some few milliseconds, but rather queried anew from the cache for every needed access.</b> This both to ensure
     * timely update when new data arrives, but more importantly to avoid that memory isn't held up by the old data
     * structures being kept alive (this is particularly important for large caches).
     * <p>
     * It is legal for a thread to call this method before {@link #start()} is invoked, but then some other (startup)
     * thread must eventually invoke {@link #start()}, otherwise you'll have a deadlock.
     *
     * @return the cached data
     */
    DATA get();

    // ----- Interface: Request update and information methods

    /**
     * If a user in some management GUI wants to force a full update, this method can be invoked. This will send a
     * request to the server to perform a full update, which will be broadcast to all clients.
     * <p>
     * <b>This must NOT be used to "poll" the server for updates on a schedule or similar</b>, as that is utterly
     * against the design of the Mats Eager Cache system. The Mats Eager Cache system is designed to be a "push" system,
     * where the server pushes updates to the clients when it has new data - or, as a backup, on a schedule. But this
     * <i>shall</i> be server handled, not client handled.
     */
    void requestFullUpdate();

    /**
     * Returns a "live view" of the cache client information, that is, you only need to invoke this method once to get
     * an instance that will always reflect the current state of the cache client.
     *
     * @return a "live view" of the cache client information.
     */
    CacheClientInformation getCacheClientInformation();

    /**
     * Mock of the MatsEagerCacheClient for testing purposes. This mock is purely in-memory, and will not connect to any
     * message broker or cache server, but will instead use the provided data, or data supplier, as the returned cached
     * data. The goal is to simulate a real cache client as closely as possible, with some "asynchronous lags" added.
     * <ul>
     * <li>You may supply the data using one of two methods {@link #setMockData(Object)} or
     * {@link #setMockDataSupplier(Supplier)} - the latter might be useful if you need to know when the data is created.
     * The latest invoked of these two methods will be the one that is used.
     * <li>The mock will block on {@link #get()} until after {@link #start()} has been invoked, and will not block on
     * {@link #get()} after that.
     * <li>Any after-initial-population tasks added with {@link #addAfterInitialPopulationTask(Runnable)} will be
     * invoked by another thread some 5 ms after {@link #start()} has been invoked - the same thread right before
     * releases the latch that {@link #get()} blocks on.
     * <li>Any listeners added with {@link #addCacheUpdatedListener(Consumer)} will be invoked some 5 ms after
     * {@link #start()} has been invoked, and then some 5 ms after each time {@link #requestFullUpdate()} has been
     * invoked.
     * </ul>
     * <p>
     * The information returned from {@link #getCacheClientInformation()} is rather mocky, but the
     * {@link CacheClientInformation#getNumberOfAccesses()} will be updated each time {@link #get()} is invoked, which
     * might be of interest in a testing scenario.
     *
     * @param <DATA>
     */
    interface MatsEagerCacheClientMock<DATA> extends MatsEagerCacheClient<DATA> {
        /**
         * The "simulated lags" introduced by the mock after {@link #start()} and {@link #requestFullUpdate()} are
         * invoked. (5 milliseconds).
         */
        int MILLIS_LAG = 5;

        /**
         * Directly set the mock data. This will be the data that {@link #get()} will return.
         * 
         * @param data
         *            the data to set.
         * @return this instance, for chaining.
         */
        MatsEagerCacheClientMock<DATA> setMockData(DATA data);

        /**
         * Set the mock data supplier. This supplier will be invoked each time {@link #get()} is invoked.
         * 
         * @param dataSupplier
         *            the data supplier to set.
         * @return this instance, for chaining.
         */
        MatsEagerCacheClientMock<DATA> setMockDataSupplier(Supplier<DATA> dataSupplier);

        /**
         * Set the mock cache updated supplier - used when invoking the cache updated listeners, both on
         * {@link #start()} and on {@link #requestFullUpdate()}. If set to null, a dummy CacheUpdated object will be
         * created.
         * 
         * @param cacheUpdatedSupplier
         *            the cache updated supplier to set - or null to use a dummy.
         * @return this instance, for chaining.
         */
        MatsEagerCacheClientMock<DATA> setMockCacheUpdatedSupplier(Supplier<CacheUpdated> cacheUpdatedSupplier);

        /**
         * This method is overridden to throw an {@link IllegalStateException}, as it makes no sense to start a mock
         * cache client linked to a server.
         *
         * @param forwardingServer
         *            not used.
         * @return nothing, as the method throws.
         */
        @Override
        default MatsEagerCacheClientMock<DATA> linkToServer(MatsEagerCacheServer forwardingServer) {
            throw new IllegalStateException("It makes absolutely no sense to start a mock cache client linked to a"
                    + " server, thus this method throws.");
        }
    }

    enum CacheClientLifecycle {
        NOT_YET_STARTED, STARTING_AWAITING_INITIAL, RUNNING, STOPPING, STOPPED;
    }

    interface CacheClientInformation {
        String getDataName();

        String getNodename();

        CacheClientLifecycle getCacheClientLifeCycle();

        String getBroadcastTopic();

        boolean isInitialPopulationDone();

        long getCacheStartedTimestamp();

        long getInitialPopulationRequestSentTimestamp();

        long getInitialPopulationTimestamp();

        long getAnyUpdateReceivedTimestamp();

        long getLastFullUpdateReceivedTimestamp();

        long getLastPartialUpdateReceivedTimestamp();

        double getLastUpdateDurationMillis();

        long getLastUpdateCompressedSize();

        long getLastUpdateDecompressedSize();

        int getLastUpdateDataCount();

        String getLastUpdateMetadata();

        boolean isLastUpdateFull();

        boolean isLastUpdateLarge();

        int getNumberOfFullUpdatesReceived();

        int getNumberOfPartialUpdatesReceived();

        long getNumberOfAccesses();

        List<LogEntry> getLogEntries();

        List<ExceptionEntry> getExceptionEntries();
    }

    /**
     * Metadata about the cache update.
     */
    interface CacheReceived {
        /**
         * @return whether this was a full update.
         */
        boolean isFullUpdate();

        /**
         * @return number of data items received.
         */
        int getDataCount();

        /**
         * @return the size of the compressed data, in bytes.
         */
        long getCompressedSize();

        /**
         * @return the size of the uncompressed data (probably JSON), in bytes.
         */
        long getUncompressedSize();

        /**
         * @return the metadata that was sent along with the data, if any - otherwise {@code null}.
         */
        String getMetadata();
    }

    /**
     * Object provided to any cache update listener, containing the metadata about the cache update.
     */
    interface CacheUpdated extends CacheReceived {
        double getUpdateDurationMillis();
    }

    /**
     * Object that is provided to the 'fullUpdateMapper' {@link Function} which was provided to the constructor of
     * {@link MatsEagerCacheClient}. This object contains the data that was received from the server, and the metadata
     * that was sent along with the data.
     *
     * @param <TRANSFER>
     *            the type of the received data.
     */
    interface CacheReceivedData<TRANSFER> extends CacheReceived {
        /**
         * @return the received data as a Stream.
         */
        Stream<TRANSFER> getReceivedDataStream();
    }

    /**
     * Object that is provided to the 'partialUpdateMapper' {@link Function} which was provided to the constructor of
     * {@link MatsEagerCacheClient}. This object contains (in addition to metadata) both the data that was received from
     * the server, and the previous 'D' data structures, whose structures (e.g. Lists, Sets or Maps) should be read out
     * and cloned/copied, and the copies updated with the new data before returning the newly created 'D' data
     * structures object.
     * <p>
     * <b>it is important that the existing structures (e.g. Lists or Maps of cached entities) aren't updated in-place
     * (as they can potentially simultaneously be accessed by other threads), but rather that each of the structures
     * (e.g. Lists or Maps of entities) are cloned or copied, and then the new data is overwritten or appended to in
     * this copy.</b>
     * 
     * @param <TRANSFER>
     *            the type of the received data.
     * @param <DATA>
     *            the type of the data structures object that shall be returned.
     *
     * @see MatsEagerCacheServer#sendPartialUpdate(CacheDataCallback)
     */
    interface CacheReceivedPartialData<TRANSFER, DATA> extends CacheReceivedData<TRANSFER> {
        /**
         * Returns the previous data, whose structures (e.g. Lists, Sets or Maps of entities) should be read out and
         * cloned/copied, and the copies updated or appended with the new data before returning the newly created data
         * object. <b>This means: No in-place updating on the existing structures!</b>
         *
         * @return the previous data.
         */
        DATA getPreviousData();
    }

    // ======== The 'MatsEagerCacheClient' implementation class

    class MatsEagerCacheClientImpl<DATA> implements MatsEagerCacheClient<DATA> {
        private static final Logger log2 = LoggerFactory.getLogger(MatsEagerCacheClient.class);

        private final MatsFactory _matsFactory;
        private final String _dataName;
        private final Function<CacheReceivedData<?>, DATA> _fullUpdateMapper;

        private final ObjectReader _transferDataTypeReader;

        private final ThreadPoolExecutor _receiveSingleBlockingThreadExecutorService;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private <TRANSFER> MatsEagerCacheClientImpl(MatsFactory matsFactory, String dataName,
                Class<TRANSFER> transferDataType, Function<CacheReceivedData<TRANSFER>, DATA> fullUpdateMapper) {
            _matsFactory = matsFactory;
            _dataName = dataName;
            _fullUpdateMapper = (Function<CacheReceivedData<?>, DATA>) (Function) fullUpdateMapper;

            // :: Jackson JSON ObjectMapper
            ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

            // Make specific Writer for the "receivedDataType" - this is what we will need to deserialize from server.
            _transferDataTypeReader = mapper.readerFor(transferDataType);

            // Bare-bones assertion that we can deserialize the receivedDataType
            try {
                _transferDataTypeReader.readValue("{}");
            }
            catch (Throwable e) {
                throw new IllegalArgumentException("Could not deserialize a simple JSON '{}' to the receivedDataType ["
                        + transferDataType + "], which is the type that the server sends. This is a critical error,"
                        + " and the client cannot be created.", e);
            }

            // :: ExecutorService for handling the received data.
            _receiveSingleBlockingThreadExecutorService = _createSingleThreadedExecutorService("MatsEagerCacheClient-"
                    + _dataName + "-receiveExecutor");
        }

        private volatile Function<CacheReceivedPartialData<?, DATA>, DATA> _partialUpdateMapper;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        <TRANSFER> MatsEagerCacheClient<DATA> setPartialUpdateMapper(
                Function<CacheReceivedPartialData<TRANSFER, DATA>, DATA> partialUpdateMapper) {
            _partialUpdateMapper = (Function<CacheReceivedPartialData<?, DATA>, DATA>) (Function) partialUpdateMapper;
            return this;
        }

        // Singleton, inner NON-static class exposing "inner information" for health checks and monitoring.
        private final CacheClientInformationImpl _cacheClientInformation = new CacheClientInformationImpl();

        // ReadWriteLock to guard the cache content.
        // Not using "fair" mode: I don't envision that the cache will be read-hammered so hard that it will be a
        // problem - rather go for speed.
        // This will be taken in read mode for .get(), and write mode for updates.
        private final ReadWriteLock _cacheContentLock = new ReentrantReadWriteLock();
        private final Lock _cacheContentReadLock = _cacheContentLock.readLock();
        private final Lock _cacheContentWriteLock = _cacheContentLock.writeLock();

        // Latch to hold .get() calls for the initial population to be done.
        // Note: Also used as a fast-path indicator for get(), by being nulled.
        private volatile CountDownLatch _initialPopulationLatch = new CountDownLatch(1);

        // Tasks to run after initial population.
        // Note: Also used as a fast-path indicator for addOnInitialPopulationTask(), by being nulled.
        // Synchronized on this, but also volatile since we use it as fast-path indicator.
        private volatile List<Runnable> _afterInitialPopulationTasks = new ArrayList<>();

        // Listeners for cache updates
        private final CopyOnWriteArrayList<Consumer<CacheUpdated>> _cacheUpdatedListeners = new CopyOnWriteArrayList<>();

        private volatile int _sizeCutover = DEFAULT_SIZE_CUTOVER;

        private volatile long _cacheStartedTimestamp;
        private volatile long _initialPopulationRequestSentTimestamp;
        private volatile long _initialPopulationTimestamp;
        private volatile long _lastAnyUpdateReceivedTimestamp;
        private volatile long _lastFullUpdateReceivedTimestamp;
        private volatile long _lastPartialUpdateReceivedTimestamp;
        private volatile double _lastUpdateDurationMillis;

        private volatile int _lastUpdateCompressedSize;
        private volatile long _lastUpdateDecompressedSize;
        private volatile int _lastUpdateDataCount;
        private volatile String _lastUpdateMetadata;
        private volatile boolean _lastUpdateWasFull;
        private volatile boolean _lastUpdateWasLarge;

        private final AtomicInteger _numberOfFullUpdatesReceived = new AtomicInteger();
        private final AtomicInteger _numberOfPartialUpdatesReceived = new AtomicInteger();

        private final AtomicLong _accessCounter = new AtomicLong();

        // Synched/locked via the '_cacheContentLock'
        private DATA _data;

        private volatile CacheClientLifecycle _cacheClientLifecycle = CacheClientLifecycle.NOT_YET_STARTED;
        private volatile MatsEndpoint<?, ?> _broadcastTerminator;

        private volatile MatsEagerCacheServer _forwardingLinkedServer_ForDevelopment;

        private final CacheMonitor _cacheMonitor = new CacheMonitor(log2);

        private class CacheClientInformationImpl implements CacheClientInformation {
            @Override
            public String getDataName() {
                return _dataName;
            }

            @Override
            public String getNodename() {
                return _matsFactory.getFactoryConfig().getNodename();
            }

            @Override
            public CacheClientLifecycle getCacheClientLifeCycle() {
                return _cacheClientLifecycle;
            }

            @Override
            public String getBroadcastTopic() {
                return MatsEagerCacheServerImpl._getBroadcastTopic(_dataName);
            }

            @Override
            public boolean isInitialPopulationDone() {
                return _initialPopulationLatch == null;
            }

            @Override
            public long getCacheStartedTimestamp() {
                return _cacheStartedTimestamp;
            }

            @Override
            public long getInitialPopulationRequestSentTimestamp() {
                return _initialPopulationRequestSentTimestamp;
            }

            @Override
            public long getInitialPopulationTimestamp() {
                return _initialPopulationTimestamp;
            }

            @Override
            public long getAnyUpdateReceivedTimestamp() {
                return _lastAnyUpdateReceivedTimestamp;
            }

            @Override
            public long getLastFullUpdateReceivedTimestamp() {
                return _lastFullUpdateReceivedTimestamp;
            }

            @Override
            public long getLastPartialUpdateReceivedTimestamp() {
                return _lastPartialUpdateReceivedTimestamp;
            }

            @Override
            public double getLastUpdateDurationMillis() {
                return _lastUpdateDurationMillis;
            }

            @Override
            public long getLastUpdateCompressedSize() {
                return _lastUpdateCompressedSize;
            }

            @Override
            public long getLastUpdateDecompressedSize() {
                return _lastUpdateDecompressedSize;
            }

            @Override
            public int getLastUpdateDataCount() {
                return _lastUpdateDataCount;
            }

            @Override
            public String getLastUpdateMetadata() {
                return _lastUpdateMetadata;
            }

            @Override
            public boolean isLastUpdateFull() {
                return _lastUpdateWasFull;
            }

            @Override
            public boolean isLastUpdateLarge() {
                return _lastUpdateWasLarge;
            }

            @Override
            public int getNumberOfFullUpdatesReceived() {
                return _numberOfFullUpdatesReceived.get();
            }

            @Override
            public int getNumberOfPartialUpdatesReceived() {
                return _numberOfPartialUpdatesReceived.get();
            }

            @Override
            public long getNumberOfAccesses() {
                return _accessCounter.get();
            }

            @Override
            public List<LogEntry> getLogEntries() {
                return _cacheMonitor.getLogEntries();
            }

            @Override
            public List<ExceptionEntry> getExceptionEntries() {
                return _cacheMonitor.getExceptionEntries();
            }
        }

        /**
         * Add a runnable that will be invoked after the initial population is done. If the initial population is
         * already done, it will be invoked immediately by current thread. Note: Run ordering wrt. add ordering is not
         * guaranteed.
         *
         * @param runnable
         *            the runnable to invoke after the initial population is done (or immediately if already done).
         * @return this instance, for chaining.
         */
        @Override
        public MatsEagerCacheClient<DATA> addAfterInitialPopulationTask(Runnable runnable) {
            boolean runNow = true;
            // ?: Is the initial population list still present, that is, are we still in the initial population phase?
            if (_afterInitialPopulationTasks != null) {
                // -> Yes, still present, so we're still in the initial population phase.
                synchronized (this) {
                    // ?: Is the list still present?
                    if (_afterInitialPopulationTasks != null) {
                        // -> Yes, still present, so add the runnable to the list.
                        _afterInitialPopulationTasks.add(runnable);
                        // We don't run it now, as we're still in the initial population phase.
                        runNow = false;
                    }
                    // E-> The list was nulled out by the initial population thread, and the runnable was run.
                }
            }

            // ?: Should we run it now? That is, have initial population been done?
            if (runNow) {
                // -> Yes, initial population is done, so run it now.
                runnable.run();
            }
            // For chaining
            return this;
        }

        /**
         * Add a listener for cache updates. The listener will be invoked with the metadata about the cache update. The
         * listener is invoked after the cache content has been updated, and the cache content lock has been released.
         *
         * @param listener
         *            the listener to add.
         * @return this instance, for chaining.
         */
        @Override
        public MatsEagerCacheClient<DATA> addCacheUpdatedListener(Consumer<CacheUpdated> listener) {
            _cacheUpdatedListeners.add(listener);
            return this;
        }

        /**
         * Sets the size cutover for the cache: The size in bytes for the uncompressed JSON where the cache will
         * consider the update to be "large", and hence use a different scheme for handling the update. The default is
         * 15 MB.
         *
         * @param size
         *            the size hint.
         */
        @Override
        public void setSizeCutover(int size) {
            _sizeCutover = size;
        }

        /**
         * Returns the data - will block until the initial full population is done, and during subsequent repopulations.
         * <b>It is imperative that neither the data object itself, or any of its contained larger data or structures
         * (e.g. any Lists or Maps) aren't read out and stored in any object, or held onto by any threads for any
         * timespan above some few milliseconds, but rather queried anew from the cache for every needed access.</b>
         * This both to ensure timely update when new data arrives, but more importantly to avoid that memory isn't held
         * up by the old data structures being kept alive (this is particularly important for large caches).
         * <p>
         * It is legal for a thread to call this method before {@link #start()} is invoked, but then some other
         * (startup) thread must eventually invoke {@link #start()}, otherwise you'll have a deadlock.
         *
         * @return the cached data
         */
        @Override
        public DATA get() {
            // :: Handle initial population
            // ?: Do we need to wait for the initial population to be done (fast-path null check)? Read directly from
            // field.
            if (_initialPopulationLatch != null) {
                // -> The field was non-null.
                // Doing "double check" using local variable, to avoid race condition on nulling out the field.
                var localLatch = _initialPopulationLatch;
                // ?: Is the latch still non-null?
                if (localLatch != null) {
                    // -> Yes, so we have to wait on it.
                    // Note: We now use the local variable, so it won't be nulled out by the other thread.
                    // This also means that we can be raced by the initial population thread, but that is fine, since
                    // we'll then sail through the latch.await() immediately.
                    try {
                        localLatch.await();
                    }
                    catch (InterruptedException e) {
                        var msg = "Got interrupted while waiting for initial population to be done.";
                        _cacheMonitor.exception(MonitorCategory.GET, msg, e);
                        throw new RuntimeException(msg, e);
                    }
                }
            }
            // :: Get the data, within the read lock.
            _cacheContentReadLock.lock();
            try {
                if (_data == null) {
                    var msg = "The data is null, which the cache API contract explicitly forbids!"
                            + " Fix your cache update code!";
                    var up = new IllegalStateException(msg);
                    _cacheMonitor.exception(MonitorCategory.GET, msg, up);
                    throw up;
                }
                _accessCounter.incrementAndGet();
                return _data;
            }
            finally {
                _cacheContentReadLock.unlock();
            }
        }

        /**
         * Starts the cache client - startup is performed in a separate thread, so this method immediately returns - to
         * wait for initial population, use the {@link #addAfterInitialPopulationTask(Runnable) dedicated
         * functionality}. The cache client creates a SubscriptionTerminator for receiving cache updates based on the
         * dataName. Once we're sure this endpoint {@link MatsEndpoint#waitForReceiving(int) has entered the receive
         * loop}, a message to the server requesting update is sent. The {@link #get()} method will block until the
         * initial full population is received and processed. When the initial population is done, any
         * {@link #addAfterInitialPopulationTask(Runnable) onInitialPopulationTasks} will be invoked.
         *
         * @return this instance, for chaining.
         */
        @Override
        public MatsEagerCacheClient<DATA> start() {
            if (_cacheClientLifecycle != CacheClientLifecycle.NOT_YET_STARTED) {
                throw new IllegalStateException("The MatsEagerCacheClient for data [" + _dataName
                        + "] has already been started.");
            }
            _cacheStartedTimestamp = System.currentTimeMillis();

            // Set lifecycle to "starting", so that we can handle the initial population.
            _cacheClientLifecycle = CacheClientLifecycle.STARTING_AWAITING_INITIAL;

            // ?: Are we in development mode, where we're linking to a server in the same codebase?
            if (_forwardingLinkedServer_ForDevelopment != null) {
                // -> Yes, so we start with the server.
                _startWithServer(_forwardingLinkedServer_ForDevelopment);
                return this;
            }
            // E-> No, so we start with the SubscriptionTerminator.

            _cacheMonitor.log(INFO, MonitorCategory.CACHE_CLIENT, "Starting the MatsEagerCacheClient"
                    + " for data [" + _dataName + "].");

            // :: Listener to the update topic.
            _broadcastTerminator = _matsFactory.subscriptionTerminator(
                    MatsEagerCacheServerImpl._getBroadcastTopic(_dataName),
                    void.class, BroadcastDto.class, this::_processLambdaForSubscriptionTerminator);

            // :: Perform the initial cache update request in a separate thread, so that we can wait for the endpoint to
            // be ready, and then send the request. We return immediately.
            Thread thread = new Thread(() -> {
                // :: Wait for the receiving (Broadcast) endpoint to be ready
                // 10 minutes should really be enough for the service to finish boot and start the MatsFactory, right?
                boolean started = _broadcastTerminator.waitForReceiving(600_000);

                // ?: Did it start?
                if (!started) {
                    // -> No, so that's bad.
                    var msg = "The Update handling SubscriptionTerminator Endpoint would not start within 10 minutes.";
                    var up = new IllegalStateException(msg);
                    _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION, msg, up);
                    throw up;
                }
                _initialPopulationRequestSentTimestamp = System.currentTimeMillis();
                _sendUpdateRequest(CacheRequestDto.COMMAND_REQUEST_BOOT);
            });
            thread.setName("MatsEagerCacheClient-" + _dataName + "-initialCacheUpdateRequest");
            // If the JVM is shut down due to bad boot, this thread should not prevent it from exiting.
            thread.setDaemon(true);
            // Start it.
            thread.start();

            return this;
        }

        @Override
        public MatsEagerCacheClient<DATA> linkToServer(MatsEagerCacheServer forwardingLinkedServer) {
            _forwardingLinkedServer_ForDevelopment = forwardingLinkedServer;
            return this;
        }

        /**
         * If a user in some management GUI wants to force a full update, this method can be invoked. This will send a
         * request to the server to perform a full update, which will be broadcast to all clients.
         * <p>
         * <b>This must NOT be used to "poll" the server for updates on a schedule or similar</b>, as that is utterly
         * against the design of the Mats Eager Cache system. The Mats Eager Cache system is designed to be a "push"
         * system, where the server pushes updates to the clients when it has new data - or, as a backup, on a schedule.
         * But this <i>shall</i> be server handled, not client handled.
         */
        @Override
        public void requestFullUpdate() {
            _sendUpdateRequest(CacheRequestDto.COMMAND_REQUEST_MANUAL);
        }

        /**
         * Close down the cache client. This will stop and remove the SubscriptionTerminator for receiving cache
         * updates, and shut down the ExecutorService for handling the received data. Closing is idempotent; Multiple
         * invocations will not have any effect. It is not possible to restart the cache client after it has been
         * closed.
         */
        @Override
        public void close() {
            _cacheMonitor.log(INFO, MonitorCategory.CACHE_CLIENT, "Closing down the MatsEagerCacheClient"
                    + " for data [" + _dataName + "].");
            // Stop the executor anyway.
            _receiveSingleBlockingThreadExecutorService.shutdown();
            synchronized (this) {
                // ?: Are we running? Note: we accept multiple close() invocations, as the close-part is harder to
                // lifecycle
                // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
                if (!EnumSet.of(CacheClientLifecycle.RUNNING, CacheClientLifecycle.STARTING_AWAITING_INITIAL)
                        .contains(_cacheClientLifecycle)) {
                    // -> No, we're not running, so we don't do anything.
                    return;
                }
                _cacheClientLifecycle = CacheClientLifecycle.STOPPING;
            }
            // -> Yes, we're started, so close down.
            if (_broadcastTerminator != null) {
                _broadcastTerminator.remove(30_000);
            }
            synchronized (this) {
                _cacheClientLifecycle = CacheClientLifecycle.STOPPED;
            }
        }

        /**
         * Returns a "live view" of the cache client information, that is, you only need to invoke this method once to
         * get an instance that will always reflect the current state of the cache client.
         *
         * @return a "live view" of the cache client information.
         */
        public CacheClientInformation getCacheClientInformation() {
            return _cacheClientInformation;
        }

        // ======== Implementation / Internal methods ========

        public MatsEagerCacheClient<DATA> _startWithServer(MatsEagerCacheServer forwardingServer) {
            _cacheMonitor.log(INFO, MonitorCategory.CACHE_CLIENT, "Starting the Linked-to-Server"
                    + " MatsEagerCacheClient for data [" + _dataName + "].");

            // !! We do NOT start the SubscriptionTerminator, but rely on the server to forward the cache updates to us.
            // Link to server - this is immediate, not waiting for anything.
            ((MatsEagerCacheServerImpl) forwardingServer)._registerForwardToClient(this);

            // :: Perform the initial cache update request in a separate thread, so that we can wait for the endpoint to
            // be ready, and then send the request. We return immediately.
            Thread thread = new Thread(() -> {
                // :: Wait for the server to be ready.
                try {
                    ((MatsEagerCacheServerImpl) forwardingServer)._waitForReceiving(2 * 60);
                }
                catch (Throwable t) {
                    var msg = "The linked server would not start within 2 minutes.";
                    var up = new IllegalStateException(msg);
                    _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION, msg, up);
                    throw up;
                }

                _initialPopulationRequestSentTimestamp = System.currentTimeMillis();
                _sendUpdateRequest(CacheRequestDto.COMMAND_REQUEST_BOOT);
            });
            thread.setName("MatsEagerCacheClient-" + _dataName + "-initialCacheUpdateRequest-Linked-to-Server");
            // If the JVM is shut down due to bad boot, this thread should not prevent it from exiting.
            thread.setDaemon(true);
            // Start it.
            thread.start();

            return this;
        }

        void _processLambdaForSubscriptionTerminator(ProcessContext<?> ctx, Void state, BroadcastDto msg) {
            if (!(msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)
                    || msg.command.equals(BroadcastDto.COMMAND_UPDATE_PARTIAL))) {
                // None of my concern
                return;
            }

            _cacheMonitor.log(INFO, MonitorCategory.RECEIVED_UPDATE, "Received cache update for data [" + _dataName
                    + "], command [" + msg.command + "]: meta: [" + msg.metadata
                    + "], compressed: [" + _formatNiceBytes(msg.compressedSize)
                    + "], decompressed: [" + _formatNiceBytes(msg.uncompressedSize)
                    + "], dataCount: [" + msg.dataCount + "].");

            // ?: Check if we're running (or awaiting), so we don't accidentally process updates after close.
            if ((_cacheClientLifecycle != CacheClientLifecycle.RUNNING)
                    && (_cacheClientLifecycle != CacheClientLifecycle.STARTING_AWAITING_INITIAL)) {
                // -> We're not running or waiting for initial population, so we don't process the update.
                return;
            }

            final byte[] payload = ctx.getBytes(MatsEagerCacheServer.SIDELOAD_KEY_DATA_PAYLOAD);

            // :: Now perform hack to relieve the Mats thread, and do the actual work in a separate thread.
            // The rationale is to let the data structures underlying in the Mats system (e.g. the
            // representation of the incoming JMS Message) be GCable. The thread pool is special in that
            // it only accepts a single task, and if it is busy, it will block the submitting thread.
            // NOTE: We do NOT capture the context (which holds the MatsTrace and byte arrays) in the
            // Runnable,
            // as that would prevent the JMS Message from being GC'ed. We only capture the message DTO and
            // the
            // payload.
            _receiveSingleBlockingThreadExecutorService.submit(() -> {
                _handleUpdateInExecutorThread(msg, payload);
            });
        }

        /**
         * Single threaded, blocking {@link ExecutorService}. This is used to "distance" ourselves from Mats, so that
         * the large ProcessContext and the corresponding JMS Message can be GC'ed <i>while</i> we're decompressing and
         * deserializing the possibly large set of large objects: We've fetched the data we need from the JMS Message,
         * and now we're done with it, but we need to exit the Mats StageProcessor to let the Mats thread let go of the
         * ProcessContext and any JMS Message reference. However, we still want the data to be sequentially processed -
         * thus use a single-threaded executor with synchronous queue, and special rejection handler, to ensure that the
         * submitting works as follows: Either the task is immediately handed over to the single thread, or the
         * submitting thread (the Mats stage processor thread) is blocked - waiting out the current
         * decompress/deserializing/processing.
         */
        static ThreadPoolExecutor _createSingleThreadedExecutorService(String threadName) {
            return new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MINUTES,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread t = new Thread(runnable, threadName);
                        t.setDaemon(true);
                        return t;
                    },
                    (r, executor) -> { // Trick: For Rejections, we'll just block until able to enqueue the task.
                        try {
                            // Block until able to enqueue the task anyway.
                            executor.getQueue().put(r);
                        }
                        catch (InterruptedException e) {
                            // Remember, this is the *submitting* thread that is interrupted, not the pool thread.
                            Thread.currentThread().interrupt();
                            // We gotta get outta here.
                            throw new RejectedExecutionException("Interrupted while waiting to enqueue task", e);
                        }
                    });
        }

        private void _sendUpdateRequest(String command) {
            // :: Construct the request message
            CacheRequestDto req = new CacheRequestDto();
            req.nodename = _matsFactory.getFactoryConfig().getNodename();
            req.sentTimestamp = System.currentTimeMillis();
            req.sentNanoTime = System.nanoTime();
            req.command = command;

            String reason = command.equals(CacheRequestDto.COMMAND_REQUEST_BOOT)
                    ? "InitialCacheUpdateRequest"
                    : "ManualCacheUpdateRequest";

            MonitorCategory category = command.equals(CacheRequestDto.COMMAND_REQUEST_BOOT)
                    ? MonitorCategory.INITIAL_POPULATION
                    : MonitorCategory.REQUEST_UPDATE_FROM_CLIENT;

            _cacheMonitor.log(INFO, category, "Sending " + reason + " [" + command + "] to server on queue '"
                    + MatsEagerCacheServerImpl._getCacheRequestQueue(_dataName) + "'.");

            // :: Send it off
            try {
                _matsFactory.getDefaultInitiator().initiate(init -> init.traceId(
                        TraceId.create(_matsFactory.getFactoryConfig().getAppName(),
                                "MatsEagerCacheClient-" + _dataName, reason))
                        .from("MatsEagerCacheClient." + _dataName + ".UpdateRequest")
                        .to(MatsEagerCacheServerImpl._getCacheRequestQueue(_dataName))
                        .send(req));
            }
            catch (Throwable t) {
                var msg = "Got exception when initiating " + reason + ".";
                _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION, msg, t);
                throw new IllegalStateException(msg, t);
            }
        }

        private void _handleUpdateInExecutorThread(BroadcastDto msg, byte[] payload) {
            String threadTackOn;
            if (msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)) {
                int num = _numberOfFullUpdatesReceived.incrementAndGet();
                threadTackOn = "Full #" + num;
            }
            else {
                int num = _numberOfPartialUpdatesReceived.incrementAndGet();
                threadTackOn = "Partial #" + num;
            }
            String originalThreadName = Thread.currentThread().getName();
            Thread.currentThread().setName("MatsEagerCacheClient-" + _dataName + "-handleUpdateInThread-"
                    + threadTackOn);

            boolean fullUpdate = msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL);
            boolean largeUpdate = msg.uncompressedSize > _sizeCutover;

            // ## FIRST: Process and update the cache data.

            // Handle it.
            long nanosAsStart_update = System.nanoTime();
            boolean lockTaken = false;
            // ?: Is this a large update?
            if (largeUpdate) {
                // -> Yes, so then we'll write lock it and delete (if full update) the existing data *before* updating.
                _cacheContentWriteLock.lock();
                lockTaken = true;
            }
            try {
                int dataSize = msg.dataCount;
                String metadata = msg.metadata;

                // ?: Is this a full update?
                if (fullUpdate) {
                    // -> :: FULL UPDATE, so then we update the entire dataset

                    // ?: Is this a large update?
                    if (largeUpdate) {
                        // -> Yes, large update
                        // :: Null out the existing data *before* sleeping and updating, so that the GC can collect it.
                        _data = null;
                        // Sleep for a little while, both to let the Mats thread finish up and release the underlying MQ
                        // resources (e.g. JMS Message), and to let any threads that have acquired references to the
                        // data
                        // finish up and release them. E.g. if a thread has acquired a reference to e.g. a Map from
                        // DATA, and is iterating over it, it will be done within a few milliseconds, and then the
                        // thread will release the reference. If we're in a tight memory situation, the GC will then
                        // be able to collect the data structure, before we populate it anew.
                        //
                        // This sleep will affect all users of the cache, but a large bit of the premise is that the
                        // cache is not updated that often, so that this sleep won't be noticeable in the grand
                        // scheme of things.
                        try {
                            Thread.sleep(100);
                        }
                        catch (InterruptedException e) {
                            _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                                    "Got interrupted while sleeping before"
                                            + " acting on full update. Assuming shutdown, thus returning immediately.",
                                    e);
                            return;
                        }
                    }

                    // :: Perform the update
                    DATA newData = null;
                    try {
                        // Invoke the full update mapper
                        // (Note: we hold onto as little as possible while invoking the mapper, to let the GC do its
                        // job.)
                        newData = _fullUpdateMapper.apply(new CacheReceivedDataImpl<>(true, dataSize, metadata,
                                msg.uncompressedSize, msg.compressedSize, _getReceiveStreamFromPayload(payload)));
                    }
                    catch (Throwable e) {
                        _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                                "Got exception when invoking the full update mapper - this is VERY BAD!", e);
                        // Nothing to do. Everything is bad.
                        return;
                    }
                    // ?: Was this NOT a large update?
                    if (!largeUpdate) {
                        // -> Yes, this was NOT a large update, so then we must lock for update now.
                        _cacheContentWriteLock.lock();
                        lockTaken = true;
                    }
                    // ?: Did we get new data? (Anything else is a massive failure on the user's part!)
                    if (newData != null) {
                        // -> Yes, we got new data, so we update the data.
                        _data = newData;
                    }
                }
                else {
                    // -> :: PARTIAL UPDATE, so then we update the data "in place".

                    if (_data == null) {
                        _cacheMonitor.log(WARN, MonitorCategory.RECEIVED_UPDATE,
                                "We got a partial update without having any"
                                        + " data. This is probably due to the initial population not being done yet, or the"
                                        + " data being nulled out due to some error. Ignoring the partial update.");
                        return;
                    }

                    if (_partialUpdateMapper == null) {
                        // This is a severe error, as the user has not provided a partial update mapper, and we got a
                        // partial update.
                        var logMsg = "We got a partial update, but we don't have a partial update mapper. Ignoring the"
                                + " partial update.";
                        _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE, logMsg, new IllegalStateException(
                                logMsg));
                        return;
                    }

                    // NOTE: On a partial update, we DO NOT null out the data structure before updating, as we want to
                    // keep the data structure around for the partial update mapper to read out the structures from it.

                    // ?: Is this a large update?
                    if (largeUpdate) {
                        // -> Yes, large update
                        // Sleep for a pretty small while, to let the Mats thread finish up and release the JMS Message,
                        // so that it can be GCed. Note: We won't get the benefit of the GC on the data structures, as
                        // we'll need to provide the "current view" to the partial update mapper. (This is the reason
                        // why one shouldn't do partial updates if the part is a significant part of the data, but
                        // rather do full)
                        try {
                            Thread.sleep(25);
                        }
                        catch (InterruptedException e) {
                            _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                                    "Got interrupted while sleeping before"
                                            + " acting on partial update. Assuming shutdown, thus returning immediately.",
                                    e);
                            return;
                        }
                    }

                    // :: Perform the update
                    DATA newData = null;
                    try {
                        // Invoke the partial update mapper to get the new data.
                        // (Note: we hold onto as little as possible while invoking the mapper, to let the GC do its
                        // job.)
                        newData = _partialUpdateMapper.apply(new CacheReceivedPartialDataImpl<>(false, _data,
                                dataSize, metadata, _getReceiveStreamFromPayload(payload),
                                msg.uncompressedSize, msg.compressedSize));
                    }
                    catch (Throwable e) {
                        _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                                "Got exception when invoking the partial update"
                                        + " mapper - this is VERY BAD!", e);
                        // Nothing to do. Everything is bad.
                        return;
                    }
                    // ?: Was this NOT a large update?
                    if (!largeUpdate) {
                        // -> Yes, this was NOT a large update, so then we must lock for update now.
                        _cacheContentWriteLock.lock();
                        lockTaken = true;
                    }
                    // ?: Did we get new data? (Anything else is a massive failure on the user's part!)
                    if (newData != null) {
                        // -> Yes, we got new data, so we update the data.
                        _data = newData;
                    }
                }
            }
            finally {
                if (lockTaken) {
                    _cacheContentWriteLock.unlock();
                }
            }

            // NOTICE: We don't bother with skewed updates of stats here (i.e. missing sync), this is only for
            // introspection

            // Update the timestamp of the last update
            _lastAnyUpdateReceivedTimestamp = System.currentTimeMillis();
            if (fullUpdate) {
                _lastFullUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
            }
            else {
                _lastPartialUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
            }
            // .. and timing
            _lastUpdateDurationMillis = (System.nanoTime() - nanosAsStart_update) / 1_000_000d;
            // .. and the sizes
            _lastUpdateCompressedSize = msg.compressedSize;
            _lastUpdateDecompressedSize = msg.uncompressedSize;
            // .. and the count
            _lastUpdateDataCount = msg.dataCount;
            // .. and the metadata
            _lastUpdateMetadata = msg.metadata;
            // .. and the type of update
            _lastUpdateWasFull = fullUpdate;
            _lastUpdateWasLarge = largeUpdate;

            // :: Handle initial population obligations
            // NOTE: There is only one thread running a SubscriptionTerminator, and it is only us that
            // will write null to the _initialPopulationLatch and _onInitialPopulationTasks fields.
            // ?: Fast check if we've already done initial population obligations.
            if (_initialPopulationLatch != null) {
                // -> No, we haven't done initial population obligations yet.

                // ## SECOND: Release threads hanging on "get" waiting for initial population.

                // Release threads hanging on "get"
                _initialPopulationLatch.countDown();
                // Null out the reference to the latch (volatile field), since we use it for fast-path
                // evaluation in get() (and free the few bytes it occupies).
                _initialPopulationLatch = null;

                // Record the initial population timestamp
                _initialPopulationTimestamp = System.currentTimeMillis();

                // Set lifecycle to "running"
                _cacheClientLifecycle = CacheClientLifecycle.RUNNING;

                // ## THIRD: Run all the initial population runnables that have been added.

                /*
                 * Run all the runnables that have been added, waiting for this moment. (Typically adding and/or
                 * starting endpoints that depend on the cache being populated.) Note: Since we synch while accessing
                 * the list both here and in addOnInitialPopulationTask(), there can't be any ambiguous state: Either
                 * the task is present here and not yet run, or it is not present, and was then run in
                 * addOnInitialPopulationTask().
                 */

                // NOTE: It is nulled by us only, which hasn't happened yet, so it will be non-null now.
                List<Runnable> localAfterInitialPopulationTasks;
                synchronized (this) {
                    // Copy the field to local variable, which we'll run through outside the synchronized block.
                    localAfterInitialPopulationTasks = _afterInitialPopulationTasks;
                    // Null out the reference to the list (volatile field), since we in the .get() use it as
                    // evaluation of whether we're still in the initial population phase, or have passed it.
                    // NOTE: This is the only place the field is nulled, and it is nulled within synch on this.
                    _afterInitialPopulationTasks = null;
                }
                // Run all the runnables that have been added.
                for (Runnable afterInitialPopulationTask : localAfterInitialPopulationTasks) {
                    try {
                        afterInitialPopulationTask.run();
                    }
                    catch (Throwable t) {
                        _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION, "Got exception when running"
                                + " afterInitialPopulationTask [" + afterInitialPopulationTask
                                + "], ignoring but this is probably pretty bad.", t);
                    }
                }
            }

            // ## FOURTH, and final: Notify CacheUpdatedListeners

            // :: Notify listeners
            CacheUpdated cacheUpdated = new CacheUpdatedImpl(msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL),
                    msg.dataCount, msg.metadata, msg.uncompressedSize, msg.compressedSize, _lastUpdateDurationMillis);
            for (Consumer<CacheUpdated> listener : _cacheUpdatedListeners) {
                try {
                    listener.accept(cacheUpdated);
                }
                catch (Throwable t) {
                    _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                            "Got exception when notifying cacheUpdatedListener"
                                    + " [" + listener + "], ignoring but this is probably pretty bad.", t);
                }
            }

            Thread.currentThread().setName(originalThreadName);
        }

        private Stream<?> _getReceiveStreamFromPayload(byte[] payload) throws IOException {
            InflaterInputStreamWithStats iis = new InflaterInputStreamWithStats(payload);
            MappingIterator<?> mappingIterator = _transferDataTypeReader.readValues(iis);
            // Convert iterator to stream
            return Stream.iterate(mappingIterator, MappingIterator::hasNext,
                    UnaryOperator.identity()).map(MappingIterator::next);
        }

        private static class CacheReceivedDataImpl<TRANSFER> implements CacheReceivedData<TRANSFER> {
            protected final boolean _fullUpdate;
            protected final int _dataCount;
            protected final String _metadata;
            protected final long _receivedUncompressedSize;
            protected final long _receivedCompressedSize;

            private final Stream<TRANSFER> _rStream;

            public CacheReceivedDataImpl(boolean fullUpdate, int dataCount, String metadata,
                    long receivedUncompressedSize,
                    long receivedCompressedSize, Stream<TRANSFER> recvStream) {
                _fullUpdate = fullUpdate;
                _dataCount = dataCount;
                _metadata = metadata;
                _receivedUncompressedSize = receivedUncompressedSize;
                _receivedCompressedSize = receivedCompressedSize;

                _rStream = recvStream;
            }

            @Override
            public boolean isFullUpdate() {
                return _fullUpdate;
            }

            @Override
            public int getDataCount() {
                return _dataCount;
            }

            @Override
            public long getUncompressedSize() {
                return _receivedUncompressedSize;
            }

            @Override
            public long getCompressedSize() {
                return _receivedCompressedSize;
            }

            @Override
            public String getMetadata() {
                return _metadata;
            }

            @Override
            public Stream<TRANSFER> getReceivedDataStream() {
                return _rStream;
            }

            /**
             * toString method showing all properties, except the data stream.
             */
            @Override
            public String toString() {
                return "CacheReceivedData[" + (_fullUpdate ? "FULL" : "PARTIAL") + ",count=" + _dataCount
                        + ",meta=" + _metadata + ",uncompr=" + _formatBytes(_receivedUncompressedSize)
                        + ",compr=" + _formatBytes(_receivedCompressedSize) + "]";
            }
        }

        static class CacheUpdatedImpl extends CacheReceivedDataImpl<Void> implements CacheUpdated {
            private final double _updateDurationMillis;

            public CacheUpdatedImpl(boolean fullUpdate, int dataSize, String metadata,
                    long receivedUncompressedSize, long receivedCompressedSize, double updateDurationMillis) {
                super(fullUpdate, dataSize, metadata, receivedUncompressedSize, receivedCompressedSize, null);
                _updateDurationMillis = updateDurationMillis;
            }

            @Override
            public double getUpdateDurationMillis() {
                return _updateDurationMillis;
            }

            /**
             * toString method showing all properties, except the data stream.
             */
            @Override
            public String toString() {
                return "CacheUpdatedData[" + (_fullUpdate ? "FULL" : "PARTIAL") + ",count=" + _dataCount
                        + ",meta=" + _metadata + ",uncompr=" + _formatBytes(_receivedUncompressedSize)
                        + ",compr=" + _formatBytes(_receivedCompressedSize) + ", update:" + _formatMillis(
                                _updateDurationMillis) + "]";
            }
        }

        private static class CacheReceivedPartialDataImpl<TRANSFER, DATA> extends CacheReceivedDataImpl<TRANSFER>
                implements CacheReceivedPartialData<TRANSFER, DATA> {
            private final DATA _data;

            public CacheReceivedPartialDataImpl(boolean fullUpdate, DATA data, int dataSize, String metadata,
                    Stream<TRANSFER> rStream,
                    long receivedUncompressedSize, long receivedCompressedSize) {
                super(fullUpdate, dataSize, metadata, receivedUncompressedSize, receivedCompressedSize, rStream);
                _data = data;
            }

            @Override
            public DATA getPreviousData() {
                return _data;
            }

            /**
             * toString method showing all properties, except the data stream.
             */
            @Override
            public String toString() {
                return "CacheReceivedPartialData[" + (_fullUpdate ? "FULL" : "PARTIAL") + ",count=" + _dataCount
                        + ",meta=" + _metadata + ",uncompr=" + _formatBytes(_receivedUncompressedSize)
                        + ",compr=" + _formatBytes(_receivedCompressedSize) + ", prevData=" + _data + "]";
            }
        }
    }

    // ======== The 'MatsEagerCacheClientMock' implementation class

    class MatsEagerCacheClientMockImpl<DATA> implements MatsEagerCacheClientMock<DATA> {
        private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheClientMockImpl.class);

        private final String _dataName;

        MatsEagerCacheClientMockImpl(String dataName) {
            _dataName = dataName;
        }

        private volatile long _startedTimestamp;

        private volatile long _fullUpdateTimestamp;

        private volatile CacheClientLifecycle _cacheClientLifecycle = CacheClientLifecycle.NOT_YET_STARTED;

        private volatile DATA _mockData;
        private volatile Supplier<DATA> _mockDataSupplier;
        private volatile Supplier<CacheUpdated> _mockCacheUpdatedSupplier;
        private final AtomicInteger _numberOfFullUpdatesReceived = new AtomicInteger();
        private final AtomicLong _accessCounter = new AtomicLong();

        // Listeners for cache updates
        private final CopyOnWriteArrayList<Consumer<CacheUpdated>> _cacheUpdatedListeners = new CopyOnWriteArrayList<>();

        // Latch to hold .get() calls for the initial population to be done.
        private final CountDownLatch _initialPopulationLatch = new CountDownLatch(1);

        // Tasks to run after initial population.
        // Note: Also used as a fast-path indicator for addOnInitialPopulationTask(), by being nulled.
        // Synchronized on this, but also volatile since we use it as fast-path indicator.
        private volatile List<Runnable> _onInitialPopulationTasks = new ArrayList<>();

        private final CacheMonitor _cacheMonitor = new CacheMonitor(log);

        @Override
        public MatsEagerCacheClientMock<DATA> setMockData(DATA data) {
            _mockData = data;
            _mockDataSupplier = null;
            _numberOfFullUpdatesReceived.incrementAndGet();
            return this;
        }

        @Override
        public MatsEagerCacheClientMock<DATA> setMockDataSupplier(Supplier<DATA> dataSupplier) {
            _mockData = null;
            _mockDataSupplier = dataSupplier;
            _numberOfFullUpdatesReceived.incrementAndGet();
            return this;
        }

        @Override
        public MatsEagerCacheClientMock<DATA> setMockCacheUpdatedSupplier(Supplier<CacheUpdated> cacheUpdatedSupplier) {
            _mockCacheUpdatedSupplier = cacheUpdatedSupplier;
            return this;
        }

        @Override
        public MatsEagerCacheClientMock<DATA> addAfterInitialPopulationTask(Runnable runnable) {
            boolean runNow = false;
            synchronized (this) {
                if (_onInitialPopulationTasks == null) {
                    // -> We're past the initial population phase, so run it now.
                    runNow = true;
                }
                else {
                    // -> We're still in the initial population phase, so add it to the list.
                    _onInitialPopulationTasks.add(runnable);
                }
            }
            if (runNow) {
                runnable.run();
            }
            return this;
        }

        @Override
        public MatsEagerCacheClientMock<DATA> addCacheUpdatedListener(Consumer<CacheUpdated> listener) {
            _cacheUpdatedListeners.add(listener);
            return this;
        }

        @Override
        public void setSizeCutover(int size) {
            /* no-op in mock */
        }

        @Override
        public DATA get() {
            try {
                boolean await = _initialPopulationLatch.await(1, TimeUnit.MINUTES);
                if (!await) {
                    throw new IllegalStateException("Initial population did not complete within 1 minute.");
                }
            }
            catch (InterruptedException e) {
                throw new RuntimeException("Interrupted", e);
            }
            if (_mockData != null) {
                _accessCounter.incrementAndGet();
                return _mockData;
            }
            if (_mockDataSupplier != null) {
                _accessCounter.incrementAndGet();
                return _mockDataSupplier.get();
            }
            throw new IllegalStateException("No mock data set - use setMockData(..) or setMockDataSupplier(..).");
        }

        @Override
        public MatsEagerCacheClientMock<DATA> start() {
            if (_cacheClientLifecycle != CacheClientLifecycle.NOT_YET_STARTED) {
                throw new IllegalStateException("The MatsEagerCacheClient MOCK for data [" + _dataName
                        + "] has already been started.");
            }

            _cacheMonitor.log(INFO, MonitorCategory.CACHE_CLIENT_MOCK, "Starting the"
                    + " MatsEagerCacheClientMock for data [" + _dataName + "].");

            _cacheClientLifecycle = CacheClientLifecycle.STARTING_AWAITING_INITIAL;
            _startedTimestamp = System.currentTimeMillis();

            // Emulate the initial population being done.
            Thread mockInitial = new Thread(() -> {
                try {
                    Thread.sleep(MILLIS_LAG);
                }
                catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted", e);
                }

                _initialPopulationLatch.countDown();

                _cacheClientLifecycle = CacheClientLifecycle.RUNNING;

                _fullUpdateTimestamp = System.currentTimeMillis();

                // :: Run all the initial population runnables that have been added.
                List<Runnable> tasksToRun;
                synchronized (this) {
                    tasksToRun = new ArrayList<>(_onInitialPopulationTasks);
                    _onInitialPopulationTasks = null;
                }
                for (Runnable runnable : tasksToRun) {
                    runnable.run();
                }
                // :: Notify listeners, as with requestFullUpdate()
                notifyListeners();
            }, "MatsEagerCacheClientMock-" + _dataName + "-initialPopulation");
            mockInitial.setDaemon(true);
            mockInitial.start();

            return this;
        }

        @Override
        public void requestFullUpdate() {
            // :: Emulate that we're requesting a full update, and then getting a reply.

            // ?: Assert that we have data
            if ((_mockData == null) && (_mockDataSupplier == null)) {
                throw new IllegalStateException("No mock data set - use setMockData(..) or setMockDataSupplier(..).");
            }

            // :: "We've gotten data", so we'll now notify the listeners.
            new Thread(() -> {
                try {
                    Thread.sleep(MILLIS_LAG);
                }
                catch (InterruptedException e) {
                    throw new RuntimeException("Interrupted", e);
                }
                _numberOfFullUpdatesReceived.incrementAndGet();
                // Do notification, as with start()
                notifyListeners();
            }, "MatsEagerCacheClientMock-" + _dataName + "-requestFullUpdate_notifyCacheUpdateListeners").start();
        }

        private void notifyListeners() {
            _fullUpdateTimestamp = System.currentTimeMillis();
            CacheUpdated cacheUpdated;
            cacheUpdated = _createMockCacheUpdated();
            for (Consumer<CacheUpdated> listener : _cacheUpdatedListeners) {
                try {
                    listener.accept(cacheUpdated);
                }
                catch (Exception e) {
                    _cacheMonitor.exception(MonitorCategory.RECEIVED_UPDATE,
                            "Got exception when notifying cacheUpdatedListener [" + listener
                                    + "], ignoring but this is probably pretty bad.", e);
                }
            }
        }

        private CacheUpdated _createMockCacheUpdated() {
            CacheUpdated cacheUpdated;
            cacheUpdated = _mockCacheUpdatedSupplier != null
                    ? _mockCacheUpdatedSupplier.get()
                    : new CacheUpdatedImpl(true, 42, null, 1337, 42, Math.PI);
            return cacheUpdated;
        }

        @Override
        public void close() {
            _cacheClientLifecycle = CacheClientLifecycle.STOPPED;
        }

        @Override
        public CacheClientInformation getCacheClientInformation() {
            return new CacheClientInformation() {

                @Override
                public String getDataName() {
                    return _dataName;
                }

                @Override
                public String getNodename() {
                    return _dataName + "-MockNode";
                }

                @Override
                public CacheClientLifecycle getCacheClientLifeCycle() {
                    return _cacheClientLifecycle;
                }

                @Override
                public String getBroadcastTopic() {
                    return MatsEagerCacheServerImpl._getBroadcastTopic(_dataName);
                }

                @Override
                public boolean isInitialPopulationDone() {
                    return _initialPopulationLatch.getCount() == 0;
                }

                @Override
                public long getCacheStartedTimestamp() {
                    return _startedTimestamp;
                }

                @Override
                public long getInitialPopulationRequestSentTimestamp() {
                    return _startedTimestamp;
                }

                @Override
                public long getInitialPopulationTimestamp() {
                    return _fullUpdateTimestamp;
                }

                @Override
                public long getAnyUpdateReceivedTimestamp() {
                    return _fullUpdateTimestamp;
                }

                @Override
                public long getLastFullUpdateReceivedTimestamp() {
                    return _fullUpdateTimestamp;
                }

                @Override
                public long getLastPartialUpdateReceivedTimestamp() {
                    return -1;
                }

                @Override
                public double getLastUpdateDurationMillis() {
                    return _createMockCacheUpdated().getUpdateDurationMillis();
                }

                @Override
                public long getLastUpdateCompressedSize() {
                    return _createMockCacheUpdated().getCompressedSize();
                }

                @Override
                public long getLastUpdateDecompressedSize() {
                    return _createMockCacheUpdated().getUncompressedSize();
                }

                @Override
                public int getLastUpdateDataCount() {
                    return _createMockCacheUpdated().getDataCount();
                }

                @Override
                public String getLastUpdateMetadata() {
                    return "MOCK";
                }

                @Override
                public boolean isLastUpdateFull() {
                    return true;
                }

                @Override
                public boolean isLastUpdateLarge() {
                    return false;
                }

                @Override
                public int getNumberOfFullUpdatesReceived() {
                    return _numberOfFullUpdatesReceived.get();
                }

                @Override
                public int getNumberOfPartialUpdatesReceived() {
                    return 0;
                }

                @Override
                public long getNumberOfAccesses() {
                    return _accessCounter.get();
                }

                @Override
                public List<LogEntry> getLogEntries() {
                    return _cacheMonitor.getLogEntries();
                }

                @Override
                public List<ExceptionEntry> getExceptionEntries() {
                    return _cacheMonitor.getExceptionEntries();
                }
            };
        }
    }
}
