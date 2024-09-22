package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    @SuppressWarnings({"unchecked", "rawtypes"})
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
    // Synchronized on this
    // Note: Also used as a fast-path indicator for addOnInitialPopulationTask(), by being nulled.
    private volatile List<Runnable> _onInitialPopulationTasks = new ArrayList<>();

    private DATA _data;

    private final AtomicInteger _updateRequestCounter = new AtomicInteger();

    /**
     * Object that is provided to the 'fullUpdateMapper' {@link Function} which was provided to the constructor of
     * {@link MatsEagerCacheClient}. This object contains the data that was received from the server, and the metadata
     * that was sent along with the data.
     *
     * @param <RECV>
     *            the type of the received data.
     */
    public interface CacheReceivedData<RECV> {
        /**
         * @return number of data items received.
         */
        int getDataCount();

        long getUncompressedSize();

        long getCompressedSize();

        String getMetadata();

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
    public MatsEagerCacheClient<DATA> addOnInitialPopulationTask(Runnable runnable) {
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

    /**
     * Get the data. Will block until the initial full population is done, and during subsequent repopulations. <b>It is
     * imperative that the data structures in the "D" datatype (e.g. any Lists or Maps) aren't read out and stored in
     * any object, or by threads for any timespan above some few milliseconds, but rather queried anew from the cache
     * for every needed access.</b> This both to ensure timely update when new data arrives, and to avoid that memory
     * isn't held up by the old data structures being kept alive.
     *
     * @return the data
     */
    public DATA get() {
        // :: Handle initial population
        // ?: Do we need to wait for the initial population to be done (fast-path null check)? Read directly from field.
        if (_initialPopulationLatch != null) {
            // -> The field was non-null.
            // Doing "double check" using local variable, so as to avoid race condition on nulling out the field.
            var localLatch = _initialPopulationLatch;
            // ?: Is the latch still non-null?
            if (localLatch != null) {
                // -> Yes, so we have to wait on it.
                // Note: We now use the local variable, so it won't be nullled out by the other thread.
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
                throw new IllegalStateException("The data is null, which the contract explicitly forbids."
                        + " Fix your cache update code!");
            }
            return _data;
        }
        finally {
            _cacheContentReadLock.unlock();
        }
    }

    public void close() {
        // TODO: Takedown.
    }

    /**
     * Immediately start the cache client - startup is performed in a separate thread, so this method immediately
     * returns - to wait for initial population, use the {@link #addOnInitialPopulationTask(Runnable) dedicated
     * functionality}. The cache client creates a SubscriptionTerminator for receiving cache updates based on the
     * dataName. Once we're sure this endpoint {@link MatsEndpoint#waitForReceiving(int) has entered the receive loop},
     * a message to the server requesting update is performed. The {@link #get()} method will block until the initial
     * full population is received and processed. When the initial population is done, any
     * {@link #addOnInitialPopulationTask(Runnable) onInitialPopulationTasks} will be invoked.
     * 
     * @return this instance, for chaining.
     */
    public MatsEagerCacheClient<DATA> start() {
        // Run the startup in a separate thread, as we don't want to block the caller.

        // :: Listener to the update topic.
        MatsEndpoint<?, ?> updateTopic = _matsFactory.subscriptionTerminator(MatsEagerCacheServer
                .getBroadcastTopic(_dataName), void.class,
                BroadcastDto.class, (ctx, state, msg) -> {
                    if (!(msg.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)
                            || msg.command.equals(BroadcastDto.COMMAND_UPDATE_PARTIAL))) {
                        // None of my concern
                        return;
                    }

                    int updateRequestCount = _updateRequestCounter.incrementAndGet();

                    byte[] payload = ctx.getBytes(MatsEagerCacheServer.MATS_EAGER_CACHE_PAYLOAD);

                    // :: Now do crazy hack to relieve the Mats thread, and do the actual work in a separate thread.
                    // The rationale is to let the data structures underlying the Mats system (e.g. the JMS Message)
                    // be GCable.
                    Thread thread = new Thread(() -> {
                        handleUpdateInThread(msg, payload);
                    });
                    thread.setName("MatsEagerCacheClient-" + _dataName + "-handleUpdateInThread-#"
                            + updateRequestCount);
                    // Won't hold back the JVM from exiting if it is shut down.
                    thread.setDaemon(true);
                    // Start it.
                    thread.start();
                });

        // :: Perform the initial cache update request in a separate thread, so that we can wait for the endpoint to be
        // ready, and then send the request. We return immediately.
        Thread thread = new Thread(() -> {
            // :: Wait for the endpoint to be ready
            // 10 minutes should really be enough for the service to finish boot and start the MatsFactory, right?
            boolean started = updateTopic.waitForReceiving(600_000);

            // ?: Did it start?
            if (!started) {
                // -> No, so that's bad.
                String msg = "The Update handler SubscriptionTerminator Endpoint would not start within 10 minutes.";
                log.error(msg);
                // TODO: Log exception to monitor and HealthCheck.
                throw new IllegalStateException(msg);
            }

            // :: Request initial population
            CacheRequestDto req = new CacheRequestDto();
            req.nodename = _matsFactory.getFactoryConfig().getNodename();
            req.sentTimestamp = System.currentTimeMillis();
            req.sentNanoTime = System.nanoTime();
            req.command = CacheRequestDto.COMMAND_REQUEST_AT_BOOT;
            try {
                _matsFactory.getDefaultInitiator().initiate(init -> {
                    init.traceId(TraceId.create(_matsFactory.getFactoryConfig().getAppName(),
                            "MatsEagerCacheClient-" + _dataName, "initialCacheUpdateRequest"))
                            .from("MatsEagerCacheClient-" + _dataName + "-initialCacheUpdateRequest")
                            .to("mats.MatsEagerCache." + _dataName + ".UpdateRequest")
                            .send(req);
                });
            }
            catch (Exception e) {
                log.error("Got exception when initiating the initial cache update request.", e);
            }
        });
        thread.setName("MatsEagerCacheClient-" + _dataName + "-initialCacheUpdateRequest");
        // If the JVM is shut down due to bad boot, this thread should not prevent it from exiting.
        thread.setDaemon(true);
        // Start it.
        thread.start();

        return this;
    }

    private void handleUpdateInThread(BroadcastDto msg, byte[] payload) {
        // Write lock
        _cacheContentWriteLock.lock();
        // Handle it.
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
                    Stream<?> rStream = getReceiveStreamFromPayload(payload);
                    payload = null; // Help GC

                    // Invoke the full update mapper
                    _data = _fullUpdateMapper.apply(new CacheReceivedDataImpl<>(dataSize, metadata,
                            rStream, msg.uncompressedSize, msg.compressedSize));
                }
                catch (Throwable e) {
                    // TODO: Log exception to monitor and HealthCheck.
                }
            }
            // ?: Is this a partial update?
            else if (msg.command.equals(BroadcastDto.COMMAND_UPDATE_PARTIAL)) {
                // -> Yes, partial update, so then we update the data "in place".

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
                    Stream<?> rStream = getReceiveStreamFromPayload(payload);

                    // Small hacking here to maybe let the GC have an easier time with the data structures.
                    // Create a free-standing object that holds the data, metadata and stream...
                    CacheReceivedPartialData<?, DATA> cacheReceivedData = new CacheReceivedPartialDataImpl<>(_data,
                            dataSize, metadata, rStream, msg.uncompressedSize, msg.compressedSize);
                    // .. and then we null out the data ...
                    _data = null;
                    // .. and invoke the partial update mapper to get the new data.
                    _data = _partialUpdateMapper.apply(cacheReceivedData);
                }
                catch (Throwable e) {
                    // TODO: Log exception to monitor and HealthCheck.
                }
            }
        }
        finally {
            _cacheContentWriteLock.unlock();
        }

        // :: Handle initial population obligations
        // NOTE: There is only one thread running a SubscriptionTerminator, and it is only us that
        // will write null to the _initialPopulationLatch and _onInitialPopulationTasks fields.
        // ?: Fast check if we've already done initial population obligations.
        if (_initialPopulationLatch != null) {
            // -> No, we haven't done initial population obligations yet.
            // Release threads hanging on "get", waiting for initial population.
            _initialPopulationLatch.countDown();
            // Null out the reference to the latch (volatile field), since we use it for fast-path
            // evaluation in get().
            _initialPopulationLatch = null;

            // Run all the runnables that have been added, waiting for this moment. (Typically adding
            // and/or starting endpoints that depend on the cache being populated.)
            // Note: Since we synch on the list both here and in addOnInitialPopulationTask(), there
            // can't be any ambiguous state: Either the task is present here and not yet run, or it is not
            // present, and was then run in addOnInitialPopulationTask().
            List<Runnable> localOnInitialPopulationTasks = _onInitialPopulationTasks;
            synchronized (localOnInitialPopulationTasks) {
                // Copy the field to local variable
                // Null out the reference to the list (volatile field), since we in the .get() use it as
                // evaluation of whether we're still in the initial population phase, or have passed it.
                // Note: This is the only place the field is nulled, and it is nulled within synch on the
                // list object.
                _onInitialPopulationTasks = null;
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
        }
    }

    private Stream<?> getReceiveStreamFromPayload(byte[] payload) throws IOException {
        InflaterInputStreamWithStats iis = new InflaterInputStreamWithStats(payload);
        MappingIterator<?> objectMappingIterator = _receivedDataTypeReader.readValues(iis);
        // Convert iterator to stream
        return Stream.iterate(objectMappingIterator, MappingIterator::hasNext,
                UnaryOperator.identity()).map(MappingIterator::next);
    }

    private static class CacheReceivedDataImpl<RECV> implements CacheReceivedData<RECV> {
        private final int _dataSize;
        private final String _metadata;
        private final Stream<RECV> _rStream;
        private final long _receivedUncompressedSize;
        private final long _receivedCompressedSize;

        public CacheReceivedDataImpl(int dataSize, String metadata, Stream<RECV> rStream, long receivedUncompressedSize,
                                     long receivedCompressedSize) {
            _dataSize = dataSize;
            _metadata = metadata;
            _rStream = rStream;
            _receivedUncompressedSize = receivedUncompressedSize;
            _receivedCompressedSize = receivedCompressedSize;
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

    private static class CacheReceivedPartialDataImpl<RECV, DATA> extends CacheReceivedDataImpl<RECV>
            implements CacheReceivedPartialData<RECV, DATA> {
        private final DATA _data;

        public CacheReceivedPartialDataImpl(DATA data, int dataSize, String metadata, Stream<RECV> rStream,
                                            long receivedUncompressedSize, long receivedCompressedSize) {
            super(dataSize, metadata, rStream, receivedUncompressedSize, receivedCompressedSize);
            _data = data;
        }

        @Override
        public DATA getPreviousData() {
            return _data;
        }
    }
}
