package io.mats3.util.eagercache;

import static io.mats3.util.eagercache.MatsEagerCacheServer.LogLevel.DEBUG;
import static io.mats3.util.eagercache.MatsEagerCacheServer.LogLevel.INFO;
import static io.mats3.util.eagercache.MatsEagerCacheServer.LogLevel.WARN;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.TraceId;
import io.mats3.util.compression.ByteArrayDeflaterOutputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientImpl;

/**
 * The server side of the Mats Eager Cache system - sitting on the "data owner" side. This server will listen for
 * requests for cache updates when clients boot, and send out a serialization of the source data to the clients, which
 * will deserialize it and make it available to the service. If the source data changes, an update can be pushed by the
 * service by means of {@link #scheduleFullUpdate()}. The server will also periodically send full updates. There's
 * optionally also a feature for {@link #sendPartialUpdate(CacheDataCallback) sending partial updates}, which can be
 * employed if the source data's size, or frequency of updates, makes full updates too resource intensive (this is
 * however more complex to handle on the client side, as it must merge the update with the existing data, and should
 * only be used if the source data is updated quite frequently or if the source data is large).
 * <p>
 * It is expected that the source data is either held in memory (again typically backed by a database), or read directly
 * from a database. If held in memory, the memory view of the source data is also effectively a cache of the database,
 * which leads to a few interesting aspects: As always with systems employing Mats3, it is expected that the source
 * service (the data owner, running the cache server) have multiple instances running, to ensure high availability and
 * scalability. However, events updating the source data will often come in on a single instance (e.g. via a GUI, or via
 * REST), and this instance will then somehow need to propagate the update to its sibling instances. Typically, the
 * update is first stored to the database by the event receiver, whereupon it should tell all sibling instances to
 * update their view (DB-cache) of the actual DB-stored source data. The Mats Eager Cache system have a feature for
 * this, where you can send "sibling commands" to all the siblings via the
 * {@link #sendSiblingCommand(String, String, byte[]) sendSiblingCommand(..)} method and the corresponding
 * {@link #addSiblingCommandListener(Consumer) addSiblingCommandListener(..)} method. <b>All instances - including the
 * originator - will receive the command</b>, and can either, as directed by your command, refresh the source data from
 * database, or apply the supplied update directly to the source data (you can send along a String and a byte array with
 * the command). The idea is that also the originator of the update <i>employs this event to update its own version of
 * the source data</i> if kept in memory, to ensure consistency between the nodes. The method
 * {@link SiblingCommand#originatedOnThisInstance() SiblingCommand.originatedOnThisInstance()} will tell whether the
 * command originated on this instance - the single instance that originated the command (=<code>true</code>) should
 * then propagate the update to the MatsEagerCacheServer via {@link #scheduleFullUpdate()} or
 * {@link #sendPartialUpdate(CacheDataCallback) sendPartialUpdate(CacheDataCallback)}, which then broadcasts the update
 * to all clients.
 * <p>
 * The cache server accesses the data via the {@link CacheDataCallback CacheDataCallback} supplier provided in the
 * constructor. It is important that the source data can be read in a consistent manner, so some kind of synchronization
 * or locking of the data should be employed while the cache server reads it (mainly relevant if the source data is held
 * in memory).
 * <p>
 * <h2>Design and Usage of Caches, and the <code>TRANSFER</code> Data Type</h2>
 * <p>
 * You should never send over full domain objects to the clients, but rather a <code>TRANSFER</code> DTO that is
 * tailored to what the clients need. This both to save processing resources (read on!), and to decouple/insulate the
 * service-internal domain API from whatever contract the cache server sets up with the clients. Also, you'll want to
 * keep memory usage on the client for keeping the cached data as low as possible.
 * <p>
 * <h3>Serializer configuration and consequences</h3>
 * <p>
 * The Jackson serializer will serialize and deserialize <b>all fields of the DTO, of all visibility levels.</b> It
 * introspects the fields - not using any getters. On the deserializing side, <b>it depends on a present no-args
 * constructor to make the object</b>, and it does not use any argument-taking constructor to instantiate. It doesn't
 * use any setters, instead relying on Jackson's ability to set fields directly (technically, the process is different
 * for Records, but that does not make any difference in this context).
 * <p>
 * Jackson is also configured for compact serialization, and lenient deserialization: On the sending side, it does not
 * include fields that are null. On the receiving side, it does not require DTO specified fields to be present in the
 * JSON, and it does not fail if there are extra fields in the JSON compared to the DTO. <b>Importantly, this means that
 * there are clear ways of evolving the data structures: You can both add and remove fields to the server side DTO
 * without breaking the client side.</b> This is because the client side will simply ignore fields it does not know
 * about, and it will not fail if fields are missing. Obviously, if you remove a field that one of the cache's clients
 * are dependent on, it will now receive <code>null</code>, <code>0</code>, or <code>false</code> when the client code
 * tries to use the field, which probably is rather bad. (A multi-repo source-viewing and -searching system like
 * <a href="https://oracle.github.io/opengrok/">OpenGrok</a> is invaluable in such contexts!) <b>This flexibility also
 * implies that you shall NEVER include fields in the Transfer DTO that no clients yet are using "just in case"</b> -
 * this is a complete waste of both CPU, memory and network bandwidth, and will just clutter up your maintenance. Add
 * the field when a client needs it. You might even want to make two caches: One for those services that just need a few
 * fields, and another for those that use many.
 * <p>
 * <b>You are advised against using enums!</b> The problem is evolution: If you add a new enum value, and the client
 * does not yet know about it, it will fail deserialization! Rather use Strings for the enum constants, mapping back
 * (including a default) to enum values when constructing domain objects from the <code>TRANSFER</code> DTOs on the
 * client side.
 * <p>
 * <b>You should not depend on any Jackson specific features!</b> The idea is that you make a simple
 * <code>TRANSFER</code> DTO (it can be as deep as you need, with nested DTOs and Maps and Lists and whatever), but not
 * using any Jackson specific annotations or features. This is to ensure that we can easily switch out Jackson for
 * another serializer if we find a faster or more efficient one. The only contract the cache system gives is that the
 * DTO object will "magically appear" on the client side.
 * <p>
 * <h3>Memory usage on client when deserializing / Sharing common instances</h3>
 * <p>
 * Current experience wrt. usage (financial context with multiple time series over different securities) shows that
 * there are many instances of date representations and other common identifiers like ISIN. Java does not natively help
 * one bit with this, so you might end up with a lot of e.g. <code>LocalDate</code> instances representing the same
 * date. This is a waste of memory, and you will want to consider using a simple shared instance cache for these when
 * going from the <code>TRANSFER</code> DTO to the actual domain object on the client - this can substantially reduce
 * memory usage. A simple <code>Map&lt;String, LocalDate&gt;</code> or <code>Map&lt;String, Isin&gt;</code> can do
 * wonders here, using the {@link Map#computeIfAbsent(Object, java.util.function.Function) computeIfAbsent(..)} method.
 * If you're sure some particular Strings are common and will effectively live for the duration of the JVM, you should
 * consider using <code>String.intern()</code> on them.
 * <p>
 * <h3>Developing the Client on the Server side</h3>
 * <p>
 * It makes sense to develop the client side on the server side, as you then have the full source code available for
 * both sides, and be close to the source data, thus simplifying the development. Note: You should NOT actually run the
 * client on the server side, except for testing purposes! Oftentimes, more than one service is interested in the cached
 * dataset, and in such cases it makes sense to let the "ownership" of the client code be on server side, and copy over
 * the client code to the other services - for some popular datasets, it might even make sense to have a separate client
 * library that is shared between the services.
 * <p>
 * <h3>Testing</h3>
 * <p>
 * You should probably make unit tests that serialize and deserialize the <code>TRANSFER</code> DTO with relevant (deep)
 * content, to ensure that it works as expected. Use the
 * {@link FieldBasedJacksonMapper#getMats3DefaultJacksonObjectMapper()} for your tests: Make a DTO setup, serialize it
 * and keep the String JSON, then deserialize it, and serialize the result again, asserting that the String JSON is
 * identical. You should probably make integration tests on the server side which additionally creates the cache client,
 * and then do a full run, and then checks that the client has received the DTO as expected (The unit tests in the Mats3
 * repo under the <code>mats-util</code> module, search for "Test_EagerCache..", can be inspirational). This goes
 * "double up" if you use the partial update feature, as then also the client side becomes a bit more complex.
 * <p>
 * <b>Thread-safety:</b> After the cache server is started, it is thread-safe: Calls to {@link #scheduleFullUpdate()},
 * {@link #sendPartialUpdate(CacheDataCallback) sendPartialUpdate(..)}, and
 * {@link #sendSiblingCommand(String, String, byte[]) sendSiblingCommand(..)} are thread-safe, and can be called from
 * any thread - but be sure to read the important notice wrt. deadlock at the
 * {@link #sendPartialUpdate(CacheDataCallback) sendPartialUpdate(..)} JavaDoc! The construction-supplied full-update
 * {@link CacheDataCallback CacheDataCallback} is invoked (at any time) by a single-threaded executor within the cache
 * server, however, partial updates are synchronously handled by you. If the source data is held in memory, you will
 * probably both have accesses from the service internals itself, and from the cache server. These accesses to this
 * shared memory must be guarded by synchronization or locking. The {@link #addSiblingCommandListener(Consumer)} is
 * thread-safe, and can technically be called both before or after the cache server is started, but listeners should
 * obviously be added before any sibling commands are sent from yourself or your siblings, thus before start() is
 * invoked is what to aim for.
 * 
 * @author Endre Stølsvik 2024-09-03 19:30 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsEagerCacheServer {

    String LOG_PREFIX = "#MatsEagerCache#S ";

    /**
     * The compressed transfer data is added to the outgoing Mats3 publish (broadcast) message as a
     * {@link MatsInitiate#addBytes(String, byte[]) binary sideload}, with this key (you do not need to care about
     * this!)
     */
    String SIDELOAD_KEY_DATA_PAYLOAD = "mec_payload";
    /**
     * A solution is in place for the situation where an update were in process of being produced, but the producing
     * node went down before it was sent, or we had (temporary) problems producing the update: All sibling nodes will in
     * some minutes verify that an update was actually produced and sent, and if not, request another full update to
     * cover for the evidently missing update. (5 minutes - but see also {@link #ENSURER_WAIT_TIME_LONG_MILLIS}).
     */
    int ENSURER_WAIT_TIME_SHORT_MILLIS = 5 * 60 * 1000;
    /**
     * See {@link #ENSURER_WAIT_TIME_SHORT_MILLIS} - but if we're currently already creating a source data set, or if we
     * currently have problems making source data sets, or if the request for full update was itself triggered by an
     * ensurer, we'll wait longer - to not risk continuously (attempting to) producing updates. (15 minutes)
     */
    int ENSURER_WAIT_TIME_LONG_MILLIS = 15 * 60 * 1000;
    /**
     * If the time since last update is higher than this, the next update will be scheduled to run immediately (for
     * manual requests) or soon (for client boot requests). (30 seconds).
     *
     * @see #scheduleFullUpdate()
     * @see #DEFAULT_SHORT_DELAY_MILLIS
     * @see #DEFAULT_LONG_DELAY_MILLIS
     */
    int FAST_RESPONSE_LAST_RECV_THRESHOLD_MILLIS = 30_000;
    /**
     * Read {@link #scheduleFullUpdate()} for the rationale behind this delay. (2.5 seconds)
     */
    int DEFAULT_SHORT_DELAY_MILLIS = 2500;
    /**
     * Read {@link #scheduleFullUpdate()} for the rationale behind this delay. (7 seconds)
     */
    int DEFAULT_LONG_DELAY_MILLIS = 7000;
    /**
     * Some commands, e.g. {@link #sendSiblingCommand(String, String, byte[]) sendSiblingCommand(..)} (and the
     * alternative start method {@link #startAndWaitForReceiving()}) needs to wait for the broadcast terminator to be
     * ready to receive. This is the maximum time to wait for this to happen. (4 minutes)
     */
    int MAX_WAIT_FOR_RECEIVING_SECONDS = 240;
    /**
     * During startup, we need to first ensure that we can make a source data set before firing up the endpoints. If it
     * fails, it will try again until it manages - but sleep an amount between each attempt. Capped exponential from 2
     * seconds, this is the max sleep time between attempts. (30 seconds)
     */
    int MAX_INTERVAL_BETWEEN_STARTUP_ATTEMPTS_MILLIS = 30_000;
    /**
     * Default interval between periodic full updates. The default is nearly 2 hours, hoping that you'd rather aim for a
     * push-on-update approach, than rely on periodic updates. (111 minutes)
     */
    double DEFAULT_PERIODIC_FULL_UPDATE_INTERVAL_MINUTES = 111;

    /**
     * Create a Mats Eager Cache Server.
     *
     * @param matsFactory
     *            The MatsFactory to use.
     * @param dataName
     *            The name of the data, which will be used in the Mats endpoints. It must be unique within the full
     *            system, i.e. the "Mats Fabric".
     * @param transferDataType
     *            The data type to transmit to the cache clients. It should be tailored to what the cache clients need,
     *            and should be serializable by Jackson.
     * @param fullDataCallbackSupplier
     *            The supplier of the {@link CacheDataCallback} that provides the source data.
     * @return the created Mats Eager Cache Server.
     * @param <TRANSFER>
     *            The data type to transmit to the cache clients.
     */
    static <TRANSFER> MatsEagerCacheServer create(MatsFactory matsFactory, String dataName,
            Class<TRANSFER> transferDataType, Supplier<CacheDataCallback<TRANSFER>> fullDataCallbackSupplier) {
        return new MatsEagerCacheServerImpl(matsFactory, dataName, transferDataType, fullDataCallbackSupplier);
    }

    /**
     * Override default periodic full update interval. The default is 111 minutes, which is a bit less than 2 hours.
     *
     * @param periodicFullUpdateIntervalMinutes
     *            The interval between periodic full updates, in minutes. If set to 0, no periodic full updates will be
     *            done.
     * @return this instance, for chaining.
     */
    MatsEagerCacheServer setPeriodicFullUpdateIntervalMinutes(double periodicFullUpdateIntervalMinutes);

    /**
     * Add a listener for sibling commands. {@link SiblingCommand Sibling commands} are messages sent from one sibling
     * to all the other siblings, including the one that originated the command (you may ask
     * {@link SiblingCommand#originatedOnThisInstance() "whether it was you"}). This can be useful to propagate updates
     * to the source data to all the siblings, to ensure that the source data is consistent between the siblings.
     *
     * @param siblingCommandEventListener
     *            the listener to add.
     * @return this instance, for chaining.
     */
    MatsEagerCacheServer addSiblingCommandListener(Consumer<SiblingCommand> siblingCommandEventListener);

    /**
     * Sends a {@link SiblingCommand sibling command}. This is a message sent from one sibling to all the other
     * siblings, <b>including the one that originated the command.</b> This can be useful to propagate updates to the
     * source data to all the siblings, to ensure that the source data is consistent between the siblings.
     * <p>
     * <b>Remember that in a boot or redeploy situation, your service instances will typically be started in a staggered
     * fashion - and at any time, a new sibling might be started, or an existing stopped. Thus, you must not expect a
     * stable cluster where a stable set of siblings are always present.</b>
     *
     * @param command
     *            the command name. This is a string that the siblings can use to determine what to do. It has no
     *            meaning to the Cache Server or the Cache Clients.
     * @param stringData
     *            the string data to send with the command. This can be used for any purpose, e.g. stating which entity
     *            Ids should be re-read from the DB. It has no meaning to the Cache Server or the Cache Clients. This
     *            can be {@code null} if no string data is to be sent.
     * @param binaryData
     *            the binary data to send with the command. This can be used for any purpose, e.g. serialize the data
     *            which should be updated on all siblings. It has no meaning to the Cache Server or the Cache Clients.
     *            This can be {@code null} if no binary data is to be sent.
     */
    void sendSiblingCommand(String command, String stringData, byte[] binaryData);

    /**
     * Manually schedules a full update of the cache, typically used to programmatically propagate a change in the
     * source data.
     * <p>
     * It is scheduled to run after a little while, the delay being a function of how soon since the last time a full
     * update was run. If it is a long time ({@link #FAST_RESPONSE_LAST_RECV_THRESHOLD_MILLIS}) since last update was
     * run, it will either be run immediately (if manual invocation as by this method, or the corresponding on client)
     * or {@link #DEFAULT_SHORT_DELAY_MILLIS soon} (if the request is due to a client boot), while if the previous time
     * was a short time ago, it will be scheduled to run {@link #DEFAULT_LONG_DELAY_MILLIS a bit later}. The reason for
     * this logic is to try to mitigate "thundering herd" problems, where many update request at the same time - either
     * by this server-side method, or the client's similar method, or more importantly, by multiple booting clients -
     * would result in a lot of full updates being produced, sent, and processed in parallel. Such a situation occurs if
     * many clients boot at the same time, e.g. with a "cold boot" of the entire system. The system attempts to handle
     * multiple servers by synchronizing who sends an update using a small broadcast protocol between them.
     * <p>
     * The data to send is retrieved by the cache server using the {@link CacheDataCallback} supplier provided when
     * constructing the cache server.
     * <p>
     * The update is asynchronous - this method returns immediately.
     */
    void scheduleFullUpdate();

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
     * ONLY be done when the cache server invokes the supplied {@link CacheDataCallback#provideSourceData(Consumer)
     * partialDataCallback.provideSourceData(Consumer)} method to retrieve the source data. Failure to observe this will
     * eventually result in a deadlock. The reason is that a full update may concurrently be in process, which also will
     * lock the source data when the full update callback is invoked, and if that full update starts before this partial
     * update which wrongly already holds the lock, we're stuck.
     * <p>
     * It is also important to let the stream of partial data returned from
     * {@link CacheDataCallback#provideSourceData(Consumer) partialDataCallback.provideSourceData(Consumer)} read
     * directly from the source data structures, and not from some temporary not-applied representation, as otherwise
     * the partial update might send out data that is older than what is currently present and which might have already
     * been sent via a concurrent update (think about races here). Thus, always first apply the update to the source
     * data (on all the instances running the Cache Server) in some atomic fashion (read up on
     * {@link MatsEagerCacheServerImpl.SiblingCommand}s), and then retrieve the partial update from the source data,
     * also in an atomic fashion (e.g. use synchronization or locking).
     * <p>
     * On a general basis, it is important to realize that you are responsible for keeping the source data in sync
     * between the different instances of the service, and that the cache server only serves the data to the clients -
     * and such serving can happen from any one of the service instances. This is however even more important to
     * understand wrt. partial updates: The Cache Clients will all get the partial update - but this does not hold for
     * the instances running the Cache Server: You must first ensure that all these instances have the partial update
     * applied, before propagating it to the Cache Clients. This is not really a problem if you serve the data from a
     * database, as you then have an external single source of truth, but if you serve the data from memory, you must
     * ensure that the data is kept in sync between the instances. The {@link MatsEagerCacheServerImpl.SiblingCommand}
     * feature is meant to help with this.
     * <p>
     * Partial updates is an optional functionality to conserve resources for situations where somewhat frequent partial
     * updates is performed to the source data. The caching system will work just fine with only full updates. If the
     * feature is employed, the client side must also be coded up to handle partial updates, as otherwise the client
     * will end up with stale data.
     * <p>
     * Correctly applying a partial update on the client is more complex than consuming a full update, as the client
     * must merge the partial update into the existing data structures, taking care to overwrite where appropriate, but
     * insert if the entity is new. This is why the feature is optional, and should only be used if the source data is
     * large and/or updated frequently enough to make the use of partial updates actually have a positive performance
     * impact outweighing the increased complexity on both the server but particularly the client side.
     * <p>
     * It is advisable to not send a lot of partial updates in a short time span, as this will result in memory churn
     * and higher memory usage on the clients due to message reception and partial update merge. Rather coalesce the
     * partial updates into a single update, or use a waiting mechanism until the source data has stabilized before
     * sending out a partial update - or just send a full update.
     * <p>
     * Also, if the partial update is of a substantial part of the full data, it is advisable to send a full update
     * instead - this will probably give a lower peak memory load on the clients, as they will then just throw away the
     * old data before updating with the new data instead of keeping the old data around while merging in the new.
     * <p>
     * There is no solution for sending partial delete updates from the cache, so to remove an element from the cache, a
     * full update must be performed.
     * <p>
     * <i>Note on typing: The <code>TRANSFER</code> datatype for partial updates shall be the same as the one used for
     * the full update, but it was decided to not include the type as a generic on <code>MatsEagerCacheServer</code>, so
     * that users wouldn't have to always reference the cache server with the transfer type when the partial update
     * feature might not even be in use. This means that you'll manually have to ensure that the partial update data is
     * of the same type as the full update - the compiler won't remember it for you!</i>
     *
     * @param partialDataCallback
     *            the callback which the cache server invokes to retrieve the source data to send to the clients.
     */
    <TRANSFER> void sendPartialUpdate(CacheDataCallback<TRANSFER> partialDataCallback);

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
     */
    void start();

    /**
     * <i>(Probably most useful for tests - or being run in a thread.)</i> Starts the cache server, and waits for it to
     * be fully running: It first invokes {@link #start()} (which will assert that it can get source data before
     * starting the endpoints), and then waits for the endpoints entering their receive-loops using
     * {@link MatsEndpoint#waitForReceiving(int)}. This method will thus block until the cache server is fully running,
     * and able to serve cache clients.
     * <p>
     * Note that if there are problems with the source data, the server will not enter state 'running', and the
     * endpoints won't start - and this method will throw out with {@link IllegalStateException} after
     * {@link #MAX_WAIT_FOR_RECEIVING_SECONDS}.
     */
    void startAndWaitForReceiving();

    /**
     * Shuts down the cache server. It stops and removes the endpoints, and the cache server will no longer be able to
     * serve cache clients. Closing is idempotent; Multiple invocations will not have any effect. It is not possible to
     * restart the cache server after it has been closed.
     */
    void close();

    /**
     * Returns a "live view" of the cache server information, that is, you only need to invoke this method once to get
     * an instance that will always reflect the current state of the cache server.
     *
     * @return a "live view" of the cache server information.
     */
    CacheServerInformation getCacheServerInformation();

    /**
     * Enum defining the life cycle of the cache server.
     */
    enum CacheServerLifeCycle {
        NOT_YET_STARTED, STARTING_ASSERTING_DATA_AVAILABILITY, STARTING_PROBLEMS_WITH_DATA, RUNNING, STOPPING, STOPPED;
    }

    /**
     * The service must provide an implementation of this interface to the cache-server, via a {@link Supplier}, so that
     * the cache-server may request the information when it needs it. The cache server may ask for the data at any time,
     * and the supplier must provide a consistent snapshot of the data. The only required method to implement,
     * {@link #provideSourceData(Consumer)}, is invoked by the cache server to retrieve the data. The service must
     * invoke the provided DTO-{@link Consumer} repeatedly until the entire dataset (for full updates), or the partial
     * dataset (for partial updates), has been provided, and then close any resources (e.g. SQL Connection), and return
     * - thus marking the end of data, which will be sent to the cache clients.
     *
     * @param <TRANSFER>
     *            the data type to transmit to the cache clients. It should be tailored to what the cache clients need,
     *            and should be serializable by Jackson.
     */
    @FunctionalInterface
    interface CacheDataCallback<TRANSFER> {
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
         * Provide the actual data: The cache server will invoke this method to retrieve the data to send to cache
         * clients. You invoke the supplied consumer repeatedly until you've provided the entire dataset (for full
         * updates), or the partial dataset (for partial updates), and then close any resources (e.g. SQL Connection),
         * and return - thus marking the end of data, which will be sent to the cache clients
         * <p>
         * Care must be taken to ensure that the stream represent a consistent snapshot of the data, and that the data
         * is not modified while the stream is being read, so some kind of synchronization or locking of the source data
         * should be employed (mainly relevant if the source data is held in memory).
         */
        void provideSourceData(Consumer<TRANSFER> consumer);
    }

    /**
     * A sibling command. This is a message {@link #sendSiblingCommand(String, String, byte[]) sent from one service
     * instance to all siblings}, including the one that originated the command. You may use the method
     * {@link #originatedOnThisInstance()} to determine whether this instance originated the command. This is relevant
     * if you have the source data in memory: If for example a user makes a change to the source data using the
     * service's GUI, you must now apply it to yourself and all the siblings. Instead of applying the change directly,
     * you do it via a sibling command, which will then be sent to all the siblings, including yourself - and apply the
     * change in the sibling command listener. The data will thus be consistent between all siblings. You then need to
     * propagate the change to the cache clients - and it doesn't make sense that all the siblings initiate this - so
     * you can use the {@link #originatedOnThisInstance()} to determine whether this instance should initiate the cache
     * update.
     */
    interface SiblingCommand {
        /**
         * Since all nodes will receive the command, including the one that originated it, this method tells whether the
         * command originated on this instance.
         *
         * @return whether the command originated on this instance.
         */
        boolean originatedOnThisInstance();

        /**
         * This is the timestamp ({@link System#currentTimeMillis()}) when the command was sent.
         *
         * @return the timestamp when the command was sent.
         */
        long getSentTimestamp();

        /**
         * This is the nanotime ({@link System#nanoTime()}) when the command was sent. This only makes sense on the
         * instance that {@link #originatedOnThisInstance() originated the command}, since nano time is not comparable
         * between different JVM instances.
         *
         * @return the nano time when the command was sent.
         */
        long getSentNanoTime();

        /**
         * @return the command name. This is a string that the siblings can use to determine what to do. It has no
         *         meaning to the Cache Server or the Cache Clients.
         */
        String getCommand();

        /**
         * @return the string data sent with the command.
         */
        String getStringData();

        /**
         * @return the binary data sent with the command.
         */
        byte[] getBinaryData();
    }

    /**
     * Information about the cache server. This is a "life view" of the cache server, that is, you only need to invoke
     * the method once to get an instance that will always reflect the current state of the cache server.
     */
    interface CacheServerInformation {
        String getDataName();

        String getNodename();

        String getCacheRequestQueue();

        String getBroadcastTopic();

        CacheServerLifeCycle getCacheServerLifeCycle();

        long getCacheStartedTimestamp();

        long getLastFullUpdateRequestReceivedTimestamp();

        long getLastFullUpdateProductionStartedTimestamp();

        double getLastFullUpdateProduceTotalMillis();

        long getLastFullUpdateSentTimestamp();

        long getLastFullUpdateReceivedTimestamp();

        long getLastPartialUpdateReceivedTimestamp();

        long getLastAnyUpdateReceivedTimestamp();

        long getLastUpdateSentTimestamp();

        boolean isLastUpdateFull();

        double getLastUpdateProduceTotalMillis();

        double getLastUpdateSourceMillis();

        double getLastUpdateSerializeMillis();

        double getLastUpdateCompressMillis();

        long getLastUpdateCompressedSize();

        long getLastUpdateUncompressedSize();

        int getLastUpdateCount();

        String getLastUpdateMetadata();

        double getPeriodicFullUpdateIntervalMinutes();

        int getNumberOfFullUpdatesSent();

        int getNumberOfPartialUpdatesSent();

        int getNumberOfFullUpdatesReceived();

        int getNumberOfPartialUpdatesReceived();

        List<LogEntry> getLogEntries();

        List<ExceptionEntry> getExceptionEntries();

    }

    enum LogLevel {
        DEBUG, INFO, WARN
    }

    enum MonitorCategory {
        INITIAL_POPULATION, PERIODIC_UPDATE, RECEIVED_BROADCAST, CACHE_SERVER, CACHE_CLIENT, REQUEST_UPDATE_FROM_CLIENT, REQUEST_UPDATE_PERIODIC, REQUEST_UPDATE_SEND_NOW, REQUEST_UPDATE_COALESCE, REQUEST_UPDATE_FROM_SERVER, ENSURE_UPDATE, SIBLING_COMMAND, PRODUCE_DATA, GET, RECEIVED_UPDATE, SEND_UPDATE, OTHER
    }

    interface LogEntry {
        MonitorCategory getCategory();

        LogLevel getLevel();

        String getMessage();

        long getTimestamp();

        String toHtmlString();
    }

    interface ExceptionEntry {
        MonitorCategory getCategory();

        String getMessage();

        Throwable getThrowable();

        long getTimestamp();

        String toHtmlString();
    }

    // ======== The 'MatsEagerCacheServer' implementation class

    class MatsEagerCacheServerImpl implements MatsEagerCacheServer {
        private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheServer.class);

        private final MatsFactory _matsFactory;
        private final String _dataName;
        private final Supplier<CacheDataCallback<?>> _fullDataCallbackSupplier;

        private volatile double _periodicFullUpdateIntervalMinutes = DEFAULT_PERIODIC_FULL_UPDATE_INTERVAL_MINUTES;

        private final String _nodename;
        private final ObjectWriter _sentDataTypeWriter;
        private final ThreadPoolExecutor _produceAndSendExecutor;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private <TRANSFER> MatsEagerCacheServerImpl(MatsFactory matsFactory, String dataName,
                Class<TRANSFER> transferDataType,
                Supplier<CacheDataCallback<TRANSFER>> fullDataCallbackSupplier) {

            _matsFactory = matsFactory;
            _dataName = dataName;
            _fullDataCallbackSupplier = (Supplier<CacheDataCallback<?>>) (Supplier) fullDataCallbackSupplier;

            // Cache the nodename
            _nodename = matsFactory.getFactoryConfig().getNodename();

            // :: Jackson JSON ObjectMapper
            ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

            // Make specific Writer for the "transferDataType" - this is what we will need to serialize to send to
            // clients.
            // Configure as NDJSON (Newline Delimited JSON), which is a good format for streaming.
            _sentDataTypeWriter = mapper.writerFor(transferDataType).withRootValueSeparator("\n");

            // Bare-bones assertion that we can serialize an empty instance of the transferDataType.
            try {
                _sentDataTypeWriter.writeValueAsString(transferDataType.getDeclaredConstructor().newInstance());
            }
            catch (Throwable e) {
                throw new IllegalArgumentException("Could not serialize a newly constructed instance of the"
                        + " transferDataType [" + transferDataType + "], which doesn't bode well at all!"
                        + " This is a critical error, and we won't create the server.", e);
            }

            // Bare-bones assertion that we can deserialize the transferDataType (for the client)
            try {
                mapper.readerFor(transferDataType).readValue("{}");
            }
            catch (Throwable e) {
                throw new IllegalArgumentException("Could not deserialize the transferDataType [" + transferDataType
                        + "], which will be a problem for the clients. This is a critical error, and we won't create"
                        + " the server.", e);
            }

            // Create the single-threaded executor for producing and sending updates.
            _produceAndSendExecutor = new ThreadPoolExecutor(1, 1, 1L, TimeUnit.MINUTES,
                    new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread t = new Thread(runnable, "MatsEagerCacheServer." + dataName + "-ProduceAndSendUpdate");
                        t.setDaemon(true);
                        return t;
                    });
        }

        @Override
        public MatsEagerCacheServer setPeriodicFullUpdateIntervalMinutes(double periodicFullUpdateIntervalMinutes) {
            if (_cacheServerLifeCycle != CacheServerLifeCycle.NOT_YET_STARTED) {
                throw new IllegalStateException("Can only set 'periodicFullUpdateIntervalMinutes' before starting.");
            }
            if ((periodicFullUpdateIntervalMinutes != 0) && (periodicFullUpdateIntervalMinutes < 0.05)) {
                throw new IllegalArgumentException(
                        "'periodicFullUpdateIntervalMinutes' must be == 0 (no period updates)"
                                + " or >0.05 (3 seconds, which is absurd).");
            }
            _periodicFullUpdateIntervalMinutes = periodicFullUpdateIntervalMinutes;
            return this;
        }

        private final CacheServerInformation _cacheServerInformation = new CacheServerInformationImpl();

        private volatile CacheServerLifeCycle _cacheServerLifeCycle = CacheServerLifeCycle.NOT_YET_STARTED;
        private volatile CountDownLatch _waitForRunningLatch = new CountDownLatch(1);
        private volatile MatsEndpoint<Void, Void> _broadcastTerminator;
        private volatile MatsEndpoint<Void, Void> _requestTerminator;

        // Synchronized on 'this' due to transactional needs.
        private int _updateRequest_OutstandingCount;
        // Synchronized on 'this' due to transactional needs.
        private String _updateRequest_HandlingNodename; // For election: The lowest nodename will handle the request.

        private volatile boolean _currentlyMakingSourceDataResult;
        private volatile boolean _currentlyHavingProblemsCreatingSourceDataResult;

        private volatile long _cacheStartedTimestamp;
        private volatile long _lastFullUpdateRequestReceivedTimestamp;
        private volatile long _lastFullUpdateProductionStartedTimestamp;
        private volatile double _lastFullUpdateProduceTotalMillis;
        private volatile long _lastFullUpdateSentTimestamp;
        private volatile long _lastFullUpdateReceivedTimestamp;
        private volatile long _lastPartialUpdateReceivedTimestamp;
        private volatile long _lastAnyUpdateReceivedTimestamp; // Both full and partial.
        private final AtomicInteger _numberOfFullUpdatesSent = new AtomicInteger();
        private final AtomicInteger _numberOfPartialUpdatesSent = new AtomicInteger();
        private final AtomicInteger _numberOfFullUpdatesReceived = new AtomicInteger();
        private final AtomicInteger _numberOfPartialUpdatesReceived = new AtomicInteger();

        private volatile long _lastUpdateSent;
        private volatile boolean _lastUpdateWasFull;
        private volatile double _lastUpdateProduceTotalMillis;
        private volatile double _lastUpdateSourceMillis;
        private volatile double _lastUpdateSerializeMillis;
        private volatile double _lastUpdateCompressMillis;
        private volatile int _lastUpdateCompressedSize;
        private volatile long _lastUpdateUncompressedSize;
        private volatile int _lastUpdateCount;
        private volatile String _lastUpdateMetadata;

        // Use a lock to make sure that only one thread is producing and sending an update at a time, and make it fair
        // so that entry into the method is sequenced in the order of the requests.
        private final ReentrantLock _produceAndSendUpdateLock = new ReentrantLock(true);

        private final CopyOnWriteArrayList<Consumer<SiblingCommand>> _siblingCommandEventListeners = new CopyOnWriteArrayList<>();

        private volatile List<MatsEagerCacheClientImpl<?>> _cacheClients;

        private int _shortDelay = DEFAULT_SHORT_DELAY_MILLIS;
        private int _longDelay = DEFAULT_LONG_DELAY_MILLIS;

        private volatile PeriodicUpdate _periodicUpdate;

        private final CacheMonitor _cacheMonitor = new CacheMonitor(log);

        private class CacheServerInformationImpl implements CacheServerInformation {
            @Override
            public String getDataName() {
                return _dataName;
            }

            @Override
            public String getNodename() {
                return _nodename;
            }

            @Override
            public String getCacheRequestQueue() {
                return _getCacheRequestQueue(_dataName);
            }

            @Override
            public String getBroadcastTopic() {
                return _getBroadcastTopic(_dataName);
            }

            @Override
            public CacheServerLifeCycle getCacheServerLifeCycle() {
                return _cacheServerLifeCycle;
            }

            @Override
            public long getCacheStartedTimestamp() {
                return _cacheStartedTimestamp;
            }

            @Override
            public long getLastFullUpdateRequestReceivedTimestamp() {
                return _lastFullUpdateRequestReceivedTimestamp;
            }

            @Override
            public long getLastFullUpdateProductionStartedTimestamp() {
                return _lastFullUpdateProductionStartedTimestamp;
            }

            @Override
            public double getLastFullUpdateProduceTotalMillis() {
                return _lastFullUpdateProduceTotalMillis;
            }

            @Override
            public long getLastFullUpdateSentTimestamp() {
                return _lastFullUpdateSentTimestamp;
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
            public long getLastAnyUpdateReceivedTimestamp() {
                return _lastAnyUpdateReceivedTimestamp;
            }

            @Override
            public long getLastUpdateSentTimestamp() {
                return _lastUpdateSent;
            }

            @Override
            public boolean isLastUpdateFull() {
                return _lastUpdateWasFull;
            }

            @Override
            public double getLastUpdateProduceTotalMillis() {
                return _lastUpdateProduceTotalMillis;
            }

            @Override
            public double getLastUpdateSourceMillis() {
                return _lastUpdateSourceMillis;
            }

            @Override
            public double getLastUpdateSerializeMillis() {
                return _lastUpdateSerializeMillis;
            }

            @Override
            public double getLastUpdateCompressMillis() {
                return _lastUpdateCompressMillis;
            }

            @Override
            public long getLastUpdateCompressedSize() {
                return _lastUpdateCompressedSize;
            }

            @Override
            public long getLastUpdateUncompressedSize() {
                return _lastUpdateUncompressedSize;
            }

            @Override
            public int getLastUpdateCount() {
                return _lastUpdateCount;
            }

            @Override
            public String getLastUpdateMetadata() {
                return _lastUpdateMetadata;
            }

            @Override
            public double getPeriodicFullUpdateIntervalMinutes() {
                return _periodicFullUpdateIntervalMinutes;
            }

            @Override
            public int getNumberOfFullUpdatesSent() {
                return _numberOfFullUpdatesSent.get();
            }

            @Override
            public int getNumberOfPartialUpdatesSent() {
                return _numberOfPartialUpdatesSent.get();
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
            public List<LogEntry> getLogEntries() {
                return _cacheMonitor.getLogEntries();
            }

            @Override
            public List<ExceptionEntry> getExceptionEntries() {
                return _cacheMonitor.getExceptionEntries();
            }
        }

        @Override
        public MatsEagerCacheServer addSiblingCommandListener(Consumer<SiblingCommand> siblingCommandEventListener) {
            _siblingCommandEventListeners.add(siblingCommandEventListener);
            return this;
        }

        private static final String SIDELOAD_KEY_SIBLING_COMMAND_BYTES = "scb";

        @Override
        public void sendSiblingCommand(String command, String stringData, byte[] binaryData) {
            // We must be in correct state, and the broadcast terminator must be ready to receive.
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);

            // Construct the broadcast DTO
            BroadcastDto broadcast = new BroadcastDto(BroadcastDto.COMMAND_SIBLING_COMMAND, _nodename);
            broadcast.siblingCommand = command;
            broadcast.siblingStringData = stringData;

            // Send the broadcast
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                init.traceId(TraceId.create("EagerCache." + _dataName, "SiblingCommand").add("cmd", command))
                        .addBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES, binaryData)
                        .from("MatsEagerCacheServer." + _dataName + ".SiblingCommand")
                        .to(_getBroadcastTopic(_dataName))
                        .publish(broadcast);
            });
        }

        @Override
        public void scheduleFullUpdate() {
            _scheduleFullUpdateFromServer();
        }

        @Override
        public <TRANSFER> void sendPartialUpdate(CacheDataCallback<TRANSFER> partialDataCallback) {
            _cacheMonitor.log(INFO, MonitorCategory.SEND_UPDATE, "Partial update invoked!");
            _produceAndSendUpdate(null, () -> partialDataCallback, false);
        }

        @Override
        public void start() {
            synchronized (this) {
                // ?: Assert that we are not already started.
                if (_cacheServerLifeCycle != CacheServerLifeCycle.NOT_YET_STARTED) {
                    // -> We've already started - so you evidently have no control over the lifecycle of this object!
                    var e = new IllegalStateException("The MatsEagerCacheServer should be NOT_YET_STARTED when"
                            + " starting, it is [" + _cacheServerLifeCycle + "].");
                    _cacheMonitor.exception(MonitorCategory.CACHE_SERVER,
                            "Wrong state: The MatsEagerCacheServer should be"
                                    + " NOT_YET_STARTED when starting, it is [" + _cacheServerLifeCycle + "].", e);
                    throw e;
                }
                _cacheMonitor.log(INFO, MonitorCategory.CACHE_SERVER, "Starting the MatsEagerCacheServer"
                        + " for data [" + _dataName + "].");
                _cacheServerLifeCycle = CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY;
            }

            // Create thread that checks if we actually can request the Source Data
            Thread checkThread = new Thread(() -> {
                // We'll keep trying until we succeed.
                long sleepTimeBetweenAttempts = 2000;
                while (_cacheServerLifeCycle == CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY ||
                        _cacheServerLifeCycle == CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA) {
                    // Try to get the data from the source provider.
                    try {
                        _cacheMonitor.log(INFO, MonitorCategory.INITIAL_POPULATION,
                                "Asserting that we can get Source Data.");
                        DataResult result = _produceDataResult(_fullDataCallbackSupplier);
                        _cacheMonitor.log(INFO, MonitorCategory.INITIAL_POPULATION,
                                "Success: We asserted that we can get Source Data! Data count:["
                                        + result.dataCountFromSourceProvider + "]");

                        // Start the endpoints
                        _createCacheEndpointsAndStartPeriodicRefresh();

                        // We're now running.
                        _cacheStartedTimestamp = System.currentTimeMillis();
                        _cacheServerLifeCycle = CacheServerLifeCycle.RUNNING;
                        _waitForRunningLatch.countDown();
                        _waitForRunningLatch = null; // fast-path check, and get rid of the CountDownLatch.
                        // We're done - it is possible to get source data.
                        break;
                    }
                    catch (Throwable t) {
                        _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION,
                                "Got exception while trying to assert that we could call the source provider and get"
                                        + " data. Will keep trying.", t);
                        _cacheServerLifeCycle = CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA;
                    }
                    // Wait a bit before trying again.
                    try {
                        Thread.sleep(sleepTimeBetweenAttempts);
                    }
                    catch (InterruptedException e) {
                        _cacheMonitor.exception(MonitorCategory.INITIAL_POPULATION,
                                "Got interrupted while waiting for initial population to be done.", e);
                        // NOTE: The _running flag will be checked in the next iteration.
                    }
                    // Increase sleep time between attempts, but cap it at 30 seconds.
                    sleepTimeBetweenAttempts = (long) Math.min(MAX_INTERVAL_BETWEEN_STARTUP_ATTEMPTS_MILLIS,
                            sleepTimeBetweenAttempts * 1.5);
                }
            }, "MatsEagerCacheServer." + _dataName + "-InitialPopulationCheck");
            checkThread.setDaemon(true);
            checkThread.start();
        }

        @Override
        public void startAndWaitForReceiving() {
            start();
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
        }

        @Override
        public void close() {
            _cacheMonitor.log(INFO, MonitorCategory.CACHE_SERVER, "Closing down the MatsEagerCacheServer"
                    + " for data [" + _dataName + "].");
            // Stop the executor anyway.
            _produceAndSendExecutor.shutdown();
            synchronized (this) {
                // ?: Are we running? Note: we accept multiple close() invocations, as the close-part is harder to
                // lifecycle
                // manage than the start-part (It might e.g. be closed by Spring too, in addition to by the user).
                if (!EnumSet.of(CacheServerLifeCycle.RUNNING,
                        CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY,
                        CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA)
                        .contains(_cacheServerLifeCycle)) {
                    // -> No, we are not running, so this is no-op.
                    return;
                }
                _cacheServerLifeCycle = CacheServerLifeCycle.STOPPING;
            }

            // -> Yes, we are RUNNING, so close down as asked.
            try {
                if (_periodicUpdate != null) {
                    _periodicUpdate.stop();
                }
                if (_broadcastTerminator != null) {
                    _broadcastTerminator.remove(30_000);
                }
                if (_requestTerminator != null) {
                    _requestTerminator.remove(30_000);
                }
            }
            finally {
                // Set final state to STOPPED
                synchronized (this) {
                    _cacheServerLifeCycle = CacheServerLifeCycle.STOPPED;
                }
            }
        }

        @Override
        public CacheServerInformation getCacheServerInformation() {
            return _cacheServerInformation;
        }

        // ======== Implementation / Internal methods ========

        void _setDelays(int shortDelay, int longDelay) {
            _shortDelay = shortDelay;
            _longDelay = longDelay;
        }

        static String _getCacheRequestQueue(String dataName) {
            return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
        }

        static String _getBroadcastTopic(String dataName) {
            return "mats.MatsEagerCache." + dataName + ".Broadcast";
        }

        private void _createCacheEndpointsAndStartPeriodicRefresh() {
            // ::: Create the Mats endpoints
            // :: The endpoint that the clients will send requests to
            // Note: We set concurrency to 1. We have this anti-thundering-herd mechanism whereby if many clients
            // request updates at the same time, we will only send one update satisfying them all. There is a mechanism
            // to handle this across multiple cache servers, whereby we broadcast "I'm going to do it". But having
            // multiple stage processors for the cache request terminator on each node makes no sense.
            _requestTerminator = _matsFactory.terminator(_getCacheRequestQueue(_dataName), void.class,
                    CacheRequestDto.class,
                    endpointConfig -> endpointConfig.setConcurrency(1), MatsFactory.NO_CONFIG,
                    (ctx, state, msg) -> _scheduleFullUpdateFromClient(msg));

            // :: Listener to the update topic.
            // To be able to see that a sibling has sent an update, we need to listen to the broadcast topic for the
            // updates. This enables us to not send an update if a sibling has already sent one.
            // This is also the topic which will be used by the siblings to send commands to each other (which the
            // clients will ignore). It is a pretty hard negative to receiving the actual updates on the servers, which
            // can be large - even though it already has the data. However, we won't have to decompress/deserialize the
            // data, so the hit won't be that big. The obvious alternative is to have a separate topic for the commands,
            // but that would pollute the MQ Destination namespace with one extra topic per cache.
            _broadcastTerminator = _matsFactory.subscriptionTerminator(_getBroadcastTopic(_dataName), void.class,
                    BroadcastDto.class, (ctx, state, broadcastDto) -> {
                        _cacheMonitor.log(DEBUG, MonitorCategory.RECEIVED_BROADCAST, "Got a broadcast: "
                                + broadcastDto.command
                                + " ## " + _infoAboutBroadcast(broadcastDto));
                        // ?: Is this a sibling command?
                        if (BroadcastDto.COMMAND_SIBLING_COMMAND.equals(broadcastDto.command)) {
                            _handleSiblingCommand(ctx, broadcastDto);
                        }
                        // ?: Is this the internal "sync between siblings" about having received a request for update?
                        else if (BroadcastDto.COMMAND_REQUEST_CLIENT_BOOT.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_PERIODIC.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_ENSURER_TRIGGERED.equals(broadcastDto.command)) {
                            _msg_fullUpdateRequestReceived(broadcastDto);
                        }
                        // ?: Is this the internal "sync between siblings" about now sending the update?
                        else if (BroadcastDto.COMMAND_REQUEST_SENDING.equals(broadcastDto.command)) {
                            _msg_fullUpdateRequestSendUpdateNow(broadcastDto);
                        }
                        // ?: Is this the actual update sent to the clients - which we also get?
                        else if (BroadcastDto.COMMAND_UPDATE_FULL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_UPDATE_PARTIAL.equals(broadcastDto.command)) {
                            // -> This is a broadcast of a cache update (primarly meant for the clients).
                            // :: Jot down that the clients were sent an update, used when calculating the delays, and
                            // in the health check.
                            _lastAnyUpdateReceivedTimestamp = System.currentTimeMillis();
                            // ?: Is this a full update?
                            if (broadcastDto.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)) {
                                // -> Yes, this was a full update, so record the timestamp, and count.
                                _lastFullUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
                                _numberOfFullUpdatesReceived.incrementAndGet();
                            }
                            else {
                                // -> No, this was a partial update, so record the timestamp, and count.
                                _lastPartialUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
                                _numberOfPartialUpdatesReceived.incrementAndGet();
                            }
                            // :: If we have any linked clients, we should send the update to them.
                            if (_cacheClients != null) {
                                _cacheClients.forEach(client -> client.processLambdaForSubscriptionTerminator(ctx,
                                        state, broadcastDto));
                            }
                        }
                        else {
                            _cacheMonitor.exception(MonitorCategory.OTHER,
                                    "Got a broadcast with unknown command, ignoring: "
                                            + _infoAboutBroadcast(broadcastDto),
                                    new IllegalArgumentException("Unknown broadcast command, shouldn't happen."));
                        }
                    });

            _periodicUpdate = new PeriodicUpdate();
        }

        void _registerForwardToClient(MatsEagerCacheClientImpl<?> client) {
            // :: Do some assertions wrt. linking the server and client
            if (!client.getCacheClientInformation().getDataName().equals(_dataName)) {
                throw new IllegalStateException("The MatsEagerCacheClient is for data ["
                        + client.getCacheClientInformation().getDataName()
                        + "], while this MatsEagerCacheServer is for data [" + _dataName + "]!");
            }
            if (!client.getCacheClientInformation().getNodename().equals(_nodename)) {
                throw new IllegalStateException("The MatsEagerCacheClient is for nodename ["
                        + client.getCacheClientInformation().getNodename()
                        + "], while this MatsEagerCacheServer is for nodename [" + _nodename + "]!");
            }
            synchronized (this) {
                if (_cacheClients == null) {
                    _cacheClients = new CopyOnWriteArrayList<>(); // COWAL since we do not sync on reading.
                }
                _cacheClients.add(client);
            }
        }

        private class PeriodicUpdate {
            private final Thread _thread;
            private volatile boolean _running;

            private PeriodicUpdate() {
                if (_periodicFullUpdateIntervalMinutes == 0) {
                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE,
                            "Periodic update: Periodic update is set to 0, i.e. never, not starting thread.");
                    _thread = null;
                    return;
                }
                _running = true;
                long intervalMillis = (long) (_periodicFullUpdateIntervalMinutes * 60_000);
                // The check interval is 10% of the interval, but at most 5 minutes.
                long checkIntervalCalcMillis = Math.min(intervalMillis / 10, 5 * 60_000);
                // Add a random part to the check interval, to avoid all servers checking at the same time.
                long checkIntervalMillis = checkIntervalCalcMillis
                        + ThreadLocalRandom.current().nextLong(checkIntervalCalcMillis / 4);

                _thread = new Thread(() -> {
                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Thread started."
                            + " interval: [" + _periodicFullUpdateIntervalMinutes + " min] => ["
                            + String.format("%,d", intervalMillis) + " ms], check interval: ["
                            + String.format("%,d", checkIntervalMillis) + " ms, "
                            + _formatMillis(checkIntervalMillis) + "].");
                    // Ensure that we're fully operational before starting the periodic update.
                    _broadcastTerminator.waitForReceiving(FAST_RESPONSE_LAST_RECV_THRESHOLD_MILLIS);
                    // Going into the run loop
                    while (_running) {
                        try {
                            /*
                             * The main goal here is to avoid the situation where all servers start producing periodic
                             * updates at the same time, or near the same time, which would result unnecessary load on
                             * every component, and if the source data is retrieved from a database, the DB will be
                             * loaded at the same time from all servers.
                             * 
                             * We ideally want *one* update per periodic interval, even though we have multiple
                             * instances of the service running (aka. "siblings").
                             *
                             * The idea is to have a check interval, which is a fraction plus a bit of randomness of the
                             * interval between the periodic updates. We repeatedly sleep the check interval, and then
                             * check whether the last full update is longer ago than the periodic update interval. If we
                             * haven't received a full update within the period update interval, we should do a full
                             * update. Since there is some randomness in the check interval, we hopefully avoid that all
                             * servers check at the same time. The one that is first to see that it is time for a full
                             * update, will send a broadcast message to the siblings to get the process going, which
                             * will lead to a new full update being produced and broadcast - which the siblings also
                             * receive, and record the timestamp of. When the other siblings wake up from their check
                             * interval sleep, and see that a full update has arrived, they'll see that there's nothing
                             * to, and they'll just continue their check loop.
                             *
                             * The "thundering herd avoidance" solution we have should mitigate the problem if they come
                             * very close to each other. However, if they come a bit more spaced out, AND it takes a
                             * long time to produce the update, we might not catch it with this solution. This since the
                             * max "coalescing and election" sleep is just a few seconds, which if the update takes e.g.
                             * tens of seconds to produce and send will lead the second guy to wake up also wanting to
                             * do a full update. Thus, if we see that we haven't gotten an update in the interval, we
                             * additionally also check whether a full update request have come in within the periodic
                             * update interval (as this is recorded close to immediately) - assuming then that it was
                             * started by another of the siblings, and if so, we wait one more interval before checking
                             * again - hopefully the update will have come in after this.
                             * 
                             * Finally: It doesn't matter all that much if this doesn't always work out perfectly, and
                             * we expend a bit more resources than necessary. It is better with an update too many than
                             * an update too few.
                             */
                            Thread.sleep(checkIntervalMillis);
                            // ?: Have we received a full update within the interval?
                            if (Math.max(_lastFullUpdateReceivedTimestamp, _cacheStartedTimestamp) > System
                                    .currentTimeMillis() - intervalMillis) {
                                // -> We've received a full update within the interval, so we don't need to do anything.
                                continue;
                            }
                            // E-> We haven't received a full update within the interval, so we should do a full update.

                            // :: However, if we're currently in the process of producing an update, we can chill a bit
                            // more, as this hopefully means that we'll soon get the update.
                            if (Math.max(_lastFullUpdateRequestReceivedTimestamp, _cacheStartedTimestamp) > System
                                    .currentTimeMillis() - intervalMillis) {
                                // -> We're currently producing an update, so we'll wait one more interval.
                                _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "We're currently"
                                        + " producing an update, so we'll wait one more interval. We are: ["
                                        + _dataName + "] " + _nodename);
                                // Sleep one more interval - but in testing, the interval can be very short, so we'll
                                // minimum sleep the coalescing "long delay" to not fire twice.
                                Thread.sleep(Math.max(checkIntervalMillis, _longDelay + 500));
                                // ?: Have we received a full update within the interval now?
                                if (Math.max(_lastFullUpdateReceivedTimestamp, _cacheStartedTimestamp) > System
                                        .currentTimeMillis() - intervalMillis) {
                                    // -> We've received a full update within the interval, so we don't need to do
                                    // anything.
                                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "After having"
                                            + " checked again, we find that it is not needed. We are: [" + _dataName
                                            + "] "
                                            + _nodename);
                                    continue;
                                }
                            }
                            // E-> So, we should request a full update. If this now comes in at the same time as another
                            // server, the "thundering herd avoidance" solution should mitigate double production.
                            _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE,
                                    "Periodic update interval exceeded:"
                                            + " Issuing request for full update. We are: [" + _dataName + "] "
                                            + _nodename);
                            _scheduleFullUpdateFromPeriodic();
                        }
                        catch (InterruptedException e) {
                            // We're probably shutting down.
                            _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Thread interrupted,"
                                    + " probably shutting down. We are: [" + _dataName + "] " + _nodename);
                        }
                        catch (Throwable t) {
                            _cacheMonitor.exception(MonitorCategory.PERIODIC_UPDATE,
                                    "Periodic update: Got exception while trying to schedule periodic update."
                                            + " Ignoring. We are: [" + _dataName + "] "
                                            + _nodename, t);
                        }
                    }
                }, "MatsEagerCacheServer." + _dataName + ".PeriodicUpdate[" + _periodicFullUpdateIntervalMinutes
                        + "min]");
                _thread.setDaemon(true);
                _thread.start();
            }

            private void stop() {
                if (_thread == null) {
                    return;
                }
                _running = false;
                _thread.interrupt();
            }
        }

        private void _scheduleFullUpdateFromPeriodic() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_PERIODIC, "Phase 0: Request for full update"
                    + " for periodic refresh (on this node!) [" + _nodename + "], broadcasting to Phase 1.");
            BroadcastDto broadcast = new BroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_PERIODIC,
                    _nodename);
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId(TraceId.create("MatsEagerCache."
                    + _dataName, "ScheduleFullUpdate")
                    .add("from", "Server")
                    .add("node", _matsFactory.getFactoryConfig().getNodename()))
                    .from("MatsEagerCache." + _dataName + ".ScheduleFullUpdateFromServer")
                    .to(_getBroadcastTopic(_dataName))
                    .publish(broadcast));
        }

        private void _scheduleFullUpdateFromServer() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_FROM_SERVER, "Phase 0: Request for full update"
                    + " on server (on this node!) [" + _nodename + "], broadcasting to Phase 1.");
            // Ensure that we are running
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
            // :: Create and send the broadcast message
            BroadcastDto broadcast = new BroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL,
                    _nodename);
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId(TraceId.create("MatsEagerCache."
                    + _dataName, "ScheduleFullUpdate")
                    .add("from", "Server")
                    .add("node", _matsFactory.getFactoryConfig().getNodename()))
                    .from("MatsEagerCache." + _dataName + ".ScheduleFullUpdateFromServer")
                    .to(_getBroadcastTopic(_dataName))
                    .publish(broadcast));
        }

        /**
         * We use the broadcast channel to sequence the incoming requests for updates, and to perform a leader election
         * of who shall do the update - the "stepping" is done with the "_msg_*" methods below.
         */
        private void _scheduleFullUpdateFromClient(CacheRequestDto incomingClientCacheRequest) {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_FROM_CLIENT, "Phase 0: Request for full update"
                    + " from client: " + incomingClientCacheRequest.nodename + ", broadcasting to Phase 1."
                    + " Command: " + incomingClientCacheRequest.command
                    + " - current Outstanding: [" + _updateRequest_OutstandingCount + "]");

            String command = incomingClientCacheRequest.command;

            if (!(CacheRequestDto.COMMAND_REQUEST_BOOT.equals(command)
                    || CacheRequestDto.COMMAND_REQUEST_MANUAL.equals(command))) {
                _cacheMonitor.exception(MonitorCategory.REQUEST_UPDATE_FROM_CLIENT,
                        "Got a CacheRequest with unknown command [" + command + "], ignoring: "
                                + incomingClientCacheRequest,
                        new IllegalArgumentException("Unknown command in CacheRequest"));
                return;
            }

            // :: Send a broadcast message about next step, that we ourselves also will get.

            // Find command type
            boolean manual = CacheRequestDto.COMMAND_REQUEST_MANUAL.equals(command);
            String updateRequestCommand = manual
                    ? BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL
                    : BroadcastDto.COMMAND_REQUEST_CLIENT_BOOT;

            BroadcastDto broadcast = new BroadcastDto(updateRequestCommand, _nodename);
            // Copy over the correlationId, nodename, and timestamps.
            broadcast.correlationId = incomingClientCacheRequest.correlationId;
            broadcast.requestNodename = incomingClientCacheRequest.nodename;
            broadcast.requestSentTimestamp = incomingClientCacheRequest.sentTimestamp;
            broadcast.requestSentNanoTime = incomingClientCacheRequest.sentNanoTime;
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId(TraceId.create("MatsEagerCache."
                    + _dataName, "ScheduleFullUpdate")
                    .add("from", "Client")
                    .add("node", incomingClientCacheRequest.nodename)
                    .add("type", manual ? "Manual" : "Boot"))
                    .from("MatsEagerCache." + _dataName + ".ScheduleFullUpdateFromClient")
                    .to(_getBroadcastTopic(_dataName))
                    .publish(broadcast));
        }

        private void _msg_fullUpdateRequestReceived(BroadcastDto broadcastDto) {
            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE, "Phase 1: Coalesce and elect leader."
                    + " Command: " + broadcastDto.command + " - from: " + broadcastDto.handlingNodename
                    + " - " + _infoAboutBroadcast(broadcastDto));

            // Record that we've received a request for update.
            _lastFullUpdateRequestReceivedTimestamp = System.currentTimeMillis();

            boolean shouldStartCoalescingThread = false;
            synchronized (this) {
                // Increase the count of outstanding requests.
                _updateRequest_OutstandingCount++;
                // ?: Was this the initial message that pushed the count to 1?
                if (_updateRequest_OutstandingCount == 1) {
                    // -> Yes, this was the first one, so we should start the coalescing thread.
                    shouldStartCoalescingThread = true;
                    // We start by proposing this first message's node as the handling node.
                    _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE, "First message of this round,"
                            + " proposing sending node as handling node: " + broadcastDto.sentNodename);
                    _updateRequest_HandlingNodename = broadcastDto.sentNodename;
                }
                else {
                    // -> No, this was not the first message of this round.
                    // ?: Check if the new message was initiated by a lower nodename than the one we have.
                    if (broadcastDto.sentNodename.compareTo(_updateRequest_HandlingNodename) < 0) {
                        // -> Yes, this one is lower, so we'll take this one.
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE,
                                "New proposed leader with lower"
                                        + " nodename. New: [" + broadcastDto.sentNodename + "] (..is lower than '"
                                        + _updateRequest_HandlingNodename + "')");
                        _updateRequest_HandlingNodename = broadcastDto.sentNodename;
                    }
                    else {
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE,
                                "Keep existing proposed leader,"
                                        + " since new suggestion isn't lower. Keeping: ["
                                        + _updateRequest_HandlingNodename
                                        + "] (..is lower than '" + broadcastDto.sentNodename + "')");
                    }
                }
            }

            /*
             * Brute-force solution at ensuring that if the responsible node doesn't manage to send the update (e.g.
             * crashes, boots, redeploys), someone else will: Make a thread on ALL nodes that in some minutes will check
             * if we've received a full update after this point in time, and if not, it will initiate a new full update
             * to try to remedy the situation.
             */

            // Adjust the time to check based on situation: If we're currently making a source data set (indicating that
            // we're effectively always producing updates), or having problems creating source data, or if this request
            // for full update was triggered by a triggered ensurer, we'll wait longer.
            int waitTime = _currentlyMakingSourceDataResult || _currentlyHavingProblemsCreatingSourceDataResult
                    || BroadcastDto.COMMAND_REQUEST_ENSURER_TRIGGERED.equals(broadcastDto.command)
                            ? ENSURER_WAIT_TIME_LONG_MILLIS
                            : ENSURER_WAIT_TIME_SHORT_MILLIS;
            // Record the time when this ensurer started.
            long timestampWhenEnsurerStarted = _lastFullUpdateRequestReceivedTimestamp;
            // Create the thread. There might be a few of these hanging around, but they are "no-ops" if the update is
            // performed. We could have a more sophisticated solution where we cancel any such ensurer thread if we
            // receive an update, but this will work just fine.
            Thread ensurerThread = new Thread(() -> {
                // Do the sleep
                _takeNap(waitTime);
                // ?: Have we received a full update since we started this ensurer?
                if (_lastFullUpdateReceivedTimestamp < timestampWhenEnsurerStarted) {
                    // -> No, we have not seen the full update yet, which is bad. Initiate a new full update.
                    _cacheMonitor.log(WARN, MonitorCategory.ENSURE_UPDATE, "Ensurer triggered: We have not seen"
                            + " the full update yet, initiating a new full update. We are node: " + _nodename);
                    // Note: This is effectively a message to this same handling method.
                    BroadcastDto broadcast = new BroadcastDto(BroadcastDto.COMMAND_REQUEST_ENSURER_TRIGGERED,
                            _nodename);
                    _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                        init.traceId(TraceId.create("MatsEagerCache." + _dataName, "FullUpdateEnsurer"))
                                .from("MatsEagerCache." + _dataName + ".FullUpdateEnsurer")
                                .to(_getBroadcastTopic(_dataName))
                                .publish(broadcast);
                    });
                }
                else {
                    // -> Yes, we have seen the full update, so we're happy.
                    _cacheMonitor.log(DEBUG, MonitorCategory.ENSURE_UPDATE, "Ensurer OK: There have been a full update"
                            + " since we started this ensurer, thus we're happy: No need to initiate a new full update."
                            + " We are node: " + _nodename);
                }
            }, "MatsEagerCacheServer." + _dataName + "-EnsureDataIsSentEventually[" + waitTime + "ms]");
            ensurerThread.setDaemon(true);
            ensurerThread.start();

            // ?: Should we start the election and coalescing thread?
            if (shouldStartCoalescingThread) {
                // -> Yes, this was the first message of this round, so we should start the coalescing thread.

                // The delay-stuff is both to find who should do the update (leader election), and to handle the
                // "thundering herd" problem, where all clients request a full update at the same time - which otherwise
                // would result in a lot of full updates being created and sent in parallel.

                _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE,
                        "Starting election and coalescing thread."
                                + " We must find who should lead this, and also coalesce any more incoming requests."
                                + " We will wait for a while, and then see if we've won. We are node: " + _nodename
                                + ", current proposed leader: " + _updateRequest_HandlingNodename);

                // "If it is a long time since last invocation, it will be scheduled to run soon, while if the previous
                // time was a short time ago, it will be scheduled to run a bit later (~ within 7 seconds)."

                // First find the latest time anything wrt. an update happened.
                long latestActivityTimestamp = Collections.max(Arrays.asList(_cacheStartedTimestamp,
                        _lastFullUpdateRequestReceivedTimestamp, _lastFullUpdateProductionStartedTimestamp,
                        _lastAnyUpdateReceivedTimestamp));
                // NOTE: If it is a *long* time since last activity, we'll do a fast response.
                boolean fastResponse = (System.currentTimeMillis()
                        - latestActivityTimestamp) > FAST_RESPONSE_LAST_RECV_THRESHOLD_MILLIS;
                // Calculate initial delay: Two tiers: If "fastResponse", decide by whether it was a manual request or
                // not.
                int initialDelay = fastResponse
                        ? BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL.equals(broadcastDto.command)
                                        ? 0 // Immediate for manual
                                        : _shortDelay // Short delay (i.e. longer) for boot
                        : _longDelay;

                Thread updateRequestsCoalescingDelayThread = new Thread(() -> {
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
                            // -> Yes, more requests have come in, so we'll do the long delay anyway, to see if we can
                            // coalesce even more requests.
                            _takeNap(_longDelay - _shortDelay);
                        }
                    }

                    // ----- Okay, we've waited for more requests, now we'll initiate the update.

                    String updateRequest_HandlingNodename;
                    synchronized (this) {
                        updateRequest_HandlingNodename = _updateRequest_HandlingNodename;
                    }

                    // ?: Are we the one that should handle the update?
                    if (_nodename.equals(updateRequest_HandlingNodename)) {
                        // -> Yes, it is us that should handle the update.
                        // We also do this over broadcast, so that all siblings can see that we're doing it.

                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE,
                                "Coalesced enough! WE'RE ELECTED!"
                                        + " We waited for more requests, and we ended up as elected leader. We will now broadcast"
                                        + " to Phase 2 that handling nodename is us [" + _nodename
                                        + "]. (currentOutstanding: ["
                                        + _updateRequest_OutstandingCount + "]).");

                        BroadcastDto broadcast = new BroadcastDto(BroadcastDto.COMMAND_REQUEST_SENDING, _nodename);
                        broadcast.handlingNodename = _nodename; // It is us that is handling it.
                        // Transfer the correlationId and requestNodename from the incoming message (they might be null)
                        broadcast.correlationId = broadcastDto.correlationId;
                        broadcast.requestNodename = broadcastDto.requestNodename;
                        broadcast.requestSentTimestamp = broadcastDto.requestSentTimestamp;
                        broadcast.requestSentNanoTime = broadcastDto.requestSentNanoTime;

                        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                            init.traceId(TraceId.create("MatsEagerCache." + _dataName, "UpdateRequestsCoalesced"))
                                    .from("MatsEagerCache." + _dataName + ".UpdateRequestsCoalesced")
                                    .to(_getBroadcastTopic(_dataName))
                                    .publish(broadcast);
                        });
                    }
                    else {
                        // -> No, it is not us that should handle the update.
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_COALESCE, "Coalesced enough! We lost -"
                                + " We waited for more requests, and someone else were elected: ["
                                + updateRequest_HandlingNodename + "]. We will thus not broadcast for next phase."
                                + " We are " + _nodename + " (currentOutstanding: [" + _updateRequest_OutstandingCount
                                + "]).");
                    }
                }, "MatsEagerCacheServer." + _dataName + "-UpdateRequestsCoalescingDelay");
                updateRequestsCoalescingDelayThread.setDaemon(true);
                updateRequestsCoalescingDelayThread.start();
            }
        }

        private void _msg_fullUpdateRequestSendUpdateNow(BroadcastDto broadcastDto) {
            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_UPDATE_SEND_NOW, "Phase 2: Leader sends update."
                    + " Command: " + broadcastDto.command + " - from: " + broadcastDto.handlingNodename
                    + " - " + _infoAboutBroadcast(broadcastDto));

            // A full-update will be sent now, make note (for fastResponse evaluation, and )
            _lastFullUpdateProductionStartedTimestamp = System.currentTimeMillis();

            // Reset count - after which a new request for update will start the election and coalescing process anew.
            synchronized (this) {
                _updateRequest_OutstandingCount = 0;
                _updateRequest_HandlingNodename = null;
            }

            // ?: Is it us that should handle the actual broadcast of update?
            // .. AND is there no other update in the queue? (If there are, that task will already send most recent
            // data)
            if (_nodename.equals(broadcastDto.handlingNodename)
                    && _produceAndSendExecutor.getQueue().isEmpty()) {
                // -> Yes it was us, and there are no other updates already in the queue.
                _produceAndSendExecutor.execute(() -> {
                    _cacheMonitor.log(INFO, MonitorCategory.SEND_UPDATE, "We are the elected node, and thus"
                            + " we now produce and send full update! [" + _nodename
                            + "] (currentOutstanding: [" + _updateRequest_OutstandingCount + "])");

                    try {
                        _produceAndSendUpdate(broadcastDto, _fullDataCallbackSupplier, true);
                    }
                    catch (Throwable t) {
                        // We catch all problems instead of letting it propagate, as we're in an Executor, and we don't
                        // want to kill the thread, but instead log this and let the HealthCheck catch it.
                        _cacheMonitor.exception(MonitorCategory.REQUEST_UPDATE_SEND_NOW,
                                "Got exception while trying to produce and send full update. Not good at all.", t);
                    }
                });
            }
        }

        private void _produceAndSendUpdate(BroadcastDto incomingBroadcastDto,
                Supplier<CacheDataCallback<?>> dataCallbackSupplier, boolean fullUpdate) {
            try {
                _produceAndSendUpdateLock.lock();

                // Create the SourceDataResult by asking the source provider for the data.
                DataResult result = _produceDataResult(dataCallbackSupplier);

                _lastFullUpdateProduceTotalMillis = result.millisTotal;

                _lastUpdateWasFull = fullUpdate;
                _lastUpdateProduceTotalMillis = result.millisTotal;
                _lastUpdateSourceMillis = result.millisSource;
                _lastUpdateSerializeMillis = result.millisSerialize;
                _lastUpdateCompressMillis = result.millisCompress;

                _lastUpdateCompressedSize = result.compressedSize;
                _lastUpdateUncompressedSize = result.uncompressedSize;
                _lastUpdateCount = result.dataCountFromSourceProvider;
                _lastUpdateMetadata = result.metadata;
                _lastUpdateCompressMillis = result.millisCompress;

                // :: Create the Broadcast message (which doesn't contain the actual data, as that is sideloaded).
                String updateCommand = fullUpdate ? BroadcastDto.COMMAND_UPDATE_FULL
                        : BroadcastDto.COMMAND_UPDATE_PARTIAL;
                BroadcastDto broadcast = new BroadcastDto(updateCommand, _nodename);
                broadcast.dataCount = result.dataCountFromSourceProvider;
                broadcast.compressedSize = result.compressedSize;
                broadcast.uncompressedSize = result.uncompressedSize;
                broadcast.metadata = result.metadata;
                broadcast.millisTotalProduceAndCompress = result.millisTotal;
                broadcast.millisCompress = result.millisCompress;
                // Transfer the correlationId and requestNodename from the incoming message, if present.
                if (incomingBroadcastDto != null) {
                    broadcast.correlationId = incomingBroadcastDto.correlationId;
                    broadcast.requestNodename = incomingBroadcastDto.requestNodename;
                    broadcast.requestSentTimestamp = incomingBroadcastDto.requestSentTimestamp;
                    broadcast.requestSentNanoTime = incomingBroadcastDto.requestSentNanoTime;
                }

                String type = fullUpdate ? "Full" : "Partial";

                long lastUpdateSent = System.currentTimeMillis();
                _lastUpdateSent = lastUpdateSent;

                if (fullUpdate) {
                    _lastFullUpdateSentTimestamp = lastUpdateSent;
                    _numberOfFullUpdatesSent.incrementAndGet();
                }
                else {
                    _numberOfPartialUpdatesSent.incrementAndGet();
                }

                // :: Send the broadcast message, with the data sideloaded.
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

        private String _infoAboutBroadcast(BroadcastDto broadcastDto) {
            return "us: " + _nodename + "command: " + broadcastDto.command
                    + ", sentNode: " + broadcastDto.sentNodename
                    + (_nodename.equals(broadcastDto.sentNodename) ? " (+SENT+ FROM US!)" : " (Not us!)")
                    + ", handlingNode: " + broadcastDto.handlingNodename
                    + (_nodename.equals(broadcastDto.handlingNodename) ? " (+HANDLED+ BY US!)" : " (Not us!)")
                    + ", currentOutstanding: " + _updateRequest_OutstandingCount
                    + ", correlationId: " + broadcastDto.correlationId + ", requestNodename: "
                    + broadcastDto.requestNodename;
        }

        private static void _takeNap(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                log.warn(LOG_PREFIX + "Got interrupted while taking nap.", e);
                throw new IllegalStateException("Got interrupted while taking nap, unexpected.", e);
            }
        }

        private void _handleSiblingCommand(ProcessContext<Void> ctx, BroadcastDto broadcastDto) {
            byte[] bytes = ctx.getBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES);
            MatsEagerCacheServer.SiblingCommand siblingCommand = new MatsEagerCacheServer.SiblingCommand() {
                @Override
                public boolean originatedOnThisInstance() {
                    return broadcastDto.sentNodename.equals(_nodename);
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
                    _cacheMonitor.exception(MonitorCategory.SIBLING_COMMAND,
                            "Got exception from SiblingCommandEventListener ["
                                    + listener + "], ignoring.", t);
                }
            }
        }

        void _waitForReceiving(int maxWaitSeconds) {
            if (!EnumSet.of(CacheServerLifeCycle.NOT_YET_STARTED,
                    CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY,
                    CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA,
                    CacheServerLifeCycle.RUNNING).contains(_cacheServerLifeCycle)) {
                throw new IllegalStateException("The MatsEagerCacheServer is not NOT_YET_STARTED, STARTING_*"
                        + " or RUNNING, it is [" + _cacheServerLifeCycle + "].");
            }
            try {
                // If the latch is there, we'll wait for it. (fast-path check for null)
                CountDownLatch latch = _waitForRunningLatch;
                if (latch != null) {
                    boolean started = latch.await(maxWaitSeconds, TimeUnit.SECONDS);
                    if (!started) {
                        throw new IllegalStateException("Did not start within " + maxWaitSeconds + " seconds.");
                    }
                }
            }
            catch (InterruptedException e) {
                throw new IllegalStateException("Got interrupted while waiting for the cache server to start.", e);
            }
            // :: Wait for the Broadcast SubscriptionTerminator and Request Terminator to start.
            boolean broadcastStarted = _broadcastTerminator.waitForReceiving(maxWaitSeconds * 1_000);
            if (!broadcastStarted) {
                throw new IllegalStateException("Broadcast SubscriptionTerminator did not start within "
                        + maxWaitSeconds + " seconds.");
            }
            boolean requestStarted = _requestTerminator.waitForReceiving(maxWaitSeconds * 1_000);
            if (!requestStarted) {
                throw new IllegalStateException("Request Terminator did not start within "
                        + maxWaitSeconds + " seconds.");
            }
        }

        /**
         * Static method to format a long representing bytes into a human-readable string. Using the IEC standard, which
         * uses B, KiB, MiB, GiB, TiB. E.g. 1024 bytes is 1 KiB, 1024 KiB is 1 MiB, etc. It formats with 2 decimals.
         */
        static String _formatBytes(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            double kb = bytes / 1024d;
            if (kb < 1024) {
                return String.format("%.2f KiB", kb);
            }
            double mb = kb / 1024d;
            if (mb < 1024) {
                return String.format("%.2f MiB", mb);
            }
            double gb = mb / 1024d;
            if (gb < 1024) {
                return String.format("%.2f GiB", gb);
            }
            double tb = gb / 1024d;
            return String.format("%.2f TiB", tb);
        }

        /**
         * Static method formatting a double representing duration in milliseconds into a human-readable string. It will
         * format into hours, minutes, seconds and milliseconds, with the highest unit that is non-zero, and with 3
         * decimals if milliseconds only, and 2 decimals if seconds, and no decimals if minutes or hours.
         * <p>
         * Examples: "950.123 ms", "23.45s", "12m 34s", "1h 23m".
         */
        static String _formatMillis(double millis) {
            if (millis < 10) {
                return String.format("%.3f ms", millis);
            }
            if (millis < 100) {
                return String.format("%.2f ms", millis);
            }
            if (millis < 1000) {
                return String.format("%.1f ms", millis);
            }
            double seconds = millis / 1000d;
            if (seconds < 60) {
                return String.format("%.2f s", seconds);
            }
            long minutes = (long) (seconds / 60);
            if (minutes < 60) {
                return String.format("%dm %ds", minutes, (long) (seconds % 60));
            }
            long hours = minutes / 60;
            return String.format("%dh %dm", hours, minutes % 60);
        }

        private static final DateTimeFormatter ISO8601_FORMATTER = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss.SSS");

        /**
         * Static method that formats a millis-since-epoch timestamp into a human-readable string, with the format
         * "yyyy-MM-dd HH:mm:ss.SSS".
         */
        static String _formatTimestamp(long millis) {
            return Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .format(ISO8601_FORMATTER);
        }

        static String _formatHtmlTimestamp(long millis) {
            if (millis <= 0) {
                return "<i>never</i>";
            }
            // Append how long time ago this was.
            long now = System.currentTimeMillis();
            long diff = now - millis;
            return "<b>" + _formatTimestamp(millis) + "</b> <i>(" + _formatMillis(diff) + " ago)</i>";
        }

        private static final NumberFormat NUMBER_FORMAT;

        static {
            NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.US);
            NUMBER_FORMAT.setGroupingUsed(true);
            NUMBER_FORMAT.setMinimumFractionDigits(0);
            NUMBER_FORMAT.setMaximumFractionDigits(0);
            DecimalFormatSymbols symbols = ((DecimalFormat) NUMBER_FORMAT).getDecimalFormatSymbols();
            symbols.setGroupingSeparator('\u202F'); // Thin non-breaking space
            ((DecimalFormat) NUMBER_FORMAT).setDecimalFormatSymbols(symbols);
        }

        static String _formatNiceBytes(long bytes) {
            // Format with thousand-separator bytes, then format as human-readable, same idea as _formatHtmlTimestamp
            return NUMBER_FORMAT.format(bytes) + " B (" + _formatBytes(bytes) + ")";
        }

        static String _formatHtmlBytes(long bytes) {
            // Format with thousand-separator bytes, then format as human-readable, same idea as _formatHtmlTimestamp
            return "<b>" + NUMBER_FORMAT.format(bytes) + " B</b> <i>(" + _formatBytes(bytes) + ")</i>";
        }

        private DataResult _produceDataResult(
                Supplier<CacheDataCallback<?>> dataCallbackSupplier) {
            _currentlyMakingSourceDataResult = true;
            CacheDataCallback<?> dataCallback = dataCallbackSupplier.get();
            // We checked these at construction time. We'll just have to live with the uncheckedness.
            @SuppressWarnings("unchecked")
            CacheDataCallback<Object> uncheckedDataCallback = (CacheDataCallback<Object>) dataCallback;

            long nanosAsStart_total = System.nanoTime();
            ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
            long[] nanosTaken_serializeAndCompress = new long[1];
            int[] dataCount = new int[1];
            try {
                // Make the Jackson SequenceWriter
                SequenceWriter jacksonSeq;
                try {
                    jacksonSeq = _sentDataTypeWriter.writeValues(out);
                }
                catch (IOException e) {
                    _currentlyHavingProblemsCreatingSourceDataResult = true;
                    _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA, "Got exception while trying to create Jackson"
                            + " SequenceWriter, which shouldn't happen, as we're writing to ByteArrayOutputStream.", e);
                    throw new RuntimeException(e);
                }
                // Create the consumer that writes to the Jackson SequenceWriter
                Consumer<Object> consumer = sent -> {
                    try {
                        long nanosStart = System.nanoTime();
                        jacksonSeq.write(sent);
                        nanosTaken_serializeAndCompress[0] += (System.nanoTime() - nanosStart);
                        // TODO: Log each entity's size to monitor and HealthCheck. (up to max 1_000 entities)
                    }
                    catch (IOException e) {
                        _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA, "Got IOException while writing to Jackson"
                                + " SequenceWriter, which shouldn't happen, as we're writing to ByteArrayOutputStream.",
                                e);
                        throw new RuntimeException(e);
                    }
                    dataCount[0]++;
                };
                // Invoke the callback to provide the data, providing the consumer backed by the Jackson SequenceWriter
                try {
                    uncheckedDataCallback.provideSourceData(consumer);
                }
                catch (Throwable t) {
                    _currentlyHavingProblemsCreatingSourceDataResult = true;
                    var msg = "Got exception while trying to produce data.";
                    _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA, msg, t);
                    throw new RuntimeException(msg, t);
                }
                // Close and finish the Jackson SequenceWriter
                try {
                    jacksonSeq.close();
                }
                catch (IOException e) {
                    _currentlyHavingProblemsCreatingSourceDataResult = true;
                    _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA,
                            "Got exception when closing Jackson SequenceWriter"
                                    + ", which shouldn't happen, as we're writing to ByteArrayOutputStream.", e);
                    throw new RuntimeException(e);
                }
                _currentlyHavingProblemsCreatingSourceDataResult = false;
            }
            finally {
                _currentlyMakingSourceDataResult = false;
            }

            // Actual data count, not the guesstimate from the sourceCallback.
            int dataCountFromSourceProvider = dataCount[0];
            // Fetch the resulting byte array.
            byte[] byteArray = out.toByteArray();
            // Sizes in bytes
            assert byteArray.length == out.getCompressedBytesOutput()
                    : "The byte array length should be the same as the"
                            + " compressed size, but it was not. This is a bug.";
            int compressedSize = (int) out.getCompressedBytesOutput();
            long uncompressedSize = out.getUncompressedBytesInput();
            // Timings
            double millisTaken_total = (System.nanoTime() - nanosAsStart_total) / 1_000_000d;
            double millisTaken_SerializeAndCompress = nanosTaken_serializeAndCompress[0] / 1_000_000d;
            double millisTaken_Source = millisTaken_total - millisTaken_SerializeAndCompress;
            double millisTaken_Compress = out.getDeflateAndWriteTimeNanos() / 1_000_000d;
            double millisTaken_Serialize = millisTaken_SerializeAndCompress - millisTaken_Compress;
            // Metadata from the source provider
            String metadata = dataCallback.provideMetadata();
            return new DataResult(dataCountFromSourceProvider, byteArray,
                    compressedSize, uncompressedSize, metadata,
                    millisTaken_total, millisTaken_Source, millisTaken_Serialize, millisTaken_Compress);
        }

        private static class DataResult {
            public final int dataCountFromSourceProvider;
            public final byte[] byteArray;
            public final int compressedSize;
            public final long uncompressedSize;
            public final String metadata;
            public final double millisTotal; // Total time to produce the Data set
            public final double millisSource;
            public final double millisSerialize;
            public final double millisCompress; // Compress (and write to byte array, but that's ~0) only

            public DataResult(int dataCountFromSourceProvider, byte[] byteArray,
                    int compressedSize, long uncompressedSize, String metadata,
                    double millisTotal, double millisSource, double millisSerialize, double millisCompress) {
                this.dataCountFromSourceProvider = dataCountFromSourceProvider;
                this.byteArray = byteArray;
                this.compressedSize = compressedSize;
                this.uncompressedSize = uncompressedSize;
                this.metadata = metadata;
                this.millisTotal = millisTotal;
                this.millisSource = millisSource;
                this.millisSerialize = millisSerialize;
                this.millisCompress = millisCompress;
            }
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
            static final String COMMAND_REQUEST_CLIENT_BOOT = "REQ_CLIENT_BOOT";
            static final String COMMAND_REQUEST_CLIENT_MANUAL = "REQ_CLIENT_MANUAL";
            static final String COMMAND_REQUEST_SERVER_MANUAL = "REQ_SERVER_MANUAL";
            static final String COMMAND_REQUEST_SERVER_PERIODIC = "REQ_SERVER_PERIODIC";
            static final String COMMAND_REQUEST_ENSURER_TRIGGERED = "REQ_ENSURER_TRIGGERED";
            static final String COMMAND_REQUEST_SENDING = "REQ_SEND";
            static final String COMMAND_UPDATE_FULL = "UPDATE_FULL";
            static final String COMMAND_UPDATE_PARTIAL = "UPDATE_PARTIAL";
            static final String COMMAND_SIBLING_COMMAND = "SIBLING_COMMAND";

            // No-args constructor for Jackson
            public BroadcastDto() {
            }

            public BroadcastDto(String command, String sendingNodename) {
                this.command = command;
                this.sentNodename = sendingNodename;
                this.sentTimestamp = System.currentTimeMillis();
                this.sentNanoTime = System.nanoTime();
            }

            // ===== For all commands
            String command;
            String sentNodename;
            long sentTimestamp;
            long sentNanoTime;

            // ===== For cache request replies
            String correlationId;
            String requestNodename;
            long requestSentTimestamp;
            long requestSentNanoTime;

            // ===== For the actual updates to clients.
            int dataCount;
            String metadata;
            long uncompressedSize;
            int compressedSize;

            double millisTotalProduceAndCompress; // Total time to produce the Data set
            double millisCompress; // Compress (and write to byte array, but that's ~0) only
            // Note: The actual Deflated data is added as a binary sideload: 'SIDELOAD_KEY_SOURCE_DATA'

            // ====== For sibling commands
            String siblingCommand;
            String siblingStringData;
            // Note: Bytes are sideloaded

            // ===== Mechanism to synchronize the updates
            String handlingNodename;
        }

        /**
         * A cache monitor, which can be used to log and monitor the cache server's activity.
         */
        static class CacheMonitor {
            private static final int MAX_ENTRIES = 20;
            private final List<LogEntry> logEntries = new ArrayList<>();
            private final List<ExceptionEntry> exceptionEntries = new ArrayList<>();

            private final Logger log;

            public CacheMonitor(Logger log) {
                this.log = log;
            }

            public synchronized void log(LogLevel level, MonitorCategory monitorCategory, String message) {
                if (logEntries.size() >= MAX_ENTRIES) {
                    logEntries.remove(0);
                }
                logEntries.add(new LogEntryImpl(level, monitorCategory, message));
                if (DEBUG.equals(level)) {
                    if (log.isDebugEnabled()) log.debug(LOG_PREFIX + monitorCategory + ": " + message);
                }
                else {
                    if (log.isInfoEnabled()) log.info(LOG_PREFIX + monitorCategory + ": " + message);
                }
            }

            public synchronized void exception(MonitorCategory monitorCategory, String message, Throwable throwable) {
                if (exceptionEntries.size() >= MAX_ENTRIES) {
                    exceptionEntries.remove(0);
                }
                exceptionEntries.add(new ExceptionEntryImpl(monitorCategory, message, throwable));
                log.error(LOG_PREFIX + monitorCategory + ": " + message, throwable);
            }

            public synchronized List<LogEntry> getLogEntries() {
                return Collections.unmodifiableList(new ArrayList<>(logEntries));
            }

            public synchronized List<ExceptionEntry> getExceptionEntries() {
                return Collections.unmodifiableList(new ArrayList<>(exceptionEntries));
            }
        }

        static class LogEntryImpl implements LogEntry {
            private final LogLevel level;
            private final MonitorCategory _monitorCategory;
            private final String message;
            private final long timestamp;

            LogEntryImpl(LogLevel level, MonitorCategory monitorCategory, String message) {
                this.level = level;
                this._monitorCategory = monitorCategory;
                this.message = message;
                this.timestamp = System.currentTimeMillis();
            }

            public MonitorCategory getCategory() {
                return _monitorCategory;
            }

            public LogLevel getLevel() {
                return level;
            }

            public String getMessage() {
                return message;
            }

            public long getTimestamp() {
                return timestamp;
            }

            public String toHtmlString() {
                return "<div class='mec_logmessage'>"
                        + "<code>" + _formatTimestamp(timestamp) + "</code> "
                        + "<b>" + _monitorCategory + "</b> "
                        + message
                        + "</div>";
            }
        }

        static class ExceptionEntryImpl implements ExceptionEntry {
            private final MonitorCategory _monitorCategory;
            private final String message;
            private final Throwable throwable;
            private final long timestamp;

            ExceptionEntryImpl(MonitorCategory monitorCategory, String message, Throwable throwable) {
                this._monitorCategory = monitorCategory;
                this.message = message;
                this.throwable = throwable;
                this.timestamp = System.currentTimeMillis();
            }

            public MonitorCategory getCategory() {
                return _monitorCategory;
            }

            public String getMessage() {
                return message;
            }

            public Throwable getThrowable() {
                return throwable;
            }

            public long getTimestamp() {
                return timestamp;
            }

            public String toHtmlString() {
                // Print Stacktrace to String
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stackTrace = sw.toString();

                return "<div class='mec_logmessage'><code>"
                        + _formatTimestamp(timestamp) + "</code> "
                        + "<b>" + _monitorCategory + "</b> "
                        + message + "<br>"
                        + "<pre>" + stackTrace + "</pre>"
                        + "</div>";
            }
        }
    }
}
