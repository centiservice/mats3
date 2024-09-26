package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.TraceId;
import io.mats3.util.compression.InflaterInputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheServer.BroadcastDto;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheRequestDto;

/**
 * The client for the Mats Eager Cache system. This client will connect to a Mats Eager Cache server, and receive data
 * from it. The client will block {@link #get()}-invocations until the initial full population is done, and during
 * subsequent repopulations.
 * <p>
 * Thread-safety: This class is thread-safe after construction and {@link #start()} has been invoked, specifically
 * {@link #get()} is meant to be invoked from multiple threads.
 * 
 * @param <DATA>
 *            the type of the data structures object that shall be returned.
 * 
 * @author Endre Stølsvik 2024-09-03 19:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsEagerCacheClient<DATA> {
    private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheClient.class);

    private final MatsFactory _matsFactory;
    private final String _dataName;
    private final Function<CacheReceivedData<?>, DATA> _fullUpdateMapper;
    private final Function<CacheReceivedPartialData<?, ?>, DATA> _partialUpdateMapper;

    private final ObjectReader _receivedDataTypeReader;

    private final ThreadPoolExecutor _receiveSingleBlockingThreadExecutorService;

    /**
     * Constructor for the Mats Eager Cache client. The client will connect to a Mats Eager Cache server, and receive
     * data from it. The client will block {@link #get()}-invocations until the initial full population is done, and
     * during subsequent repopulations.
     * 
     * @param matsFactory
     *            the MatsFactory to use for the Mats Eager Cache client.
     * @param dataName
     *            the name of the data that the client will receive.
     * @param receivedDataType
     *            the type of the received data.
     * @param fullUpdateMapper
     *            the function that will be invoked when a full update is received from the server. It is illegal to
     *            return null from this function, and if the update throws an exception, the cache will be left in a
     *            nulled state.
     * @param partialUpdateMapper
     *            the function that will be invoked when a partial update is received from the server. It is illegal to
     *            return null from this function, and if the update throws an exception, the cache will be left in a
     *            nulled state.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <RECV> MatsEagerCacheClient(MatsFactory matsFactory, String dataName, Class<RECV> receivedDataType,
            Function<CacheReceivedData<RECV>, DATA> fullUpdateMapper,
            Function<CacheReceivedPartialData<RECV, DATA>, DATA> partialUpdateMapper) {
        _matsFactory = matsFactory;
        _dataName = dataName;
        _fullUpdateMapper = (Function<CacheReceivedData<?>, DATA>) (Function) fullUpdateMapper;
        _partialUpdateMapper = (Function<CacheReceivedPartialData<?, ?>, DATA>) (Function) partialUpdateMapper;

        // :: Jackson JSON ObjectMapper
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Make specific Writer for the "receivedDataType" - this is what we will need to deserialize from server.
        _receivedDataTypeReader = mapper.readerFor(receivedDataType);

        // :: ExecutorService for handling the received data.
        _receiveSingleBlockingThreadExecutorService = _createSingleThreadedExecutorService("MatsEagerCacheClient-"
                + _dataName + "-receiveExecutor");
    }

    // ReadWriteLock to guard the cache content.
    // Not using "fair" mode: I don't envision that the cache will be read-hammered so hard that it will be a problem.
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
    private volatile List<Runnable> _onInitialPopulationTasks = new ArrayList<>();

    // Listeners for cache updates
    private final CopyOnWriteArrayList<Consumer<CacheUpdated>> _cacheUpdatedListeners = new CopyOnWriteArrayList<>();

    private final AtomicInteger _updateRequestCounter = new AtomicInteger();

    private DATA _data;

    private volatile boolean _running;
    private volatile MatsEndpoint<?, ?> _broadcastTerminator;

    /**
     * Metadata about the cache update.
     */
    public interface CacheReceived {
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
    public interface CacheUpdated extends CacheReceived {
        double getUpdateDurationMillis();
    }

    /**
     * Object that is provided to the 'fullUpdateMapper' {@link Function} which was provided to the constructor of
     * {@link MatsEagerCacheClient}. This object contains the data that was received from the server, and the metadata
     * that was sent along with the data.
     *
     * @param <RECV>
     *            the type of the received data.
     */
    public interface CacheReceivedData<RECV> extends CacheReceived {
        /**
         * @return the received data as a Stream.
         */
        Stream<RECV> getReceivedDataStream();
    }

    /**
     * Object that is provided to the 'partialUpdateMapper' {@link Function} which was provided to the constructor of
     * {@link MatsEagerCacheClient}. This object contains the data that was received from the server, and the metadata
     * that was sent along with the data - as well as the previous 'D' data structures, whose structures (e.g. Lists,
     * Sets or Maps) should be read out and cloned/copied, and the copies updated with the new data before returning the
     * newly created 'D' data structures object.
     * <p>
     * <b>it is important that the existing structures (e.g. Lists or Maps of cached entities) aren't updated in-place
     * (as they can potentially simultaneously be accessed by other threads), but rather that each of the structures
     * (e.g. Lists or Maps of entities) are cloned or copied, and then the new data is overwritten in this copy.</b>
     *
     * @param <RECV>
     *            the type of the received data.
     * @param <DATA>
     *            the type of the data structures object that shall be returned.
     */
    public interface CacheReceivedPartialData<RECV, DATA> extends CacheReceivedData<RECV> {
        /**
         * Returns the previous 'D' data structures, whose structures (e.g. Lists, Sets or Maps of entities) should be
         * read out and cloned/copied, and the copies updated with the new data before returning the newly created 'D'
         * data structures object. <b>This means: No in-place updating on the existing structures!</b>
         *
         * @return the previous data structure.
         */
        DATA getPreviousData();
    }

    /**
     * Add a runnable that will be invoked after the initial population is done. If the initial population is already
     * done, it will be invoked immediately by current thread. Note: Run ordering wrt. add ordering is not guaranteed.
     * 
     * @param runnable
     *            the runnable to invoke after the initial population is done.
     * @return this instance, for chaining.
     */
    public MatsEagerCacheClient<DATA> addAfterInitialPopulationTask(Runnable runnable) {
        boolean runNow = true;
        // ?: Is the initial population list still present, that is, are we still in the initial population phase?
        if (_onInitialPopulationTasks != null) {
            // -> Yes, still present, so we're still in the initial population phase.
            synchronized (this) {
                // ?: Is the list still present?
                if (_onInitialPopulationTasks != null) {
                    // -> Yes, still present, so add the runnable to the list.
                    _onInitialPopulationTasks.add(runnable);
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

    public MatsEagerCacheClient<DATA> addCacheUpdatedListener(Consumer<CacheUpdated> listener) {
        _cacheUpdatedListeners.add(listener);
        return this;
    }

    /**
     * Get the data. Will block until the initial full population is done, and during subsequent repopulations. <b>It is
     * imperative that the data structures in the "D" datatype (e.g. any Lists or Maps) aren't read out and stored in
     * any object, or held onto by threads for any timespan above some few milliseconds, but rather queried anew from
     * the cache for every needed access.</b> This both to ensure timely update when new data arrives, and to avoid that
     * memory isn't held up by the old data structures being kept alive.
     * <p>
     * It is legal to call this method before {@link #start()} is invoked, but initial population won't be done until
     * the client is started.
     *
     * @return the data
     */
    public DATA get() {
        // :: Handle initial population
        // ?: Do we need to wait for the initial population to be done (fast-path null check)? Read directly from field.
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
                    // TODO: Log exception to monitor and HealthCheck.
                    throw new RuntimeException("Got interrupted while waiting for initial population to be done.", e);
                }
            }
        }
        // :: Get the data, within the read lock.
        _cacheContentReadLock.lock();
        try {
            if (_data == null) {
                // TODO: Log exception to monitor and HealthCheck - OR, rather, on the setter side.
                throw new IllegalStateException("The data is null, which the cache API contract explicitly forbids."
                        + " Fix your cache update code!");
            }
            return _data;
        }
        finally {
            _cacheContentReadLock.unlock();
        }
    }

    /**
     * Immediately start the cache client - startup is performed in a separate thread, so this method immediately
     * returns - to wait for initial population, use the {@link #addAfterInitialPopulationTask(Runnable) dedicated
     * functionality}. The cache client creates a SubscriptionTerminator for receiving cache updates based on the
     * dataName. Once we're sure this endpoint {@link MatsEndpoint#waitForReceiving(int) has entered the receive loop},
     * a message to the server requesting update is performed. The {@link #get()} method will block until the initial
     * full population is received and processed. When the initial population is done, any
     * {@link #addAfterInitialPopulationTask(Runnable) onInitialPopulationTasks} will be invoked.
     * 
     * @return this instance, for chaining.
     */
    public MatsEagerCacheClient<DATA> start() {
        return _start();
    }

    public void requestFullUpdate() {
        _sendUpdateRequest(CacheRequestDto.COMMAND_REQUEST_MANUAL);
    }

    public void close() {
        synchronized (this) {
            // ?: Are we running? Note: we accept multiple close() invocations, as the close-part is harder to lifecycle
            // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
            if (_running) {
                // -> Yes, we're started, so close down.
                _broadcastTerminator.stop(30_000);
                _receiveSingleBlockingThreadExecutorService.shutdown();
                _running = false;
            }
        }
    }

    // ======== Implementation / Internal methods ========

    private MatsEagerCacheClient<DATA> _start() {
        // :: Listener to the update topic.
        _broadcastTerminator = _matsFactory.subscriptionTerminator(MatsEagerCacheServer.getBroadcastTopic(_dataName),
                void.class, BroadcastDto.class, (ctx, state, msg) -> {
                    if (!(msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)
                            || msg.command.equals(BroadcastDto.COMMAND_UPDATE_PARTIAL))) {
                        // None of my concern
                        return;
                    }

                    final byte[] payload = ctx.getBytes(MatsEagerCacheServer.MATS_EAGER_CACHE_PAYLOAD);

                    // :: Now perform hack to relieve the Mats thread, and do the actual work in a separate thread.
                    // The rationale is to let the data structures underlying in the Mats system (e.g. the
                    // representation of the incoming JMS Message) be GCable. The thread pool is special in that
                    // it only accepts a single task, and if it is busy, it will block the submitting thread.
                    // NOTE: We do NOT capture the context (which holds the MatsTrace and byte arrays) in the Runnable,
                    // as that would prevent the JMS Message from being GC'ed. We only capture the message DTO and the
                    // payload.
                    _receiveSingleBlockingThreadExecutorService.submit(() -> {
                        _handleUpdateInExecutorThread(msg, payload);
                    });
                });

        // :: Perform the initial cache update request in a separate thread, so that we can wait for the endpoint to be
        // ready, and then send the request. We return immediately.
        Thread thread = new Thread(() -> {
            // :: Wait for the receiving (Broadcast) endpoint to be ready
            // 10 minutes should really be enough for the service to finish boot and start the MatsFactory, right?
            boolean started = _broadcastTerminator.waitForReceiving(600_000);

            // ?: Did it start?
            if (!started) {
                // -> No, so that's bad.
                String msg = "The Update handler SubscriptionTerminator Endpoint would not start within 10 minutes.";
                log.error(msg);
                // TODO: Log exception to monitor and HealthCheck.
                throw new IllegalStateException(msg);
            }

            _sendUpdateRequest(CacheRequestDto.COMMAND_REQUEST_BOOT);
        });
        thread.setName("MatsEagerCacheClient-" + _dataName + "-initialCacheUpdateRequest");
        // If the JVM is shut down due to bad boot, this thread should not prevent it from exiting.
        thread.setDaemon(true);
        // Start it.
        thread.start();

        // Set running flag
        _running = true;

        return this;
    }

    /**
     * Single threaded, blocking {@link ExecutorService}. This is used to "distance" ourselves from Mats, so that the
     * large JMS Message can be GC'ed <i>while</i> we're decompressing and deserializing the possibly large set of large
     * objects: We've fetched the data we need from the JMS Message, and now we're done with it, but we need to exit the
     * Mats StageProcessor to let the Mats thread let go of the JMS Message reference. However, we still want the data
     * to be sequentially processed - thus use a single-threaded executor with synchronous queue, and special rejection
     * handler, to ensure that the submitting works as follows: Either the task is immediately handed over to the single
     * thread, or the submitting thread (the Mats stage processor thread) is blocked - waiting out the current
     * deserializing.
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
        // Construct the message
        CacheRequestDto req = new CacheRequestDto();
        req.nodename = _matsFactory.getFactoryConfig().getNodename();
        req.sentTimestamp = System.currentTimeMillis();
        req.sentNanoTime = System.nanoTime();
        req.command = command;

        // Send it off
        try {
            String reason = command.equals(CacheRequestDto.COMMAND_REQUEST_BOOT)
                    ? "initialCacheUpdateRequest"
                    : "manualCacheUpdateRequest";
            _matsFactory.getDefaultInitiator().initiate(init -> init.traceId(
                    TraceId.create(_matsFactory.getFactoryConfig().getAppName(),
                            "MatsEagerCacheClient-" + _dataName, reason))
                    .from("MatsEagerCacheClient." + _dataName + "." + reason)
                    .to(MatsEagerCacheServer.getCacheRequestQueue(_dataName))
                    .send(req));
        }
        catch (Exception e) {
            // TODO: Log exception to monitor and HealthCheck.
            String msg = "Got exception when initiating the initial cache update request.";
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    private void _handleUpdateInExecutorThread(BroadcastDto msg, byte[] payload) {
        String originalThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName("MatsEagerCacheClient-" + _dataName + "-handle"
                + (msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL) ? "Full" : "Partial")
                + "UpdateInThread-#" + _updateRequestCounter.getAndIncrement());

        // Write lock
        _cacheContentWriteLock.lock();
        // Handle it.
        long nanosAsStart_update = System.nanoTime();
        try {
            int dataSize = msg.dataCount;
            String metadata = msg.metadata;

            // ?: Is this a full update?
            if (msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)) {
                // -> Yes, full update, so then we clear out the data, and produce new.

                // Null out our reference of the data, so that the GC can collect it.
                _data = null;

                // Sleep for a small while, both to let the Mats thread finish up and release the underlying MQ
                // resources (e.g. JMS Message), and to let any threads that have acquired references to the data
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
                    // TODO: Log exception to monitor and HealthCheck.
                    log.info("Was interrupted while sleeping before acting on full update. Assuming"
                            + " shutdown, thus returning immediately.");
                    return;
                }

                // Perform the update
                try {
                    // Invoke the full update mapper
                    // (Note: we hold onto as little as possible while invoking the mapper, to let the GC do its job.)
                    _data = _fullUpdateMapper.apply(new CacheReceivedDataImpl<>(true, dataSize, metadata,
                            _getReceiveStreamFromPayload(payload), msg.uncompressedSize, msg.compressedSize));
                }
                catch (Throwable e) {
                    // TODO: Log exception to monitor and HealthCheck.
                }
            }
            // ?: Is this a partial update?
            else if (msg.command.equals(BroadcastDto.COMMAND_UPDATE_PARTIAL)) {
                // -> Yes, partial update, so then we update the data "in place".

                if (_data == null) {
                    // TODO: Log exception to monitor and HealthCheck?
                    log.warn("We got a partial update without having any data. This is probably due"
                            + " to the initial population not being done yet, or the data being nulled out"
                            + " due to some error. Ignoring the partial update.");
                    return;
                }

                if (_partialUpdateMapper == null) {
                    // TODO: Log exception to monitor and HealthCheck.
                    log.error("We got a partial update, but we don't have a partial update mapper."
                            + " Ignoring the partial update.");
                    return;
                }

                // Sleep for a pretty small while, to let the Mats thread finish up and release the JMS Message, so
                // that it can be GCed. Note: We won't get the benefit of the GC on the data structures, as we'll
                // need to provide the "current view" to the partial update mapper. (This is the reason why one
                // shouldn't do partial updates if the part is a significant part of the data, but rather do full)
                try {
                    Thread.sleep(25);
                }
                catch (InterruptedException e) {
                    // TODO: Log exception to monitor and HealthCheck.
                    log.info("Was interrupted while sleeping before acting on partial update. Assuming"
                            + " shutdown, thus returning immediately.");
                    return;
                }

                // Perform the update
                try {
                    // Invoke the partial update mapper to get the new data.
                    // (Note: we hold onto as little as possible while invoking the mapper, to let the GC do its job.)
                    _data = _partialUpdateMapper.apply(new CacheReceivedPartialDataImpl<>(false, _data,
                            dataSize, metadata,
                            _getReceiveStreamFromPayload(payload),
                            msg.uncompressedSize, msg.compressedSize));
                }
                catch (Throwable e) {
                    // TODO: Log exception to monitor and HealthCheck.
                }
            }
        }
        finally {
            _cacheContentWriteLock.unlock();
        }
        double millisTaken_update = (System.nanoTime() - nanosAsStart_update) / 1_000_000d;

        // FIRST: Notify CacheUpdatedListeners

        // :: Notify listeners
        CacheUpdated cacheUpdated = new CacheUpdatedImpl(msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL),
                msg.dataCount, msg.metadata, msg.uncompressedSize, msg.compressedSize, millisTaken_update);
        for (Consumer<CacheUpdated> listener : _cacheUpdatedListeners) {
            try {
                listener.accept(cacheUpdated);
            }
            catch (Exception e) {
                // TODO: Handle exception.
                log.error("Got exception when notifying cacheUpdatedListener [" + listener
                        + "], ignoring but this is probably pretty bad.", e);
            }
        }

        // :: Handle initial population obligations
        // NOTE: There is only one thread running a SubscriptionTerminator, and it is only us that
        // will write null to the _initialPopulationLatch and _onInitialPopulationTasks fields.
        // ?: Fast check if we've already done initial population obligations.
        if (_initialPopulationLatch != null) {
            // -> No, we haven't done initial population obligations yet.

            // SECOND: Release threads hanging on "get" waiting for initial population.

            // Release threads hanging on "get"
            _initialPopulationLatch.countDown();
            // Null out the reference to the latch (volatile field), since we use it for fast-path
            // evaluation in get().
            _initialPopulationLatch = null;

            // THIRD, and final: Run all the initial population runnables that have been added.

            /*
             * Run all the runnables that have been added, waiting for this moment. (Typically adding and/or starting
             * endpoints that depend on the cache being populated.) Note: Since we synch while accessing the list both
             * here and in addOnInitialPopulationTask(), there can't be any ambiguous state: Either the task is present
             * here and not yet run, or it is not present, and was then run in addOnInitialPopulationTask().
             */

            // NOTE: It is nulled by us only, which hasn't happened yet, so it will be non-null now.
            List<Runnable> localOnInitialPopulationTasks;
            synchronized (this) {
                // Copy the field to local variable, which we'll run through outside the synchronized block.
                localOnInitialPopulationTasks = _onInitialPopulationTasks;
                // Null out the reference to the list (volatile field), since we in the .get() use it as
                // evaluation of whether we're still in the initial population phase, or have passed it.
                // NOTE: This is the only place the field is nulled, and it is nulled within synch on this.
                _onInitialPopulationTasks = null;
            }
            // Run all the runnables that have been added.
            for (Runnable onInitialPopulationTask : localOnInitialPopulationTasks) {
                try {
                    onInitialPopulationTask.run();
                }
                catch (Exception e) {
                    // TODO: Handle exception.
                    log.error("Got exception when running onInitialPopulationTask ["
                            + onInitialPopulationTask
                            + "], ignoring but this is probably pretty bad.", e);
                }
            }
        }
        Thread.currentThread().setName(originalThreadName);
    }

    private Stream<?> _getReceiveStreamFromPayload(byte[] payload) throws IOException {
        InflaterInputStreamWithStats iis = new InflaterInputStreamWithStats(payload);
        MappingIterator<?> mappingIterator = _receivedDataTypeReader.readValues(iis);
        // Convert iterator to stream
        return Stream.iterate(mappingIterator, MappingIterator::hasNext,
                UnaryOperator.identity()).map(MappingIterator::next);
    }

    private static class CacheReceivedDataImpl<RECV> implements CacheReceivedData<RECV> {
        private final boolean _fullUpdate;
        private final int _dataSize;
        private final String _metadata;
        private final Stream<RECV> _rStream;
        private final long _receivedUncompressedSize;
        private final long _receivedCompressedSize;

        public CacheReceivedDataImpl(boolean fullUpdate, int dataSize, String metadata, Stream<RECV> recvStream,
                long receivedUncompressedSize, long receivedCompressedSize) {
            _fullUpdate = fullUpdate;
            _dataSize = dataSize;
            _metadata = metadata;
            _rStream = recvStream;
            _receivedUncompressedSize = receivedUncompressedSize;
            _receivedCompressedSize = receivedCompressedSize;
        }

        @Override
        public boolean isFullUpdate() {
            return _fullUpdate;
        }

        @Override
        public int getDataCount() {
            return _dataSize;
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
        public Stream<RECV> getReceivedDataStream() {
            return _rStream;
        }
    }

    private static class CacheUpdatedImpl extends CacheReceivedDataImpl<Void> implements CacheUpdated {
        private final double _updateDurationMillis;

        public CacheUpdatedImpl(boolean fullUpdate, int dataSize, String metadata,
                long receivedUncompressedSize, long receivedCompressedSize, double updateDurationMillis) {
            super(fullUpdate, dataSize, metadata, null, receivedUncompressedSize, receivedCompressedSize);
            _updateDurationMillis = updateDurationMillis;
        }

        @Override
        public double getUpdateDurationMillis() {
            return _updateDurationMillis;
        }
    }

    private static class CacheReceivedPartialDataImpl<RECV, DATA> extends CacheReceivedDataImpl<RECV>
            implements CacheReceivedPartialData<RECV, DATA> {
        private final DATA _data;

        public CacheReceivedPartialDataImpl(boolean fullUpdate, DATA data, int dataSize, String metadata,
                Stream<RECV> rStream,
                long receivedUncompressedSize, long receivedCompressedSize) {
            super(fullUpdate, dataSize, metadata, rStream, receivedUncompressedSize, receivedCompressedSize);
            _data = data;
        }

        @Override
        public DATA getPreviousData() {
            return _data;
        }
    }
}
