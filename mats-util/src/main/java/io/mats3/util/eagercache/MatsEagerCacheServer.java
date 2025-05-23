/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SequenceWriter;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.MatsLoggingInterceptor;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.TraceId;
import io.mats3.util.compression.ByteArrayDeflaterOutputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientImpl;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl.NodeAdvertiser.NodeAndTimestamp;

/**
 * The server side of the Mats Eager Cache system - sitting on the "data owner" side. <b>All caches within a Mats3
 * Fabric must have a globally unique "DataName" - failure to adhere to this will result in data corruption on the cache
 * clients!</b> This server will listen for requests for cache updates when clients boot, and send out a serialization
 * of the source data to the clients, which will deserialize it and make it available to the service. If the source data
 * changes, an update should be pushed by the service by means of {@link #initiateFullUpdate(int)}. The server will also
 * {@link #setPeriodicFullUpdateIntervalMinutes(double) periodically} send full updates. There's optionally also a
 * feature for {@link #sendPartialUpdate(CacheDataCallback) sending partial updates}, which can be employed if the
 * source data's size, or frequency of updates, makes full updates too resource intensive (this is however more complex
 * to handle on the client side, as it must merge the update with the existing data, and should only be used if the
 * source data is updated quite frequently or if the source data is large).
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
 * then propagate the update to the MatsEagerCacheServer via {@link #initiateFullUpdate(int)} or
 * {@link #sendPartialUpdate(CacheDataCallback) sendPartialUpdate(CacheDataCallback)}, which then broadcasts the update
 * to all clients.
 * <p>
 * The cache server accesses the data via the {@link CacheDataCallback CacheDataCallback} supplier provided in the
 * constructor. It is important that the source data can be read in a consistent manner, so some kind of synchronization
 * or locking of the data should be employed while the cache server reads it (mainly relevant if the source data is held
 * in memory).
 * <p>
 * <h2>Design and Usage of Caches, and the <code>TRANSPORT</code> Data Type</h2>
 * <p>
 * <h3>DataName</h3>
 * <p>
 * <b>It is imperative that a given Cache Server-Client system have a global unique <code>DataName</code> within the
 * Mats3 Fabric, i.e. within a given Message Broker!</b> This is because the cache clients will subscribe to a Topic
 * derived from the DataName, and the cache server will publish to this Topic. If two different cache servers use the
 * same DataName, the clients will receive data from both servers, and will not be able to distinguish between the two.
 * This will lead to data corruption on the client side when it tries to deserialize wrong data into its specified
 * <code>TRANSPORT</code> CTO. The DataName should be a simple and descriptive name, and can include dots, e.g.
 * "ProductCatalog.Bestsellers".
 * <p>
 * <h3><code>TRANSPORT</code> <i>Cache Transport Object</i> datatype</h3>
 * <p>
 * You should never send over full domain objects to the clients, but rather a <code>TRANSPORT</code> object that is
 * tailored to what the clients actually need, today. These are called <i>Cache Transport Objects</i>, <code>CTO</code>.
 * This both to save processing resources (read on!), and to decouple/insulate the service-internal domain API from
 * whatever contract the cache server sets up with the clients. You also do not want to reuse DTOs used for e.g. Mats3
 * or REST Endpoints - this so you can change and evolve them fully independent of all other interfaces in the service.
 * Also, you'll want to keep memory usage on the client for keeping the cached data as low as possible (read on!).
 * <p>
 * <h3>Serializer configuration and consequences</h3>
 * <p>
 * The serialization/deserialization system will serialize and deserialize <b>all fields of the <code>TRANSPORT</code>
 * CTO, of all visibility levels.</b> It introspects the fields - not using any getters. On the deserializing side,
 * <b>it depends on a present no-args constructor to make the object</b>, and it does not use any argument-taking
 * constructor to instantiate. It doesn't use any setters, instead relying on Jackson's ability to set fields directly
 * (technically, the process is different for Records, but that does not make any difference in this context).
 * <p>
 * <b>You shall not depend on any Jackson specific features!</b> The idea is that you make a simple
 * <code>TRANSPORT</code> CTO (it can be as deep as you need, with nested CTOs and Collections), but not using any
 * Jackson specific annotations or features. This is to ensure that we can easily switch out Jackson for another
 * serializer if we find a faster or more efficient one. <i>The only contract the cache system gives is that the
 * <code>TRANSPORT</code> CTO object will be "magically transported" from the server to the client side, using direct
 * field access on both read and write, with support for both POJOs and Records.</i>
 * <p>
 * The serialization/deserialization system is also configured for compact serialization, and lenient deserialization:
 * On the sending side, it does not include fields that are null. On the receiving side, it does not require CTO
 * specified fields to be present in the JSON, and it does not fail if there are extra fields in the JSON compared to
 * the CTO. If some native data fields, e.g. int, double or boolean, often are the default value, you can consider using
 * their respective boxed variants, e.g. Integer, Double and Boolean, instead, and leave them null - they will thus not
 * be serialized (saving space!), and end up with the default value on the receiving side. <b>Importantly, this setup
 * means that there are clear ways of evolving the data structures: You can both add and remove fields to the server
 * side CTO without breaking the client side.</b> This is because the client side will simply ignore fields it does not
 * know about, and it will not fail if fields are missing. Obviously, if you remove a field that one of the cache's
 * clients are dependent on, it will now receive default values <code>null</code>, <code>0</code>, or <code>false</code>
 * when the client code reads the field, which probably is rather bad. (A multi-repo source-viewing and -searching
 * system like <a href="https://oracle.github.io/opengrok/">OpenGrok</a> is invaluable in such contexts!) <b>This
 * flexibility also implies that you shall never include fields in the <code>TRANSPORT</code> CTO that no clients yet
 * are using "just in case"</b> - this is a complete waste of both CPU, memory and network bandwidth, and will just
 * clutter up your maintenance. Add the field when a client needs it. You might even want to make two or more different
 * caches (with different TRANSFER objects): One for those services that just need a few fields, and another for those
 * that use many.
 * <p>
 * <b>You are advised against using enums!</b> The problem is evolution: If you add a new enum value, and the client
 * does not yet know about it, it will fail deserialization! Rather use Strings for the enum constants, mapping back
 * (including a default) to enum values when constructing domain objects from the <code>TRANSPORT</code> CTOs on the
 * client side.
 * <p>
 * <h3>Memory usage on client when deserializing / Sharing common instances</h3>
 * <p>
 * Current experience wrt. usage (financial context with multiple time series over different securities) shows that
 * there are many instances of date representations and other common identifiers like ISIN. Java does not natively help
 * one bit with this, so you might end up with a lot of e.g. <code>LocalDate</code> instances representing the same
 * date. This is a waste of memory, and you will want to consider using a simple shared instance cache for these when
 * going from the <code>TRANSPORT</code> CTO to the actual domain object on the client - this can substantially reduce
 * memory usage. A simple <code>Map&lt;String, LocalDate&gt;</code> or <code>Map&lt;String, Isin&gt;</code> can do
 * wonders here, using the {@link Map#computeIfAbsent(Object, java.util.function.Function) computeIfAbsent(..)} method.
 * If you're sure some particular Strings are common and will effectively live for the duration of the JVM, you should
 * consider using <code>String.intern()</code> on them.
 * <p>
 * <h3>Developing the Client on the Server side</h3>
 * <p>
 * It makes sense to develop the client side on the server side, as you then have the full source code available for
 * both sides, and be close to the source data, thus simplifying the development. Note: You should NOT actually run the
 * client on the server side, except for testing purposes, and possibly in "development environment". Oftentimes, more
 * than one service is interested in the cached dataset, and in such cases it makes sense to let the "ownership" of the
 * client code be on server side, and copy over the client code to the other services - for some popular datasets, it
 * might even make sense to have a separate client library that is shared between the services.
 * <p>
 * <h3>Testing</h3>
 * <p>
 * You should probably make unit tests that serialize and deserialize the <code>TRANSPORT</code> CTOs with relevant
 * (deep) content, to ensure that it works as expected. Use the
 * {@link FieldBasedJacksonMapper#getMats3DefaultJacksonObjectMapper()} for your tests: Make a <code>TRANSPORT</code>
 * CTO setup, serialize it and keep the String JSON, then deserialize it, and serialize the result again, asserting that
 * the String JSON is identical.
 * <p>
 * You should make integration tests on the server side which additionally creates the cache client, and then do a full
 * run from actual source data that server consumes, via the cache server and client's use of the <code>TRANSFER</code>
 * CTOs, and then back up to the target DATA container and objects on client side, and then checks that the client has
 * received the full DATA as expected. This is even more important if you use the partial update feature, as then also
 * the client side becomes a bit more complex. You'll want to read the JavaDoc on
 * {@link MatsEagerCacheClient#linkToServer(MatsEagerCacheServer)}.
 * <p>
 * <b>Thread-safety:</b> After the cache server is started, it is thread-safe: Calls to
 * {@link #initiateFullUpdate(int)}, {@link #sendPartialUpdate(CacheDataCallback) sendPartialUpdate(..)}, and
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
    /**
     * All log lines from the Mats Eager Cache Server will be prefixed with this string. This is valuable for grepping
     * or searching the logs for the Mats Eager Cache Server. Try "Grep Console" if you're using IntelliJ.
     * (<code>"#MatsEagerCache#S"</code>).
     */
    String LOG_PREFIX_FOR_SERVER = "#MatsEagerCache#S "; // The space is intentional, don't remove!
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
     * currently have problems making source data sets, we'll wait longer - to not risk continuously (attempting to)
     * producing updates. (15 minutes)
     */
    int ENSURER_WAIT_TIME_LONG_MILLIS = 15 * 60 * 1000;
    /**
     * If the time since last update is higher than this, the next update will be scheduled to run immediately (for
     * manual requests) or soon (for client boot requests). (30 seconds).
     *
     * @see #initiateFullUpdate(int)
     * @see #DEFAULT_SHORT_DELAY_MILLIS
     * @see #DEFAULT_LONG_DELAY_MILLIS
     */
    int FAST_RESPONSE_LAST_RECV_THRESHOLD_SECONDS = 30;
    /**
     * Read {@link #initiateFullUpdate(int)} for the rationale behind this delay. (2.5 seconds)
     */
    int DEFAULT_SHORT_DELAY_MILLIS = 2500;
    /**
     * Read {@link #initiateFullUpdate(int)} for the rationale behind this delay. (7 seconds)
     */
    int DEFAULT_LONG_DELAY_MILLIS = 7000;
    /**
     * Some commands, e.g. {@link #sendSiblingCommand(String, String, byte[]) sendSiblingCommand(..)} (and the
     * alternative start method {@link #startAndWaitForReceiving()}) needs to wait for the Mats3 endpoints to be ready
     * to receive. This is the maximum time to wait for this to happen. (4 minutes)
     */
    int MAX_WAIT_FOR_RECEIVING_SECONDS = 240;
    /**
     * Interval between server and client advertising itself to the rest of the system. (45 minutes + random up to 1/3
     * more, i.e. 45-60 minutes)
     */
    int ADVERTISEMENT_INTERVAL_MINUTES = 45;
    /**
     * During startup, we need to first assert that we can make a source data set before firing up the endpoints. If it
     * fails, it will try again until it manages - but sleep an amount between each attempt. Capped exponential from 2
     * seconds, this is the max sleep time between attempts. (30 seconds)
     */
    int MAX_INTERVAL_BETWEEN_DATA_ASSERTION_ATTEMPTS_MILLIS = 30_000;
    /**
     * Default interval between periodic full updates. The default is nearly 2 hours, hoping that you'd rather aim for a
     * push-on-update approach, than rely on periodic updates. (111 minutes)
     */
    double DEFAULT_PERIODIC_FULL_UPDATE_INTERVAL_MINUTES = 111;

    /**
     * The interval between the checks the Ensurer does.
     */
    long ENSURER_CHECK_INTERVAL_MILLIS = 60_000; // 1 minute

    /**
     * Create a Mats Eager Cache Server.
     *
     * @param matsFactory
     *            The MatsFactory to use.
     * @param dataName
     *            The unique name of the data for this cache server-client combination, which will be used in the Mats
     *            endpoints - <b>it is imperative that this is globally unique within the Mats Fabric!</b> - failure to
     *            adhere to this will result in data corruption for the clients, as they will receive data for another
     *            cache. You may use dots in the name, e.g. "ProductCatalog.Bestsellers".
     * @param cacheTransportDataType
     *            The data type ("CTO") to transport from the Cache Server to the Cache Clients. It should be tailored
     *            to what the cache clients need, and should be serializable by Jackson, to efficient JSON.
     * @param fullDataCallbackSupplier
     *            The supplier of the {@link CacheDataCallback} that provides the source data.
     * @return the created Mats Eager Cache Server.
     * @param <TRANSPORT>
     *            The data type to transmit to the cache clients.
     */
    static <TRANSPORT> MatsEagerCacheServer create(MatsFactory matsFactory, String dataName,
            Class<TRANSPORT> cacheTransportDataType, Supplier<CacheDataCallback<TRANSPORT>> fullDataCallbackSupplier) {
        return new MatsEagerCacheServerImpl(matsFactory, dataName, cacheTransportDataType, fullDataCallbackSupplier);
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
     * Programmatically initiates a full update of the cache clients, typically used to programmatically propagate a
     * change in the source data to the cache clients. This is a full update, where the entire source data is serialized
     * and sent to the clients.
     * <p>
     * It is scheduled to run after a little while, the delay being a function of how soon since the last time a full
     * update was run. If it is a long time ({@link #FAST_RESPONSE_LAST_RECV_THRESHOLD_SECONDS}) since last update was
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
     * The update is asynchronous. However, this method can be invoked with a timeout, which will make the method block
     * until the update has been produced, sent and received back - which means that the clients shall also have gotten
     * the update). If the timeout is &le;0, the method will return immediately,
     *
     * @see #initiateManualFullUpdate(int)
     * 
     * @param timeoutMillis
     *            the timeout in milliseconds for the request to complete. If the full production, send and subsequent
     *            receive is performed within this time, the method will return <code>true</code>, otherwise
     *            <code>false</code>. If &le;0 is provided, the method will return <code>false</code> immediately, and
     *            the request will be performed asynchronously.
     * @return whether the update was successfully produced, sent and received back. If the timeout was &le;0, this will
     *         always be <code>false</code>.
     */
    boolean initiateFullUpdate(int timeoutMillis);

    /**
     * Same as {@link #initiateFullUpdate(int)}, but meant for GUIs - just to be able to separate the two in logs.
     *
     * @param timeoutMillis
     *            the timeout in milliseconds for the request to complete. If the full production, send and subsequent
     *            receive is performed within this time, the method will return <code>true</code>, otherwise
     *            <code>false</code>. If &le;0 is provided, the method will return <code>false</code> immediately, and
     *            the request will be performed asynchronously.
     * @return whether the update was successfully produced, sent and received back. If the timeout was &le;0, this will
     *         always be <code>false</code>.
     */
    boolean initiateManualFullUpdate(int timeoutMillis);

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
     * eventually result in a deadlock. The reason is that a full update may concurrently be starting, which also will
     * lock the source data when the full update callback is invoked. There is however also a serializing mechanism on
     * the actual sending of updates (as mentioned above), and depending on the order of progress of the full update and
     * the partial update, a deadlock will occur.
     * <p>
     * It is also important to let the stream of partial data returned from
     * {@link CacheDataCallback#provideSourceData(Consumer) partialDataCallback.provideSourceData(Consumer)} read
     * directly from the source data structures, and not from some temporary not-applied representation, as otherwise
     * the partial update might send out data that is older than what is currently present and which might have already
     * been sent via a concurrent update (think about races here). Thus, always first apply the update to the source
     * data (on all the instances running the Cache Server) in some atomic fashion (read up on
     * {@link MatsEagerCacheServerImpl.SiblingCommand}), and then retrieve the partial update from the source data, also
     * in an atomic fashion (e.g. use synchronization or locking).
     * <p>
     * On a general basis, it is important to realize that you are responsible for keeping the source data in sync
     * between the different instances of the service, and that the cache server only serves the data to the clients -
     * and such serving can happen from any one of the cache server service instances. This is however even more
     * important to understand wrt. partial updates: The Cache Clients will all get the partial update sent from the
     * initiating node - but this data update does not hold for the instances running the Cache Server: This is on you!
     * You must first ensure that all these instances have the partial update applied, before propagating it to the
     * Cache Clients. This is not really a problem if you serve the data from a database, as you then have an external
     * single source of truth, but if you serve the data from memory, you must ensure that the data is kept in sync
     * between the instances. The {@link MatsEagerCacheServerImpl.SiblingCommand} feature is meant to help with this.
     * <p>
     * Partial updates is an optional functionality to conserve resources for situations where somewhat frequent partial
     * updates is performed to the source data, e.g. new daily prices coming in on inventory. The caching system will
     * work just fine with only full updates. If the feature is employed, the client side must also be coded up to
     * handle partial updates, as otherwise the client will end up with stale data.
     * <p>
     * Correctly applying a partial update on the client is more complex than consuming a full update, as the client
     * must merge the partial update into the existing data structures, taking care to overwrite where appropriate, but
     * insert if the entity is new. This is why the feature is optional, and should only be used if the source data is
     * large and/or updated frequently enough to make the use of partial updates actually have a positive performance
     * impact outweighing the increased complexity on both the server, but particularly the client side.
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
     * <i>Note on typing: The <code>TRANSPORT</code> datatype for partial updates shall be the same as the one used for
     * the full update, but it was decided to not include the type as a generic on <code>MatsEagerCacheServer</code>, so
     * that users wouldn't have to always reference the cache server with the transfer type when the partial update
     * feature might not even be in use. This means that you'll manually have to ensure that the partial update data is
     * of the same type as the full update - the compiler won't remember it for you!</i>
     *
     * @param partialDataCallback
     *            the callback which the cache server invokes to retrieve the source data to send to the clients.
     */
    <TRANSPORT> void sendPartialUpdate(CacheDataCallback<TRANSPORT> partialDataCallback);

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
     * <i>(Probably most useful for tests)</i> Starts the cache server, and waits for it to be fully running: It first
     * invokes {@link #start()} (which will assert that it can get source data before starting the endpoints), and then
     * waits for the endpoints entering their receive-loops using {@link MatsEndpoint#waitForReceiving(int)}. This
     * method will thus block until the cache server is fully running, and able to serve cache clients.
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
     * invoke the provided <code>TRANSPORT</code> CTO-{@link Consumer} repeatedly until the entire dataset (for full
     * updates), or the partial dataset (for partial updates), has been provided, and then close any resources (e.g. SQL
     * Connection), and return - thus marking the end of data, which will be sent to the cache clients.
     *
     * @param <TRANSPORT>
     *            the data type to transmit to the cache clients. It should be tailored to what the cache clients need,
     *            and should be serializable by Jackson.
     */
    @FunctionalInterface
    interface CacheDataCallback<TRANSPORT> {
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
        void provideSourceData(Consumer<TRANSPORT> consumer);
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

        String getAppName();

        String getNodename();

        String getCacheRequestQueue();

        String getBroadcastTopic();

        CacheServerLifeCycle getCacheServerLifeCycle();

        long getCacheStartedTimestamp();

        long getLastFullUpdateRequestRegisteredTimestamp();

        long getLastFullUpdateProductionStartedTimestamp();

        long getLastFullUpdateReceivedTimestamp();

        double getLastFullUpdateRegisterToUpdateMillis();

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

        int getLastUpdateDataCount();

        String getLastUpdateMetadata();

        double getPeriodicFullUpdateIntervalMinutes();

        int getNumberOfFullUpdatesSent();

        int getNumberOfPartialUpdatesSent();

        int getNumberOfFullUpdatesReceived();

        int getNumberOfPartialUpdatesReceived();

        Map<String, Set<String>> getServersAppNamesToNodenames();

        Map<String, Set<String>> getClientsAppNamesToNodenames();

        List<LogEntry> getLogEntries();

        List<ExceptionEntry> getExceptionEntries();

        /**
         * Active method for acknowledging exceptions. This method will acknowledge the exception with the provided id,
         * and return whether the exception was acknowledged. If the exception is no longer in the list, or was already
         * acknowledged, it will return false.
         *
         * @param id
         *            the id of the exception to acknowledge.
         * @param username
         *            the username acknowledging the exception.
         * @return whether the exception was acknowledged.
         */
        boolean acknowledgeException(String id, String username);

        /**
         * Active method for acknowledging all exceptions up to the provided timestamp. This method will acknowledge all
         * exceptions up to the provided timestamp, and return the number of exceptions acknowledged.
         *
         * @param timestamp
         *            the timestamp to acknowledge exceptions up to.
         *
         * @param username
         *            the username acknowledging the exceptions.
         */
        int acknowledgeExceptionsUpTo(long timestamp, String username);
    }

    enum LogLevel {
        DEBUG, INFO, WARN
    }

    enum MonitorCategory {
        CACHE_SERVER,

        CACHE_CLIENT,

        ASSERT_DATA_AVAILABILITY,

        INITIAL_POPULATION,

        PERIODIC_UPDATE,

        REQUEST_UPDATE_CLIENT_BOOT,

        REQUEST_UPDATE_CLIENT_MANUAL,

        REQUEST_UPDATE_SERVER_PERIODIC,

        REQUEST_UPDATE_SERVER_MANUAL,

        REQUEST_UPDATE_SERVER_PROGRAMMATIC,

        REQUEST_COALESCE,

        REQUEST_SEND_NOW,

        UNKNOWN_COMMAND,

        ENSURER,

        ENSURE_UPDATE,

        SIBLING_COMMAND,

        PRODUCE_DATA,

        RECEIVED_UPDATE,

        GET,

        SEND_UPDATE,

        CACHE_CLIENT_MOCK, ADVERTISE_APP_AND_NODE;
    }

    interface LogEntry {
        long getTimestamp();

        MonitorCategory getCategory();

        LogLevel getLevel();

        String getMessage();
    }

    interface ExceptionEntry {
        long getTimestamp();

        void acknowledge(String acknowledgedByUser);

        default boolean isAcknowledged() {
            return getAcknowledgedTimestamp().isPresent();
        }

        Optional<String> getAcknowledgedByUser();

        OptionalLong getAcknowledgedTimestamp();

        MonitorCategory getCategory();

        String getId();

        String getMessage();

        Throwable getThrowable();

        String getThrowableAsString();

    }

    // ======== The 'MatsEagerCacheServer' implementation class

    class MatsEagerCacheServerImpl implements MatsEagerCacheServer {
        // These should be statically inlined by compiler, AFAIK.
        static String SUPPRESS_LOGGING_TRACE_PROPERTY_KEY = MatsLoggingInterceptor.SUPPRESS_LOGGING_TRACE_PROPERTY_KEY;
        static String SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY = MatsLoggingInterceptor.SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY;

        private static final Logger log = LoggerFactory.getLogger(MatsEagerCacheServer.class);

        private static final String SIDELOAD_KEY_SIBLING_COMMAND_BYTES = "scb";
        private static final DateTimeFormatter ISO8601_FORMATTER = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss.SSS");
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

        private final MatsFactory _matsFactory;
        private final String _dataName;
        private final Supplier<CacheDataCallback<?>> _fullDataCallbackSupplier;

        private final String _appName;
        private final String _nodename;
        private final ObjectWriter _sentDataTypeWriter;
        private final ThreadPoolExecutor _produceAndSendExecutor;

        private final NodeAdvertiser _nodeAdvertiser;
        private final PeriodicUpdater _periodicUpdater;
        private final Ensurer _ensurer;

        private final CacheMonitor _cacheMonitor;

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private <TRANSPORT> MatsEagerCacheServerImpl(MatsFactory matsFactory, String dataName,
                Class<TRANSPORT> transferDataType,
                Supplier<CacheDataCallback<TRANSPORT>> fullDataCallbackSupplier) {

            _matsFactory = matsFactory;
            _dataName = dataName;
            _fullDataCallbackSupplier = (Supplier<CacheDataCallback<?>>) (Supplier) fullDataCallbackSupplier;

            // Cache the AppName and Nodename
            _appName = matsFactory.getFactoryConfig().getAppName();
            _nodename = matsFactory.getFactoryConfig().getNodename();

            _cacheMonitor = new CacheMonitor(log, LOG_PREFIX_FOR_SERVER, dataName);
            // Create the NodeAdvertiser, but do not start it.
            _nodeAdvertiser = new NodeAdvertiser(_matsFactory, _cacheMonitor, true,
                    _dataName, _appName, _nodename, () -> _waitForReceiving(30_000));
            // Create the PeriodicUpdate, but do not start it.
            _periodicUpdater = new PeriodicUpdater();
            // Create the Ensurer, but do not start it.
            _ensurer = new Ensurer();

            // :: Jackson JSON ObjectMapper
            ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

            // Make specific Writer for the "transferDataType" - this is what we will need to serialize to send to
            // clients. Configure as NDJSON (Newline Delimited JSON), which is a good format for streaming.
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

        private volatile double _periodicFullUpdateIntervalMinutes = DEFAULT_PERIODIC_FULL_UPDATE_INTERVAL_MINUTES;

        @Override
        public MatsEagerCacheServer setPeriodicFullUpdateIntervalMinutes(double periodicFullUpdateIntervalMinutes) {
            if (_cacheServerLifeCycle != CacheServerLifeCycle.NOT_YET_STARTED) {
                throw new IllegalStateException("Can only set 'periodicFullUpdateIntervalMinutes' before starting.");
            }
            if ((periodicFullUpdateIntervalMinutes != 0) && (periodicFullUpdateIntervalMinutes < 0.05)) {
                throw new IllegalArgumentException("'periodicFullUpdateIntervalMinutes' must be == 0 (no periodic"
                        + " updates) or >0.05 (3 seconds, which is absurd).");
            }
            _periodicFullUpdateIntervalMinutes = periodicFullUpdateIntervalMinutes;
            return this;
        }

        private final Object _updateNotificationSync = new Object();
        private volatile CountDownLatch _updateNotificationLatch;

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

        // The server-cluster as a whole (all siblings):
        private volatile long _lastFullUpdateRequestRegisteredTimestamp;
        private volatile long _lastFullUpdateProductionStartedTimestamp;
        private volatile long _lastFullUpdateReceivedTimestamp;
        private volatile double _lastFullUpdateRegisterToUpdateMillis;
        private volatile long _lastPartialUpdateReceivedTimestamp;
        private volatile long _lastAnyUpdateReceivedTimestamp; // Both full and partial.
        private final AtomicInteger _numberOfFullUpdatesReceived = new AtomicInteger();
        private final AtomicInteger _numberOfPartialUpdatesReceived = new AtomicInteger();

        // Us as a node in the server cluster:
        private final AtomicInteger _numberOfFullUpdatesSent = new AtomicInteger();
        private final AtomicInteger _numberOfPartialUpdatesSent = new AtomicInteger();
        private volatile long _lastUpdateSentTimestamp;
        private volatile boolean _lastUpdateWasFull;
        private volatile double _lastUpdateProduceTotalMillis;
        private volatile double _lastUpdateSourceMillis;
        private volatile double _lastUpdateSerializeMillis;
        private volatile double _lastUpdateCompressMillis;
        private volatile int _lastUpdateCompressedSize;
        private volatile long _lastUpdateUncompressedSize;
        private volatile int _lastUpdateDataCount;
        private volatile String _lastUpdateMetadata;

        private final Map<String, Long> _msg_correlationIdToNanoTime = new HashMap<>();

        // Use a lock to make sure that only one thread is producing and sending an update at a time, and make it fair
        // so that entry into the method is sequenced in the order of the requests.
        private final ReentrantLock _produceAndSendUpdateLock = new ReentrantLock(true);

        private final CopyOnWriteArrayList<Consumer<SiblingCommand>> _siblingCommandEventListeners = new CopyOnWriteArrayList<>();

        private volatile List<MatsEagerCacheClientImpl<?>> _linkedCacheClients; // For development / testing.

        private int _shortDelay = DEFAULT_SHORT_DELAY_MILLIS;
        private int _longDelay = DEFAULT_LONG_DELAY_MILLIS;

        private class CacheServerInformationImpl implements CacheServerInformation {
            @Override
            public String getDataName() {
                return _dataName;
            }

            @Override
            public String getAppName() {
                return _appName;
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
            public long getLastFullUpdateRequestRegisteredTimestamp() {
                return _lastFullUpdateRequestRegisteredTimestamp;
            }

            @Override
            public long getLastFullUpdateProductionStartedTimestamp() {
                return _lastFullUpdateProductionStartedTimestamp;
            }

            @Override
            public long getLastFullUpdateReceivedTimestamp() {
                return _lastFullUpdateReceivedTimestamp;
            }

            @Override
            public double getLastFullUpdateRegisterToUpdateMillis() {
                return _lastFullUpdateRegisterToUpdateMillis;
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
                return _lastUpdateSentTimestamp;
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
            public int getLastUpdateDataCount() {
                return _lastUpdateDataCount;
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
            public Map<String, Set<String>> getServersAppNamesToNodenames() {
                return _nodeAdvertiser.getServersAppNamesToNodenames();
            }

            @Override
            public Map<String, Set<String>> getClientsAppNamesToNodenames() {
                return _nodeAdvertiser.getClientsAppNamesToNodenames();
            }

            @Override
            public List<LogEntry> getLogEntries() {
                return _cacheMonitor.getLogEntries();
            }

            @Override
            public List<ExceptionEntry> getExceptionEntries() {
                return _cacheMonitor.getExceptionEntries();
            }

            @Override
            public boolean acknowledgeException(String id, String username) {
                return _cacheMonitor.acknowledgeException(id, username);
            }

            @Override
            public int acknowledgeExceptionsUpTo(long timestamp, String username) {
                return _cacheMonitor.acknowledgeExceptionsUpTo(timestamp, username);
            }
        }

        @Override
        public MatsEagerCacheServer addSiblingCommandListener(Consumer<SiblingCommand> siblingCommandEventListener) {
            _cacheMonitor.log(INFO, MonitorCategory.SIBLING_COMMAND, "cacheServer.addSiblingCommandListener! ["
                    + siblingCommandEventListener + "].");
            _siblingCommandEventListeners.add(siblingCommandEventListener);
            return this;
        }

        @Override
        public void sendSiblingCommand(String command, String stringData, byte[] binaryData) {
            _cacheMonitor.log(INFO, MonitorCategory.SIBLING_COMMAND, "cacheServer.sendSiblingCommand! ["
                    + command + "] - [" + (stringData != null
                            ? "stringData: " + stringData.length() + " chars"
                            : "-no stringData-")
                    + "], [" + (binaryData != null
                            ? "binaryData: " + binaryData.length + " bytes"
                            : "-no binaryData-") + "].");
            // We must be in correct state, and the broadcast terminator must be ready to receive.
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);

            // Construct the broadcast DTO
            BroadcastDto broadcast = _createBroadcastDto(BroadcastDto.COMMAND_SIBLING_COMMAND);
            broadcast.siblingCommand = command;
            broadcast.siblingStringData = stringData;

            // Send the broadcast
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                init.traceId(TraceId.create("MatsEagerCacheServer." + _dataName, "SiblingCommand").add("cmd", command))
                        .addBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES, binaryData)
                        .from("MatsEagerCacheServer." + _dataName + ".SiblingCommand")
                        .to(_getBroadcastTopic(_dataName))
                        .setTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Boolean.TRUE.toString())
                        .publish(broadcast);
            });
        }

        @Override
        public boolean initiateFullUpdate(int timeoutMillis) {
            return _initiateFullUpdate_internal(timeoutMillis, true);
        }

        @Override
        public boolean initiateManualFullUpdate(int timeoutMillis) {
            return _initiateFullUpdate_internal(timeoutMillis, false);
        }

        @Override
        public <TRANSPORT> void sendPartialUpdate(CacheDataCallback<TRANSPORT> partialDataCallback) {
            _cacheMonitor.log(INFO, MonitorCategory.SEND_UPDATE, "cacheServer.sendPartialUpdate! ["
                    + partialDataCallback + "]");
            _produceAndSendUpdate(null, () -> partialDataCallback, false,
                    "Server/Manual/Partial");
        }

        @Override
        public void start() {
            _start_internal("start");
        }

        @Override
        public void startAndWaitForReceiving() {
            _start_internal("startAndWaitForReceiving");
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
        }

        @Override
        public void close() {
            _cacheMonitor.log(INFO, MonitorCategory.CACHE_SERVER, "cacheServer.close()!");
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
                _periodicUpdater.stop();
                _nodeAdvertiser.stop();
                _ensurer.stop();
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

        void _setCoalescingDelays(int shortDelay, int longDelay) {
            _shortDelay = shortDelay;
            _longDelay = longDelay;
        }

        private boolean _initiateFullUpdate_internal(int timeoutMillis, boolean programmatic) {
            String type = programmatic
                    ? "initiateFullUpdate_Programmatic"
                    : "initiateFullUpdate_Manual";
            MonitorCategory cat = programmatic
                    ? MonitorCategory.REQUEST_UPDATE_SERVER_PROGRAMMATIC
                    : MonitorCategory.REQUEST_UPDATE_SERVER_MANUAL;
            _cacheMonitor.log(INFO, cat, "cacheServer." + type + "! [timeoutMillis:" + timeoutMillis + "]");
            CountDownLatch updatedLatch = null;
            if (timeoutMillis > 0) {
                synchronized (_updateNotificationSync) {
                    if (_updateNotificationLatch == null) {
                        _updateNotificationLatch = new CountDownLatch(1);
                    }
                    updatedLatch = _updateNotificationLatch;
                }
            }
            // Initiate the full update.
            if (programmatic) {
                _fullUpdateCoord_phase0_InitiateFromServer_Programmatic();
            }
            else {
                _fullUpdateCoord_phase0_InitiateFromServer_Manual();
            }

            // ?: Did we set up a latch for waiting for the update to finish?
            if (updatedLatch == null) {
                // -> No, we're done.
                return false;
            }
            // E-> Yes, we should wait.
            try {
                boolean finished = updatedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
                if (finished) {
                    return true;
                }
                _cacheMonitor.log(INFO, cat, "Timed out waiting for full update to finish!"
                        + " [timeoutMillis:" + timeoutMillis + "]");
                return false;
            }
            catch (InterruptedException e) {
                _cacheMonitor.exception(cat, "Got interrupted while waiting for full update to finish!"
                        + " [timeoutMillis:" + timeoutMillis + "]", e);
                return false;
            }
        }

        private void _start_internal(String whatMethod) {
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
                _cacheServerLifeCycle = CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY;
            }
            _cacheMonitor.log(INFO, MonitorCategory.CACHE_SERVER, whatMethod + "! Starting the"
                    + " MatsEagerCacheServer for data [" + _dataName + "].");

            // Create thread that checks if we actually can request the Source Data, and when it manages,
            // starts the endpoints.
            Thread checkThenStartThread = new Thread(() -> {
                // We'll keep trying until we succeed.
                long sleepTimeBetweenAttempts = 2000;
                while (_cacheServerLifeCycle == CacheServerLifeCycle.STARTING_ASSERTING_DATA_AVAILABILITY ||
                        _cacheServerLifeCycle == CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA) {
                    // Try to get the data from the source provider.
                    try {
                        _cacheMonitor.log(INFO, MonitorCategory.ASSERT_DATA_AVAILABILITY,
                                "Asserting that we can get Source Data.");
                        DataResult result = _produceDataResult(_fullDataCallbackSupplier);
                        _cacheMonitor.log(INFO, MonitorCategory.ASSERT_DATA_AVAILABILITY,
                                "Success: We asserted that we can get Source Data! Data count:["
                                        + result.dataCountFromSourceProvider + "]");

                        // :: ### Start the endpoints ###
                        _createMatsEndpoints();

                        // :: We're now running.
                        _cacheStartedTimestamp = System.currentTimeMillis();
                        _cacheServerLifeCycle = CacheServerLifeCycle.RUNNING;
                        _waitForRunningLatch.countDown();
                        _waitForRunningLatch = null; // fast-path check, and get rid of the CountDownLatch.

                        // :: Start periodic update, and the NodeAdvertiser.
                        _periodicUpdater.start();
                        _nodeAdvertiser.start();
                        _ensurer.start();

                        // We're done - it is possible to get source data.
                        break;
                    }
                    catch (Throwable t) {
                        _cacheMonitor.exception(MonitorCategory.ASSERT_DATA_AVAILABILITY,
                                "Got exception while trying to assert that we could call the source provider and get"
                                        + " data. Will keep trying.", t);
                        _cacheServerLifeCycle = CacheServerLifeCycle.STARTING_PROBLEMS_WITH_DATA;
                    }
                    // Wait a bit before trying again.
                    try {
                        Thread.sleep(sleepTimeBetweenAttempts);
                    }
                    catch (InterruptedException e) {
                        _cacheMonitor.exception(MonitorCategory.ASSERT_DATA_AVAILABILITY,
                                "Got interrupted while waiting for initial population to be done.", e);
                        // NOTE: The _running flag will be checked in the next iteration.
                    }
                    // Increase sleep time between attempts, but cap it at 30 seconds.
                    sleepTimeBetweenAttempts = (long) Math.min(MAX_INTERVAL_BETWEEN_DATA_ASSERTION_ATTEMPTS_MILLIS,
                            sleepTimeBetweenAttempts * 1.5);
                }
            }, "MatsEagerCacheServer." + _dataName + "-InitialPopulationCheck");
            checkThenStartThread.setDaemon(true);
            checkThenStartThread.start();
        }

        private void _createMatsEndpoints() {
            // ::: Create the Mats endpoints

            _cacheMonitor.log(INFO, MonitorCategory.CACHE_SERVER, "Creating the Mats endpoints for the"
                    + " MatsEagerCacheServer: Request: '" + _getCacheRequestQueue(_dataName) + "', Broadcast: '"
                    + _getBroadcastTopic(_dataName) + "'.");

            // :: The queue-based terminator that the clients will send update requests to.
            //
            // Note 1: The only reason for having a separate endpoint for the cache request is to be able to use
            // queue semantics for it. The client could just as well have sent the request directly to the broadcast
            // topic (we literally just forward it from this queue terminator to the broadcast subscription terminator),
            // and the server would have used that directly as the "phase 1" message. But if there were no servers up
            // when the client sent the request, it would have been lost. By having a queue, the client can send the
            // request, and the server can start up later, and still get the request - since it has queue semantics.
            //
            // Note 2: We set concurrency to 1 to divide incoming requests as round-robin as possible to the cache
            // server siblings. As mentioned above, it is literally just a forward to the broadcast topic, so there is
            // nothing to gain from having multiple threads processing the requests.
            _requestTerminator = _matsFactory.terminator(_getCacheRequestQueue(_dataName),
                    void.class, CacheRequestDto.class,
                    endpointConfig -> endpointConfig.setConcurrency(1), MatsFactory.NO_CONFIG,
                    (ctx, state, msg) -> _fullUpdateCoord_phase0_InitiateByRequestFromClient(msg));

            // :: Listener to the update topic.
            // To be able to see that a sibling has sent an update, we need to listen to the broadcast topic for the
            // updates. This enables us to not send an update if a sibling has already sent one.
            // This is also the topic which will be used by the siblings to send commands to each other (which the
            // clients will ignore). It is a pretty hard negative to receiving the actual updates on the servers, which
            // can be large - even though it already has the data. However, we won't have to decompress/deserialize the
            // data, so the hit won't be that big. The obvious alternative is to have a separate topic for the commands,
            // but that would pollute the MQ Destination namespace with one extra topic per cache. A positive is that
            // we then actually get the updates on the server, which is good for introspection & monitoring.
            _broadcastTerminator = _matsFactory.subscriptionTerminator(_getBroadcastTopic(_dataName),
                    void.class, BroadcastDto.class, (ctx, state, broadcastDto) -> {
                        // Snoop on messages for keeping track of whom the clients and servers are.
                        _nodeAdvertiser.handleAdvertise(broadcastDto);

                        // ?: Is this a periodic advertisement?
                        if (BroadcastDto.COMMAND_SERVER_ADVERTISE.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_CLIENT_ADVERTISE.equals(broadcastDto.command)) {
                            // -> Yes, so then we ignore it, since the NodeAdvertiser handles it.
                            return;
                        }
                        // ?: Is this a sibling command?
                        else if (BroadcastDto.COMMAND_SIBLING_COMMAND.equals(broadcastDto.command)) {
                            _handleSiblingCommand(ctx, broadcastDto);
                        }
                        // ?: Is this the internal "sync between siblings" about having received a request for update?
                        else if (BroadcastDto.COMMAND_REQUEST_CLIENT_BOOT.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_PERIODIC.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_PROGRAMMATIC.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_REQUEST_SERVER_ENSURER.equals(broadcastDto.command)) {
                            _fullUpdateCoord_phase1_CoalesceAndElect(broadcastDto);
                        }
                        // ?: Is this the internal "sync between siblings" about now sending the update?
                        else if (BroadcastDto.COMMAND_REQUEST_SEND.equals(broadcastDto.command)) {
                            _fullUpdateCoord_phase2_ElectedLeaderSendsUpdate(broadcastDto);
                        }
                        // ?: Is this the actual update sent to the clients - which we also get?
                        else if (BroadcastDto.COMMAND_UPDATE_FULL.equals(broadcastDto.command)
                                || BroadcastDto.COMMAND_UPDATE_PARTIAL.equals(broadcastDto.command)) {
                            _handleCacheUpdateLogging(ctx, state, broadcastDto);
                        }
                        else {
                            _cacheMonitor.exception(MonitorCategory.UNKNOWN_COMMAND, "Got a broadcast with"
                                    + " unknown command, ignoring: " + _infoAboutBroadcast(broadcastDto),
                                    new IllegalArgumentException("Unknown broadcast command, shouldn't happen."));
                        }
                    });
            // Allow log suppression
            _broadcastTerminator.getEndpointConfig().setAttribute(
                    SUPPRESS_LOGGING_ENDPOINT_ALLOWS_ATTRIBUTE_KEY, Boolean.TRUE);
        }

        /**
         * We are only interested in the cache updates insofar as to log and time them, as well as for the
         * PeriodicUpdate thundering herd avoidance algorithm - they are meant for the clients. We do not process them,
         * i.e. we do not expend resources on decompressing, deserializing, and stacking up the data.
         */
        private void _handleCacheUpdateLogging(ProcessContext<Void> ctx, Void state, BroadcastDto broadcastDto) {
            // :: Jot down that the clients were sent an update, used when calculating the delays, and health checks.
            _lastAnyUpdateReceivedTimestamp = System.currentTimeMillis();

            // ?: Is this a full update?
            if (broadcastDto.command.equals(BroadcastDto.COMMAND_UPDATE_FULL)) {
                // -> Yes, this was a full update, so record the timestamp, and count.
                _lastFullUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
                _numberOfFullUpdatesReceived.incrementAndGet();

                // Log Request-to-Update delay
                double millisRequestToUpdate = -1;
                synchronized (_msg_correlationIdToNanoTime) {
                    Long nanoTime = _msg_correlationIdToNanoTime.remove(broadcastDto.correlationId);
                    if (nanoTime != null) {
                        millisRequestToUpdate = (System.nanoTime() - nanoTime) / 1_000_000.0;
                        _lastFullUpdateRegisterToUpdateMillis = millisRequestToUpdate;
                    }
                    // GC: Delete entries older than 2 hours
                    _msg_correlationIdToNanoTime.entrySet()
                            .removeIf(entry -> entry.getValue() < System.nanoTime() - 2 * 60 * 60 * 1_000 * 1_000_000L);
                }

                _cacheMonitor.log(DEBUG, MonitorCategory.RECEIVED_UPDATE, "[Broadcast]"
                        + " Received FULL Cache Update!"
                        + (millisRequestToUpdate >= 0
                                ? " - Request-to-Update:" + _formatMillis(millisRequestToUpdate)
                                : "")
                        + _infoAboutBroadcast(broadcastDto));

                // ?: If we have any waiters for the update, we should release them.
                synchronized (_updateNotificationSync) {
                    if (_updateNotificationLatch != null) {
                        _updateNotificationLatch.countDown();
                        _updateNotificationLatch = null;
                    }
                }

            }
            else {
                // -> No, this was a partial update, so record the timestamp, and count.
                _lastPartialUpdateReceivedTimestamp = _lastAnyUpdateReceivedTimestamp;
                _numberOfPartialUpdatesReceived.incrementAndGet();
            }
            // :: If we have any linked clients, we should send the update to them.
            if (_linkedCacheClients != null) {
                _linkedCacheClients.forEach(client -> client._processLambdaForSubscriptionTerminator(ctx,
                        state, broadcastDto));
            }
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
                // This is the only place where we write and add to the '_cacheClients'.
                // There is "fast path" null-based checking for the normal case where we do not link any clients.
                // ?: Do we have the list?
                if (_linkedCacheClients == null) {
                    // -> No, we don't have the list, so create it.
                    // We use a COWAL since we do not sync on reading.
                    _linkedCacheClients = new CopyOnWriteArrayList<>();
                }
                _linkedCacheClients.add(client);
            }
        }

        private class PeriodicUpdater {
            private volatile Thread _thread;
            private volatile boolean _running;

            /**
             * Starts the periodic update thread - for clean shutdown, call {@link #stop()}. It is a daemon thread, so
             * it won't hold the JVM back - but you should still call {@link #stop()}.
             */
            private void start() {
                if (_periodicFullUpdateIntervalMinutes == 0) {
                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Periodic update: NOT starting"
                            + "Thread, as periodic update is set to 0, i.e. never.");
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
                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Periodic update: Thread started."
                            + " interval: [" + _periodicFullUpdateIntervalMinutes + " min] => ["
                            + String.format("%,d", intervalMillis) + " ms], check interval: ["
                            + String.format("%,d", checkIntervalMillis) + " ms, "
                            + _formatMillis(checkIntervalMillis) + "].");
                    // Ensure that we're fully operational before starting the periodic update.
                    _broadcastTerminator.waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
                    // Going into the run loop
                    while (_running) {
                        try {
                            /*
                             * The main goal here is to avoid the situation where all servers start producing periodic
                             * updates at the same time, or near the same time, which would result in unnecessary load
                             * on every component, and if the source data is retrieved from a database, the DB will be
                             * loaded at the same time from all servers. Aka avoiding "thundering herds".
                             *
                             * We ideally want *one* update per periodic interval, even though we have multiple
                             * instances of the service running (aka. "siblings").
                             *
                             * The idea is to have a check interval, which is a fraction plus a bit of randomness of the
                             * interval between the periodic updates. We repeatedly sleep the check interval, and then
                             * check whether the last full update is longer ago than the periodic update interval. If we
                             * haven't received a full update within the periodic update interval, we should initiate a
                             * full update. Since there is some randomness in the check interval, we hopefully avoid
                             * that all servers check at the same time. The one that is first to see that it is time for
                             * a full update, will send a broadcast message to the siblings to get the process going,
                             * which will lead to a new full update being produced and broadcast - which the siblings
                             * also receive, and record the timestamp of. (Note: This is a bit counter-intuitive, and
                             * also a bit wasteful: The server side also get the cache data update. But it does not
                             * process it, avoiding that resource use - it is only interested in the fact that it has
                             * happened, and its metadata). When the other siblings wake up from their check interval
                             * sleep, and see that a full update has arrived, they'll see that there's nothing to do
                             * (since they're now plenty within the periodic update interval - they just recently got
                             * it!), and they'll just continue their check loop.
                             *
                             * However, if these updates come rather close to each other, AND it takes a long time to
                             * produce the update, we might not catch any double-request for update with this solution
                             * alone: Since the max "coalescing and election" sleep is just a few seconds, which if the
                             * update takes e.g. tens of seconds to produce and send will lead the second guy to wake up
                             * also wanting to do a full update (that is, it will wake up and see "ah, no full update
                             * has arrived within periodic update interval - better get this going!", even though one is
                             * just in the process of being created and shipped!). Thus, if we see that we haven't
                             * gotten an update in the interval, we *additionally* also check whether a full update
                             * request have come in within the periodic update interval (this is a very small message
                             * that is received pretty close to immediately after it is sent) - assuming then that the
                             * full update process was started by another of the siblings. If this is the case, we wait
                             * one more interval before checking again - hopefully the actual full update will have come
                             * in during this extra wait. If not, we start the process.
                             *
                             * Finally: It doesn't matter all that much if this doesn't always work out perfectly, and
                             * we expend a bit more resources than necessary by handling duplicate full updates; It is
                             * better with an update too many than an update too few.
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
                            if (Math.max(_lastFullUpdateRequestRegisteredTimestamp, _cacheStartedTimestamp) > System
                                    .currentTimeMillis() - intervalMillis) {
                                // -> We're currently producing an update, so we'll wait one more interval.
                                _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Cluster is currently"
                                        + " producing an update, so we'll wait one more interval.");
                                // Sleep one more interval - but in testing, the interval can be very short, so we'll
                                // minimum sleep the coalescing "long delay" plus a bit, to not fire twice.
                                Thread.sleep(Math.max(checkIntervalMillis, _longDelay + 500));
                                // ?: Have we received a full update within the interval now?
                                if (Math.max(_lastFullUpdateReceivedTimestamp, _cacheStartedTimestamp) > System
                                        .currentTimeMillis() - intervalMillis) {
                                    // -> We've received a full update within the interval, so we don't need to do
                                    // anything.
                                    _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "After having"
                                            + " checked again, we find that an update has already been received,"
                                            + " so we don't need to initiate periodic update.");
                                    continue;
                                }
                            }
                            // E-> So, we should request a full update. If this now comes in at the same time as another
                            // server, the "thundering herd avoidance" solution should mitigate double production.
                            _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Periodic update interval"
                                    + " exceeded: Issuing request for full update."
                                    + " We are: [" + _dataName + "] " + _nodename);
                            _fullUpdateCoord_phase0_InitiateFromServer_Periodic();
                        }
                        catch (InterruptedException e) {
                            // We're probably shutting down.
                            _cacheMonitor.log(INFO, MonitorCategory.PERIODIC_UPDATE, "Thread interrupted,"
                                    + " probably shutting down. We are: [" + _dataName + "] " + _nodename);
                        }
                        catch (Throwable t) {
                            _cacheMonitor.exception(MonitorCategory.PERIODIC_UPDATE, "Periodic update: Got"
                                    + " exception while trying to schedule periodic update. Ignoring."
                                    + " We are: [" + _dataName + "] " + _nodename, t);
                        }
                    }
                }, "MatsEagerCacheServer." + _dataName
                        + ".PeriodicUpdate[" + _periodicFullUpdateIntervalMinutes + "min]");
                _thread.setDaemon(true);
                _thread.start();
            }

            /**
             * Stops the Periodic Update thread, and nulls the thread.
             */
            private void stop() {
                if (_thread == null) {
                    return;
                }
                _running = false;
                _thread.interrupt();
                _thread = null;
            }
        }

        private class Ensurer {
            private Thread _ensurerThread;
            private volatile boolean _running;
            private volatile long _lastUpdateInitiatedTimestamp;

            private void start() {
                _running = true;

                _ensurerThread = new Thread(() -> {
                    while (_running) {
                        try {
                            Thread.sleep(ENSURER_CHECK_INTERVAL_MILLIS);

                            // We copy off the timestamp to check. This is a big point, as we'll use this particular
                            // timestamp for a "double check". If a new update is initiated, we will still compare
                            // against the old timestamp. The new update's checking will be done on the next iteration
                            // of the loop.
                            long lastUpdateInitiatedTimestamp = _lastUpdateInitiatedTimestamp;

                            // ?: If the last coalescing started timestamp is 0, we've not gotten any requests yet
                            if (lastUpdateInitiatedTimestamp == 0) {
                                // -> No update requests yet, so loop.
                                continue;
                            }

                            // ?: Have we NOT received a full update AFTER the last coalescing started timestamp?
                            if (lastUpdateInitiatedTimestamp > _lastFullUpdateReceivedTimestamp) {
                                // -> No, we have not received a full update after the last coalescing started

                                // Now, go into the "ensurer" mode, where we check if we should trigger a new
                                // update attempt

                                // :: Sleep for a while, then evaluate if we're still in the same situation.

                                /*
                                 * Adjust the time to check based on situation: If we're currently making a source data
                                 * set (indicating that we're effectively always producing updates probably since it is
                                 * extremely slow), or having problems creating source data (e.g. database problems), or
                                 * if this request for full update was triggered by a triggered ensurer, we'll wait
                                 * longer.
                                 */
                                int waitTime = _currentlyMakingSourceDataResult
                                        || _currentlyHavingProblemsCreatingSourceDataResult
                                                ? ENSURER_WAIT_TIME_LONG_MILLIS
                                                : ENSURER_WAIT_TIME_SHORT_MILLIS;
                                Thread.sleep(waitTime);

                                // ?: Check again if we've received a full update after the last coalescing?
                                if (lastUpdateInitiatedTimestamp > _lastFullUpdateReceivedTimestamp) {
                                    // -> No, we have not received a full update after the last coalescing started
                                    // timestamp, so we should trigger a coalescing.
                                    _cacheMonitor.log(WARN, MonitorCategory.ENSURE_UPDATE, "Ensurer triggered:"
                                            + " We have NOT seen a full update AFTER this update was started ["
                                            + _formatTimestamp(lastUpdateInitiatedTimestamp)
                                            + "]: Initiating a new full update. We are: [" + _dataName + "] "
                                            + _nodename);

                                    // Fire off a broadcast to the siblings to trigger an update.
                                    _fullUpdateCoord_phase0_InitiateFromServer_Ensurer();
                                }
                                else {
                                    // -> Yes, we have seen the full update, so we're happy.
                                    _cacheMonitor.log(DEBUG, MonitorCategory.ENSURE_UPDATE,
                                            "Ensurer OK: There have been a full update since update started ["
                                                    + _formatTimestamp(lastUpdateInitiatedTimestamp)
                                                    + "], thus we're happy: No need to initiate a new full update."
                                                    + " We are: [" + _dataName + "] " + _nodename);
                                }
                            }
                        }
                        catch (InterruptedException e) {
                            // We're probably shutting down.
                            _cacheMonitor.log(INFO, MonitorCategory.ENSURER, "Thread interrupted,"
                                    + " probably shutting down. We are: [" + _dataName + "] " + _nodename);
                        }
                        catch (Throwable t) {
                            _cacheMonitor.exception(MonitorCategory.ENSURER, "Ensurer: Got exception while"
                                    + " trying to schedule periodic update. Ignoring."
                                    + " We are: [" + _dataName + "] " + _nodename, t);
                            _takeNap(MonitorCategory.ENSURER, ENSURER_WAIT_TIME_SHORT_MILLIS);
                        }
                    }
                }, "MatsEagerCacheServer." + _dataName + ".Ensurer");
                _ensurerThread.setDaemon(true);
                _ensurerThread.start();
            }

            private void updateInitiated() {
                _lastUpdateInitiatedTimestamp = System.currentTimeMillis();
            }

            private void stop() {
                if (_ensurerThread == null) {
                    return;
                }
                _running = false;
                _ensurerThread.interrupt();
                _ensurerThread = null;
            }
        }

        static class NodeAdvertiser {
            private static final int ADVERTISE_INTERVAL_TICK_MILLIS = 30_000; // 30 seconds * 2 = 1 minute.
            private static final int ADVERTISE_INTERVAL_TICKS = ADVERTISEMENT_INTERVAL_MINUTES * 2;

            private final MatsFactory _matsFactory;
            private final CacheMonitor _cacheMonitor;
            private final boolean _server;
            private final String _serverOrClassName;
            private final String _dataName;
            private final String _appName;
            private final String _nodename;

            // SYNC on the Maps when reading or writing!
            // Should ONLY contain one server AppName!! If it contains more, HealthChecks should fail.
            private final Map<String, Set<NodeAndTimestamp>> _serversAppNamesToNodenames = new HashMap<>();
            // We might have multiple client Apps, each of them having multiple nodenames.
            private final Map<String, Set<NodeAndTimestamp>> _clientsAppNamesToNodenames = new HashMap<>();

            private final AtomicInteger _advertiseCounter = new AtomicInteger(getNextTicks());
            private final Runnable _waitBeforeStart;

            NodeAdvertiser(MatsFactory matsFactory, CacheMonitor cacheMonitor, boolean server,
                    String dataName, String appName, String nodename, Runnable waitBeforeStart) {
                _matsFactory = matsFactory;
                _cacheMonitor = cacheMonitor;
                _server = server;
                _serverOrClassName = server ? MatsEagerCacheServer.class.getSimpleName()
                        : MatsEagerCacheClient.class.getSimpleName();
                _dataName = dataName;
                _appName = appName;
                _nodename = nodename;
                _waitBeforeStart = waitBeforeStart;
            }

            /**
             * Equals/HashCode is only on nodename
             */
            static class NodeAndTimestamp {
                final String nodename;
                final long timestamp;

                NodeAndTimestamp(String nodename) {
                    this.nodename = nodename;
                    this.timestamp = System.currentTimeMillis();
                }

                @Override
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    NodeAndTimestamp that = (NodeAndTimestamp) o;
                    return Objects.equals(nodename, that.nodename);
                }

                @Override
                public int hashCode() {
                    return Objects.hashCode(nodename);
                }
            }

            private volatile Thread _thread;
            private volatile boolean _running;
            private volatile long _lastServerSeenTimestamp;

            /**
             * Starts the advertising thread - for clean shutdown, call {@link #stop()}. It is a daemon thread, so it
             * won't hold the JVM back - but you should still call {@link #stop()}.
             */
            void start() {
                _running = true;
                _thread = new Thread(() -> {
                    _cacheMonitor.log(INFO, MonitorCategory.ADVERTISE_APP_AND_NODE, "NodeAdvertiser: "
                            + "Thread started. We are '" + _appName + "' @ '" + _nodename + "'.");

                    boolean clearAppsNodes = true;

                    // :: Outer, try-catch-all loop, to catch all exceptions and errors.
                    while (_running) {
                        try {
                            // Wait for endpoints to start
                            if (_waitBeforeStart != null) _waitBeforeStart.run();
                            // Advertise that we just booted (will clear AppsAndNodes map on clients and servers)
                            boolean clear = _server && clearAppsNodes;
                            doAdvertise(clear, false, "Initial advertisement"
                                    + (_server ? "; clearAppsNodes=" + clear : "") + ".");
                            clearAppsNodes = false;

                            // Going into periodic advertisement loop
                            while (_running) {
                                int ticks = _advertiseCounter.decrementAndGet();
                                // ?: Is it time to advertise?
                                if (ticks <= 0) {
                                    // -> Yes, it is time to advertise.
                                    // Send the advertisement
                                    doAdvertise(false, false, "Periodic AppAndNode advertisement.");
                                    // Reset countdown
                                    _advertiseCounter.set(getNextTicks());
                                }
                                // Sleep the tick time.
                                Thread.sleep(ADVERTISE_INTERVAL_TICK_MILLIS);
                                _scavengeAppAndNodenames(_clientsAppNamesToNodenames);
                                _scavengeAppAndNodenames(_serversAppNamesToNodenames);
                            }
                        }
                        catch (InterruptedException e) {
                            _cacheMonitor.log(INFO, MonitorCategory.ADVERTISE_APP_AND_NODE,
                                    "AdvertiseAppAndNode: Thread interrupted, probably shutting down.");
                        }
                        catch (Throwable t) {
                            _cacheMonitor.exception(MonitorCategory.ADVERTISE_APP_AND_NODE,
                                    "AdvertiseAppAndNode: Got exception while trying to advertise app and"
                                            + " node. Ignoring. Sleep 2 min and loop.", t);
                            try {
                                Thread.sleep(2 * 60 * 1_000);
                            }
                            catch (InterruptedException e) {
                                /* no-op, will check runflag */
                            }
                        }
                    }
                }, _serverOrClassName + "." + _dataName + ".AdvertiseAppAndNodeName");
                _thread.setDaemon(true);
                _thread.start();
            }

            /**
             * Stops the NodeAdvertiser, and nulls out the thread.
             */
            void stop() {
                if (_thread == null) {
                    return;
                }
                _running = false;
                _thread.interrupt();
                _thread = null;
                doAdvertise(false, true, "Stopping NodeAdvertiser,"
                        + " Sending 'Going Away' Advertisement.");
            }

            boolean isRunning() {
                return _running;
            }

            Map<String, Set<String>> getServersAppNamesToNodenames() {
                synchronized (_serversAppNamesToNodenames) {
                    // Return copy, with copy of sets.
                    return _serversAppNamesToNodenames.entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey(),
                                    e -> new TreeSet<>(e.getValue().stream()
                                            .map(nt -> nt.nodename).collect(Collectors.toSet()))));
                }
            }

            Map<String, Set<String>> getClientsAppNamesToNodenames() {
                synchronized (_clientsAppNamesToNodenames) {
                    // Return copy, with copy of sets.
                    return _clientsAppNamesToNodenames.entrySet().stream()
                            .collect(Collectors.toMap(e -> e.getKey(),
                                    e -> new TreeSet<>(e.getValue().stream()
                                            .map(nt -> nt.nodename).collect(Collectors.toSet()))));
                }
            }

            long getLastServerSeenTimestamp() {
                return _lastServerSeenTimestamp;
            }

            private static int getNextTicks() {
                return ADVERTISE_INTERVAL_TICKS + ThreadLocalRandom.current().nextInt(ADVERTISE_INTERVAL_TICKS / 3);
            }

            void handleAdvertise(BroadcastDto broadcastDto) {
                // ?: Is this a Node Advertisement from clients?
                // (This is the ONLY type of Broadcast message sent by clients!)
                if (BroadcastDto.COMMAND_CLIENT_ADVERTISE.equals(broadcastDto.command)) {
                    // -> Yes, it is a (periodic) advertisement from client, so then it is a Client app and nodename.
                    // ?: "Going away" advertisement?
                    if (broadcastDto.goingAway) {
                        // -> Yes, this is a "we're going down now!" message, so remove the client node.
                        _removeAppAndNodename(_clientsAppNamesToNodenames,
                                broadcastDto.sentAppName, broadcastDto.sentNodename);
                    }
                    else {
                        // -> No, this is a "here we are!" message, so add the client node.
                        _addAppAndNodename(_clientsAppNamesToNodenames,
                                broadcastDto.sentAppName, broadcastDto.sentNodename);
                    }
                }
                else {
                    // -> No, this is a broadcast from a Server, with Server app and nodename - for any type of message.

                    // Update the timestamp
                    _lastServerSeenTimestamp = System.currentTimeMillis();

                    // ?: Is this an Advertisement from server node that it is 'going away'?
                    if (broadcastDto.goingAway) {
                        // -> Yes, that server node is going away, so delete it from the maps.
                        _removeAppAndNodename(_serversAppNamesToNodenames,
                                broadcastDto.sentAppName, broadcastDto.sentNodename);
                        return;
                    }

                    // ?: Should we clear the maps? (Happens when servers boot)
                    if (broadcastDto.clearAppsNodes) {
                        // -> Yes, clear the maps (all nodes will do this)
                        _serversAppNamesToNodenames.clear();
                        _clientsAppNamesToNodenames.clear();
                        // Now ensure that we will advertise soon (a bit of coalescing logic here!)
                        _advertiseCounter.set(8 + ThreadLocalRandom.current().nextInt(4));

                        /*
                         * But if we're server, also immediately advertise - this is to try to catch multiple cache
                         * servers, on different apps using the same DataName as soon as possible (It makes zero sense
                         * that two different apps should be serving the same cache data). The idea is that all of them
                         * using the same DataName will honor this contract, and immediately send their advertisement,
                         * and then we all will see each other ASAP, and the healthcheck can screech when it sees
                         * multiple appNames using the same DataName. (The rationale for not including AppName in the
                         * DataName is that it is conceivable that one might want to refactor the system, so that a
                         * specific DataName now would be served by a different app/service. It would then be annoying
                         * to have to change the combined AppName+DataName on all services with a cache client
                         * connecting to this cache service, just because the app name changed.)
                         */

                        // ?: Are WE a server? (The other question is from where the broadcast came from!)
                        if (_server) {
                            // -> Yes, we're a server!
                            // Immediately send an advertisement to let the other servers know that we are here.
                            doAdvertise(false, false, "Server advertisement after"
                                    + " 'clearAppsNodes=true' from sibling '" + broadcastDto.sentAppName
                                    + "' @ '" + broadcastDto.sentNodename + "'.");

                            // Hack to ensure that we won't be caught with the problem where there is no guarantee of
                            // ordering between messages sent from different producers, and thus one server might get
                            // the clearAppsNodes=true message, that I react to here, after my advertisement!
                            Thread extraAdvertise = new Thread(() -> {
                                try {
                                    Thread.sleep(25);
                                    doAdvertise(false, false, "");
                                    Thread.sleep(200);
                                    doAdvertise(false, false, "");
                                }
                                catch (Throwable e) {
                                    /* no-op */
                                }
                            }, _serverOrClassName + "." + _dataName + ".BroadcastAppAndNodeName-ExtraAdvertise");
                            extraAdvertise.setDaemon(true);
                            extraAdvertise.start();
                        }
                    }
                    _addAppAndNodename(_serversAppNamesToNodenames,
                            broadcastDto.sentAppName, broadcastDto.sentNodename);
                }
            }

            private void doAdvertise(boolean clearAppsNodes, boolean goingAway, String logText) {
                if (!"".equals(logText)) {
                    _cacheMonitor.log(DEBUG, MonitorCategory.ADVERTISE_APP_AND_NODE, "Advertise: " + logText
                            + "  I am: '" + _dataName + "' @ '" + _appName + "' @ '" + _nodename + "'.");
                }
                _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                    init.traceId(TraceId.create(_serverOrClassName + "." + _dataName, "AdvertiseAppAndNode")
                            .add("app", _appName)
                            .add("node", _nodename))
                            .from(_serverOrClassName + "." + _dataName + ".AdvertiseAppAndNode")
                            .to(_getBroadcastTopic(_dataName));
                    // ?: Is this an ordinary advertisement? (not clear or goingAway)
                    if (!(clearAppsNodes || goingAway)) {
                        // -> Yes, this is an ordinary advertisement, so we should suppress logging.
                        init.setTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Boolean.TRUE);
                    }
                    init.publish(new AdvertiseBroadcastDto(_server
                            ? BroadcastDto.COMMAND_SERVER_ADVERTISE
                            : BroadcastDto.COMMAND_CLIENT_ADVERTISE,
                            _appName, _nodename, clearAppsNodes, goingAway));
                });
            }
        }

        static void _addAppAndNodename(Map<String, Set<NodeAndTimestamp>> mapToAddTo, String appName, String nodename) {
            synchronized (mapToAddTo) {
                Set<NodeAndTimestamp> nodes = mapToAddTo.computeIfAbsent(appName, k -> new HashSet<>());
                // NodeAndTimestamp has equals/hashcode nodename only.
                NodeAndTimestamp newNT = new NodeAndTimestamp(nodename);
                nodes.remove(newNT); // Ensure no duplicates based on nodename
                nodes.add(newNT); // Add fresh timestamped entry
            }
        }

        static void _removeAppAndNodename(Map<String, Set<NodeAndTimestamp>> mapToRemoveFrom, String appName,
                String nodename) {
            synchronized (mapToRemoveFrom) {
                Set<NodeAndTimestamp> nodes = mapToRemoveFrom.get(appName);
                // ?: Do we have a Set for this appName? (Should have!)
                if (nodes != null) {
                    // -> Yes, so remove the nodename from the set.
                    nodes.remove(new NodeAndTimestamp(nodename)); // NodeAndTimestamp has equals/hashcode nodename only.
                    // ?: Is the Set now empty?
                    if (nodes.isEmpty()) {
                        // -> Yes, set empty, remove it from the map.
                        mapToRemoveFrom.remove(appName);
                    }
                }
            }
        }

        static void _scavengeAppAndNodenames(Map<String, Set<NodeAndTimestamp>> mapToScavenge) {
            synchronized (mapToScavenge) {
                // Remove nodes from sets that are older than 1.5 * ADVERTISEMENT_INTERVAL_MINUTES
                long threshold = System.currentTimeMillis()
                        - (long) (ADVERTISEMENT_INTERVAL_MINUTES * 1.5 * 60 * 1_000);
                mapToScavenge.values().forEach(nodes -> nodes.removeIf(nt -> nt.timestamp < threshold));
                // Remove empty sets from map.
                mapToScavenge.entrySet().removeIf(e -> e.getValue().isEmpty());
            }
        }

        // ====== Initiating full update from various sources
        // We use the broadcast channel to sequence the incoming requests for updates, and to perform a leader election
        // of who shall do the update - the "stepping" is done with the "_msg_*" methods below.

        private void _fullUpdateCoord_phase0_InitiateFromServer_Periodic() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_SERVER_PERIODIC, "Phase 0: Initiating full update"
                    + " from server for periodic refresh (on this node!) [" + _nodename
                    + "], broadcasting to Phase 1.");
            BroadcastDto broadcast = _createBroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_PERIODIC);
            broadcast.correlationId = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);
            _broadcastInitiateFullUpdate(broadcast, "Server", _nodename, "Periodic");
        }

        private void _fullUpdateCoord_phase0_InitiateFromServer_Manual() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_SERVER_MANUAL, "Phase 0: Initiating full update"
                    + " from server by manual request [" + _nodename + "], broadcasting to Phase 1.");
            // Ensure that we are running
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
            // :: Create and send the broadcast message
            BroadcastDto broadcast = _createBroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL);
            broadcast.correlationId = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);
            _broadcastInitiateFullUpdate(broadcast, "Server", _nodename, "Manual");
        }

        private void _fullUpdateCoord_phase0_InitiateFromServer_Programmatic() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_SERVER_PROGRAMMATIC, "Phase 0: Initiating full"
                    + " update from server by programmatic request [" + _nodename + "], broadcasting to Phase 1.");
            // Ensure that we are running
            _waitForReceiving(MAX_WAIT_FOR_RECEIVING_SECONDS);
            // :: Create and send the broadcast message
            BroadcastDto broadcast = _createBroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_PROGRAMMATIC);
            broadcast.correlationId = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);
            _broadcastInitiateFullUpdate(broadcast, "Server", _nodename, "Programmatic");
        }

        private void _fullUpdateCoord_phase0_InitiateFromServer_Ensurer() {
            _cacheMonitor.log(INFO, MonitorCategory.REQUEST_UPDATE_SERVER_PROGRAMMATIC, "Phase 0: Initiating full"
                    + " update from server due to Ensurer [" + _nodename + "], broadcasting to Phase 1.");
            // :: Create and send the broadcast message
            BroadcastDto broadcast = _createBroadcastDto(BroadcastDto.COMMAND_REQUEST_SERVER_PROGRAMMATIC);
            broadcast.correlationId = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);
            _broadcastInitiateFullUpdate(broadcast, "Server", _nodename, "Ensurer");
        }

        private void _fullUpdateCoord_phase0_InitiateByRequestFromClient(CacheRequestDto incomingClientCacheRequest) {
            boolean boot;
            String command = incomingClientCacheRequest.command;
            if (CacheRequestDto.COMMAND_REQUEST_BOOT.equals(command)) {
                boot = true;
            }
            else if (CacheRequestDto.COMMAND_REQUEST_MANUAL.equals(command)) {
                boot = false;
            }
            else {
                _cacheMonitor.exception(MonitorCategory.UNKNOWN_COMMAND, "Got a CacheRequest with"
                        + " unknown command [" + command + "], ignoring: " + incomingClientCacheRequest,
                        new IllegalArgumentException("Unknown command in CacheRequest"));
                return;
            }

            var catg = boot ? MonitorCategory.REQUEST_UPDATE_CLIENT_BOOT : MonitorCategory.REQUEST_UPDATE_CLIENT_MANUAL;
            String nodename = incomingClientCacheRequest.nodename;
            _cacheMonitor.log(INFO, catg, "Phase 0: Initiating full update by request from client: "
                    + nodename + ", broadcasting to Phase 1."
                    + " Client command: " + incomingClientCacheRequest.command
                    + " - current Outstanding: [" + _updateRequest_OutstandingCount + "]");

            // Update with who listens to us, just since we have the information here.
            _addAppAndNodename(_nodeAdvertiser._clientsAppNamesToNodenames,
                    incomingClientCacheRequest.appName, nodename);

            // :: Send a broadcast message about next step, that we ourselves also will get.

            // Find command type
            boolean manual = CacheRequestDto.COMMAND_REQUEST_MANUAL.equals(command);
            String updateRequestCommand = manual
                    ? BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL
                    : BroadcastDto.COMMAND_REQUEST_CLIENT_BOOT;

            // Create the broadcast DTO, copy over the information from the incoming request.
            BroadcastDto broadcast = _createBroadcastDto(updateRequestCommand);
            broadcast.correlationId = incomingClientCacheRequest.correlationId;
            broadcast.reqNodename = nodename;
            broadcast.reqTimestamp = incomingClientCacheRequest.sentTimestamp;
            broadcast.reqNanoTime = incomingClientCacheRequest.sentNanoTime;
            // Send the broadcast
            _broadcastInitiateFullUpdate(broadcast, "Client",
                    nodename,
                    manual ? "Manual" : "Boot");
        }

        private void _broadcastInitiateFullUpdate(BroadcastDto broadcast, String initiateSide,
                String initatedNode, String reason) {
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId(
                    TraceId.create("MatsEagerCacheServer." + _dataName, "InitiateFullUpdate")
                            .add("initiateSide", initiateSide)
                            .add("initiateNode", initatedNode)
                            .add("reason", reason))
                    .from("MatsEagerCacheServer." + _dataName + ".InitiateFullUpdate." + initiateSide + "-" + reason)
                    .to(_getBroadcastTopic(_dataName))
                    .setTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Boolean.TRUE.toString())
                    .publish(broadcast));
        }

        private void _fullUpdateCoord_phase1_CoalesceAndElect(BroadcastDto inBroadcast) {
            long timestampWhenRequestReceived = System.currentTimeMillis();
            // "Log" the nanoTime so that we can calculate the time it took to get finished response.
            // The reason for a Map is due to the coalescing, where there might be multiple requests in the air, but
            // only one - typically the first - will be the one that gets a corresponding response.
            synchronized (_msg_correlationIdToNanoTime) {
                _msg_correlationIdToNanoTime.put(inBroadcast.correlationId, System.nanoTime());
            }
            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "[Broadcast] Phase 1: Coalesce"
                    + " requests and elect leader." + _infoAboutBroadcast(inBroadcast));

            // Update who our peers are, just since we have the information here.
            _addAppAndNodename(_nodeAdvertiser._serversAppNamesToNodenames,
                    inBroadcast.sentAppName, inBroadcast.sentNodename);

            boolean shouldStartCoalescingThread = false;
            synchronized (this) {
                // Increase the count of outstanding requests.
                _updateRequest_OutstandingCount++;
                // ?: Was this the initial message that pushed the count to 1?
                if (_updateRequest_OutstandingCount == 1) {
                    // -> Yes, this was the first one, so we should start the coalescing thread.
                    shouldStartCoalescingThread = true;
                    // We start by proposing this first message's node as the handling node.
                    _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "First message seen"
                            + " in this round, sender is leader unless challenged: [" + inBroadcast.sentNodename + "],"
                            + " which is " + (_nodename.equals(inBroadcast.sentNodename) ? "us!" : "NOT us!"));
                    _updateRequest_HandlingNodename = inBroadcast.sentNodename;
                }
                else {
                    // -> No, this was not the first message of this round.
                    // ?: Check if the new message was initiated by a lower nodename than the one we have.
                    if (inBroadcast.sentNodename.compareTo(_updateRequest_HandlingNodename) < 0) {
                        // -> Yes, this one is lower, so we'll take this one.
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "Message #"
                                + _updateRequest_OutstandingCount + " in this round, coalesced, NEW LEADER appeared"
                                + " with lower nodename. New: [" + inBroadcast.sentNodename + "]"
                                + " which is " + (_nodename.equals(inBroadcast.sentNodename) ? "us!" : "NOT us!")
                                + " (..new is better than existing '" + _updateRequest_HandlingNodename + "')");
                        // Update our view of who should handle this round.
                        _updateRequest_HandlingNodename = inBroadcast.sentNodename;
                    }
                    else {
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "Message #"
                                + _updateRequest_OutstandingCount + " in this round, coalesced, KEEPING leader, since"
                                + " new suggestion isn't lower. Keeping: [" + _updateRequest_HandlingNodename + "]"
                                + " which is " + (_nodename.equals(_updateRequest_HandlingNodename) ? "us!" : "NOT us!")
                                + " (..existing is better than new '" + inBroadcast.sentNodename + "')");
                    }
                }
            }

            /*
             * "Ensurer": Solution for ensuring that if the responsible node somehow does NOT manage to send the update
             * (e.g. crashes, boots, redeploys), someone else will: There's a thread on ALL nodes that in some minutes
             * will check if we've received a full update after this point in time, and if not, it will initiate a new
             * full update to try to remedy the situation.
             */
            _ensurer.updateInitiated();

            // ?: Should we start the election and coalescing thread?
            if (shouldStartCoalescingThread) {
                // -> Yes, this was the first message of this round, so we should start the coalescing thread.

                // The delay-stuff is both to find who should do the update (leader election), and to handle the
                // "thundering herd" problem, where all clients request a full update at the same time - which otherwise
                // would result in a lot of full updates being created and sent in parallel.

                // "If it is a long time since last invocation, it will be scheduled to run soon, while if the previous
                // time was a short time ago, it will be scheduled to run a bit later (~ within 7 seconds)."

                // Note: The logic here involves potential asymmetric race conditions due to slight timing differences
                // (milliseconds) when messages arrive at nodes. A request's classification as a needing "fast" or
                // "slow" response depends on a threshold, which can lead nodes to independently make different
                // decisions. Because processing times and message ordering are not identical across nodes, one node
                // might include a message in its current coalescing batch, while another excludes it, temporarily
                // desynchronizing their states.
                //
                // This situation primarily leads to generating slightly more updates than strictly necessary, which is
                // generally harmless. However, in an extremely rare case, a node might incorrectly determine it is not
                // the leader, believing another node (previously coalesced elsewhere) is still leading, thus
                // potentially missing an update. Even if this improbable scenario occurs, the "Ensurer" mechanism will
                // eventually correct the oversight, though with a considerably higher delay than ideal.

                // First find the latest time anything wrt. an update happened.
                long latestActivityTimestamp = Collections.max(Arrays.asList(_cacheStartedTimestamp,
                        _lastFullUpdateRequestRegisteredTimestamp, _lastFullUpdateProductionStartedTimestamp,
                        _lastAnyUpdateReceivedTimestamp));
                // NOTE: If it is a *long* time since last activity, we'll do a fast response.
                boolean fastResponse = (System.currentTimeMillis()
                        - latestActivityTimestamp) > (FAST_RESPONSE_LAST_RECV_THRESHOLD_SECONDS * 1000);
                // NOTE: A *manual* request gets immediate response, *if* fast is decided.
                boolean manual = BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL.equals(inBroadcast.command)
                        || BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL.equals(inBroadcast.command);
                // Calculate initial delay: Two tiers: If "fastResponse", decide by whether it was a manual request or
                // not.
                int initialDelay = fastResponse
                        ? manual
                                ? 0 // Immediate for manual
                                : _shortDelay // Short delay (longer than immediate!) for boot and periodic.
                        : _longDelay;

                Thread updateRequestsCoalescingDelayThread = new Thread(() -> {
                    try {
                        _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE,
                                "Started election and coalescing thread. We will wait for [" + initialDelay
                                        + "ms, fastResponse:" + fastResponse
                                        + "], coalescing incoming requests and elect"
                                        + " the leader. We are node: [" + _nodename + "], current proposed leader: ["
                                        + _updateRequest_HandlingNodename + " which is "
                                        + (_nodename.equals(_updateRequest_HandlingNodename) ? "us!" : "NOT us!")
                                        + "], currentOutstanding: [" + _updateRequest_OutstandingCount + "].");

                        // First sleep the initial delay.
                        _takeNap(MonitorCategory.REQUEST_COALESCE, initialDelay);

                        // ?: Was this a short sleep?
                        if (fastResponse) {
                            // -> Yes, short - now evaluate whether there have come in more requests while we slept.
                            int outstandingCount;
                            synchronized (this) {
                                outstandingCount = _updateRequest_OutstandingCount;
                            }
                            // ?: Have there come in more than the one that started the process?
                            if (outstandingCount > 1) {
                                // -> Yes, more requests have come in, so we'll do the long delay anyway, to see if we
                                // can coalesce even more requests.
                                _takeNap(MonitorCategory.REQUEST_COALESCE, _longDelay - _shortDelay);
                            }
                        }

                        // ----- Okay, we've waited for more requests, now we'll initiate the update.

                        String updateRequest_HandlingNodename;
                        synchronized (this) {
                            // Copy off the handling nodename: We need it to not change, and we need to reset it.
                            updateRequest_HandlingNodename = _updateRequest_HandlingNodename;
                        }

                        // ?: Are we the one that should handle the update?
                        if (_nodename.equals(updateRequest_HandlingNodename)) {
                            // -> Yes, it is us that should handle the update.
                            // We also do this over broadcast, so that all siblings can see that we're doing it.
                            // (And then also reset the count and HandlingNodename, read above).

                            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "Coalesced enough!"
                                    + " WE'RE ELECTED! We waited for more requests, and we ended up as elected leader."
                                    + " We will now broadcast to Phase 2 that handling nodename is us [" + _nodename
                                    + "]. (currentOutstanding: [" + _updateRequest_OutstandingCount + "]).");

                            BroadcastDto outBroadcast = _createBroadcastDto(BroadcastDto.COMMAND_REQUEST_SEND);
                            outBroadcast.originalCommand = inBroadcast.command;
                            outBroadcast.handlingNodename = _nodename; // It is us that is handling it.
                            // Transfer the correlationId and requestNodename from the incoming message (they might be
                            // null)
                            outBroadcast.correlationId = inBroadcast.correlationId;
                            outBroadcast.reqNodename = inBroadcast.reqNodename;
                            outBroadcast.reqTimestamp = inBroadcast.reqTimestamp;
                            outBroadcast.reqNanoTime = inBroadcast.reqNanoTime;

                            // Note: This can potentially throw. That is caught in the catch-all below.
                            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                                init.traceId(TraceId.create("MatsEagerCacheServer." + _dataName,
                                        "UpdateRequestsCoalesced"))
                                        .from("MatsEagerCacheServer." + _dataName + ".UpdateRequestsCoalesced")
                                        .to(_getBroadcastTopic(_dataName))
                                        .setTraceProperty(SUPPRESS_LOGGING_TRACE_PROPERTY_KEY, Boolean.TRUE.toString())
                                        .publish(outBroadcast);
                            });
                        }
                        else {
                            // -> No, it is not us that should handle the update.
                            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_COALESCE, "Coalesced enough!"
                                    + " WE LOST - We waited for more requests, and someone else were elected: ["
                                    + updateRequest_HandlingNodename + "]. We will thus not broadcast for next phase."
                                    + " We are " + _nodename + " (currentOutstanding: ["
                                    + _updateRequest_OutstandingCount
                                    + "]).");
                        }
                    }
                    catch (Throwable t) {
                        // We catch all problems - probably from the initiation-attempt - instead of letting it
                        // propagate out as uncaught exception from thread.
                        _cacheMonitor.exception(MonitorCategory.REQUEST_COALESCE, "Got exception while trying"
                                + " to coalesce requests and elect leader - thus not sending off the actual \"send new"
                                + " update\" broadcast. Resetting the coalesce system, letting the Ensurer-system"
                                + " handle this problem.", t);

                        // We now reset the coalescing system (in finally block), as we don't want to be in a state
                        // where we coalesce new messages into a coalescing thread that is dead! That would be
                        // unresolvable, as the logic above would just put more and more messages into "coalescing"
                        // state, but the thread is gone, so no-one will ever handle them.

                        // Note that this means we're temporarily screwed, but the Ensurer-logics will eventually kick
                        // in and start a new full update since it haven't seen a full update since this was started.
                    }
                    finally {
                        // :: Reset coalescing system: Clear count and nodename, after which a new request for update
                        // will start the election and coalescing process anew.
                        synchronized (this) {
                            // There are plenty of "asymmetric races" here based on the millisecond a message comes in.
                            // Read the earlier comment (at "if (shouldStartCoalescingThread)") for more details. These
                            // races are benign, as they *eventually* will resolve, albeit with either an update too
                            // many, or that the Ensurer system eventually catches it.
                            //
                            // Example: If a new (broadcast) request comes in "right now" (after the reset), this node
                            // will start a new coalescing round and fire off a new thread, while another node might
                            // coalesce the same broadcast message into the existing coalescing round, since it hasn't
                            // yet reset. This could happen due to milliseconds differences in receiving the message, or
                            // that they (again due to milliseconds differences!) slept different wrt. "short" or "long"
                            // delay.
                            synchronized (this) {
                                _updateRequest_OutstandingCount = 0;
                                _updateRequest_HandlingNodename = null;
                            }
                        }
                    }
                }, "MatsEagerCacheServer." + _dataName + "-UpdateRequestsCoalescingDelay");
                updateRequestsCoalescingDelayThread.setDaemon(true);
                updateRequestsCoalescingDelayThread.start();
            }

            // Record that we've received a request for update (after we've used the timestamp for the above calc).
            // NOTICE! MUST do this "late", as we use it right above to decide "fastResponse" or not.
            // NOTICE! We don't want to move this into the 'shouldStartCoalescingThread' block, as we want to record
            // this "last" timestamp for each request, not just the one that started the coalescing.
            _lastFullUpdateRequestRegisteredTimestamp = timestampWhenRequestReceived;
        }

        private void _fullUpdateCoord_phase2_ElectedLeaderSendsUpdate(BroadcastDto inBroadcast) {
            _cacheMonitor.log(DEBUG, MonitorCategory.REQUEST_SEND_NOW, "[Broadcast] Phase 2: Leader sends"
                    + " update - leader is " + (_nodename.equals(inBroadcast.handlingNodename) ? "us!" : "NOT us!")
                    + _infoAboutBroadcast(inBroadcast));

            // A full-update will be sent now, make note (for fastResponse evaluation, and possibly GUI)
            _lastFullUpdateProductionStartedTimestamp = System.currentTimeMillis();

            // ?: Is it us that should handle the actual broadcast of update?
            // .. AND is there no other update in the queue? (If there is a task in queue, that task will already send
            // most recent data, so no use in adding another that can't possibly send any more recent data AFAWK. If a
            // new update comes in after the reset of the count above, it will start the election and coalescing anew.)
            if (_nodename.equals(inBroadcast.handlingNodename)
                    && _produceAndSendExecutor.getQueue().isEmpty()) {
                // -> Yes it was us, and there are no other updates already in the queue.
                _produceAndSendExecutor.execute(() -> {
                    _cacheMonitor.log(DEBUG, MonitorCategory.SEND_UPDATE, "We are the elected node, and thus"
                            + " we now produce and send full update! [" + _nodename
                            + "] (currentOutstanding: [" + _updateRequest_OutstandingCount + "])");
                    String reason;
                    switch (inBroadcast.originalCommand) {
                        case BroadcastDto.COMMAND_REQUEST_SERVER_PERIODIC:
                            reason = "Server/Periodic";
                            break;
                        case BroadcastDto.COMMAND_REQUEST_SERVER_MANUAL:
                            reason = "Server/Manual";
                            break;
                        case BroadcastDto.COMMAND_REQUEST_SERVER_PROGRAMMATIC:
                            reason = "Server/Programmatic";
                            break;
                        case BroadcastDto.COMMAND_REQUEST_SERVER_ENSURER:
                            reason = "Server/EnsurerTriggered";
                            break;
                        case BroadcastDto.COMMAND_REQUEST_CLIENT_BOOT:
                            reason = "Client/Boot";
                            break;
                        case BroadcastDto.COMMAND_REQUEST_CLIENT_MANUAL:
                            reason = "Client/Manual";
                            break;
                        default:
                            reason = "Unknown";
                    }
                    try {
                        _produceAndSendUpdate(inBroadcast, _fullDataCallbackSupplier, true, reason);
                    }
                    catch (Throwable t) {
                        // We catch all problems instead of letting it propagate, as we're in an Executor, and we don't
                        // want to kill the thread, but instead log this and let the HealthCheck catch it.
                        _cacheMonitor.exception(MonitorCategory.REQUEST_SEND_NOW, "Got exception while trying"
                                + " to produce and send full update. Not good at all.", t);
                    }
                });
            }
        }

        private void _produceAndSendUpdate(BroadcastDto incomingBroadcastDto,
                Supplier<CacheDataCallback<?>> dataCallbackSupplier, boolean fullUpdate, String reason) {
            try {
                _produceAndSendUpdateLock.lock();

                // Create the SourceDataResult by asking the source provider for the data.
                DataResult result = _produceDataResult(dataCallbackSupplier);

                _lastUpdateWasFull = fullUpdate;
                _lastUpdateSentTimestamp = System.currentTimeMillis();
                _lastUpdateProduceTotalMillis = result.millisTotal;
                _lastUpdateSourceMillis = result.millisSource;
                _lastUpdateSerializeMillis = result.millisSerialize;
                _lastUpdateCompressMillis = result.millisCompress;

                _lastUpdateCompressedSize = result.compressedSize;
                _lastUpdateUncompressedSize = result.uncompressedSize;
                _lastUpdateDataCount = result.dataCountFromSourceProvider;
                _lastUpdateMetadata = result.metadata;
                _lastUpdateCompressMillis = result.millisCompress;

                // :: Create the Broadcast message (which doesn't contain the actual data, as that is sideloaded).
                String updateCommand = fullUpdate ? BroadcastDto.COMMAND_UPDATE_FULL
                        : BroadcastDto.COMMAND_UPDATE_PARTIAL;
                BroadcastDto broadcast = _createBroadcastDto(updateCommand);
                broadcast.reason = reason;
                broadcast.dataCount = result.dataCountFromSourceProvider;
                broadcast.compressedSize = result.compressedSize;
                broadcast.uncompressedSize = result.uncompressedSize;
                broadcast.metadata = result.metadata;
                broadcast.msTotal = result.millisTotal;
                broadcast.msCompress = result.millisCompress;
                broadcast.msSerialize = result.millisSerialize;
                // Transfer the correlationId and requestNodename from the incoming message, if present.
                if (incomingBroadcastDto != null) {
                    broadcast.correlationId = incomingBroadcastDto.correlationId;
                    broadcast.reqNodename = incomingBroadcastDto.reqNodename;
                    broadcast.reqTimestamp = incomingBroadcastDto.reqTimestamp;
                    broadcast.reqNanoTime = incomingBroadcastDto.reqNanoTime;
                }

                String type = fullUpdate ? "Full" : "Partial";

                if (fullUpdate) {
                    _numberOfFullUpdatesSent.incrementAndGet();
                }
                else {
                    _numberOfPartialUpdatesSent.incrementAndGet();
                }

                // :: Send the broadcast message, with the data sideloaded.
                TraceId traceId = TraceId.create("MatsEagerCacheServer." + _dataName, "Update")
                        .add("type", type)
                        .add("count", result.dataCountFromSourceProvider);
                if (result.metadata != null) {
                    traceId.add("meta", result.metadata);
                }
                // NOTE: WE DO *NOT* LOG-SUPPRESS THE ACTUAL UPDATE MESSAGE!
                _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                    init.traceId(traceId)
                            .from("MatsEagerCacheServer." + _dataName + ".Update")
                            .to(_getBroadcastTopic(_dataName))
                            .addBytes(SIDELOAD_KEY_DATA_PAYLOAD, result.byteArray)
                            .publish(broadcast);
                });

                Map<String, String> mdc = Map.of(
                        "mats.ec.updateSent", "true",
                        "mats.ec.type", type,
                        "mats.ec.reason", reason,
                        "mats.ec.metadata", result.metadata != null ? result.metadata : "-null-",
                        "mats.ec.dataCount", Integer.toString(result.dataCountFromSourceProvider),
                        "mats.ec.uncompressedSize", Long.toString(result.uncompressedSize),
                        "mats.ec.compressedSize", Integer.toString(result.compressedSize),
                        "mats.ec.msTotal", Double.toString(result.millisTotal),
                        "mats.ec.msSerialize", Double.toString(result.millisSerialize),
                        "mats.ec.msCompress", Double.toString(result.millisCompress));
                _cacheMonitor.logWithMdc(INFO, mdc, MonitorCategory.SEND_UPDATE, "Sent " + type
                        + " Update - MDC:"
                        + " type=" + type
                        + "; reason=" + reason
                        + "; metadata=" + result.metadata
                        + "; dataCount=" + result.dataCountFromSourceProvider
                        + "; uncompressedSize=" + _formatNiceBytes(result.uncompressedSize)
                        + "; compressedSize=" + _formatNiceBytes(result.compressedSize)
                        + "; msTotal=" + _formatMillis(result.millisTotal)
                        + "; msSerialize=" + _formatMillis(result.millisSerialize)
                        + "; msCompress=" + _formatMillis(result.millisCompress));

            }
            finally {
                _produceAndSendUpdateLock.unlock();
            }
        }

        private String _infoAboutBroadcast(BroadcastDto broadcastDto) {
            return " ## Command: " + broadcastDto.command + ", sentNode: " + broadcastDto.sentNodename
                    + (_nodename.equals(broadcastDto.sentNodename) ? " (#SENT# FROM US!)" : " (Not us!)")
                    + (broadcastDto.handlingNodename != null
                            ? ", handlingNode: " + broadcastDto.handlingNodename
                                    + (_nodename.equals(broadcastDto.handlingNodename)
                                            ? " (#HANDLED# BY US!)"
                                            : " (Not us!)")
                            : "")
                    + (broadcastDto.reqNodename != null
                            ? ", requestNodename: " + broadcastDto.reqNodename
                            : "")
                    + (broadcastDto.correlationId != null
                            ? ", correlationId: " + broadcastDto.correlationId
                            : "")
                    + ", currentOutstanding: " + _updateRequest_OutstandingCount;
        }

        private void _takeNap(MonitorCategory category, long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                var m = "Got interrupted while taking nap, unexpected.";
                var up = new IllegalStateException(m, e);
                _cacheMonitor.exception(category, m, up);
                throw up;
            }
        }

        private void _handleSiblingCommand(ProcessContext<Void> ctx, BroadcastDto broadcastDto) {
            _cacheMonitor.log(INFO, MonitorCategory.SIBLING_COMMAND, "[Broadcast]: Received sibling command: "
                    + broadcastDto.siblingCommand + ", invoking listeners." + _infoAboutBroadcast(broadcastDto));
            byte[] bytes = ctx.getBytes(SIDELOAD_KEY_SIBLING_COMMAND_BYTES);
            MatsEagerCacheServer.SiblingCommand siblingCommand = new MatsEagerCacheServer.SiblingCommand() {
                @Override
                public boolean originatedOnThisInstance() {
                    return _nodename.equals(broadcastDto.sentNodename);
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
                    _cacheMonitor.exception(MonitorCategory.SIBLING_COMMAND, "Got exception from"
                            + " SiblingCommandEventListener [" + listener + "], ignoring.", t);
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
                        // TODO/CONSIDER: Log each entity's size to monitor and HealthCheck. (up to max 1_000 entities)
                    }
                    catch (IOException e) {
                        _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA, "Got IOException while writing"
                                + " to Jackson SequenceWriter, which shouldn't happen, as we're writing to"
                                + " ByteArrayOutputStream.", e);
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
                    _cacheMonitor.exception(MonitorCategory.PRODUCE_DATA, "Got exception when closing Jackson"
                            + " SequenceWriter, which shouldn't happen, as we're writing to ByteArrayOutputStream.", e);
                    throw new RuntimeException(e);
                }
                _currentlyHavingProblemsCreatingSourceDataResult = false;
            }
            finally {
                _currentlyMakingSourceDataResult = false;
            }

            // Actual data count, not the guesstimate from the sourceCallback.
            int dataCountFromSourceProvider = dataCount[0];
            // Fetch the resulting compressed byte array.
            byte[] byteArray = out.toByteArray();
            // Sizes in bytes
            assert byteArray.length == out.getCompressedBytesOutput()
                    : "The byte array length should be the same as the compressed size, but it was not. This is a bug.";
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

            String appName;
            String nodename;
            long sentTimestamp;
            long sentNanoTime;
        }

        private BroadcastDto _createBroadcastDto(String command) {
            return new BroadcastDto(command, _appName, _nodename);
        }

        /**
         * Reduced version of the BroadcastDto, used for the "Advertise" broadcasts - serialize as small as possible.
         * Will be deserialized into the full {@link BroadcastDto} on the receiving side, so the field names must match.
         */
        static final class AdvertiseBroadcastDto {
            String command;
            String sentAppName;
            String sentNodename;
            boolean clearAppsNodes;
            boolean goingAway;

            public AdvertiseBroadcastDto() {
                // No-args constructor for Jackson
            }

            public AdvertiseBroadcastDto(String command, String sendingAppName, String sendingNodename,
                    boolean clearAppsNodes, boolean goingAway) {
                this.command = command;
                this.sentAppName = sendingAppName;
                this.sentNodename = sendingNodename;
                this.clearAppsNodes = clearAppsNodes;
                this.goingAway = goingAway;
            }
        }

        static final class BroadcastDto {
            static final String COMMAND_REQUEST_CLIENT_BOOT = "REQ_CLIENT_BOOT";
            static final String COMMAND_REQUEST_CLIENT_MANUAL = "REQ_CLIENT_MANUAL";
            static final String COMMAND_REQUEST_SERVER_MANUAL = "REQ_SERVER_MANUAL";
            static final String COMMAND_REQUEST_SERVER_PROGRAMMATIC = "REQ_SERVER_PROGRAMMATIC";
            static final String COMMAND_REQUEST_SERVER_PERIODIC = "REQ_SERVER_PERIODIC";
            static final String COMMAND_REQUEST_SERVER_ENSURER = "REQ_SERVER_ENSURER";
            static final String COMMAND_REQUEST_SEND = "REQ_SEND";
            static final String COMMAND_UPDATE_FULL = "UPDATE_FULL";
            static final String COMMAND_UPDATE_PARTIAL = "UPDATE_PARTIAL";
            static final String COMMAND_SIBLING_COMMAND = "SIBLING_COMMAND";

            static final String COMMAND_SERVER_ADVERTISE = "SERVER_ADVERTISE";
            static final String COMMAND_CLIENT_ADVERTISE = "CLIENT_ADVERTISE";

            public BroadcastDto() {
                // No-args constructor for Jackson
            }

            public BroadcastDto(String command, String sendingAppName, String sendingNodename) {
                this.command = command;
                this.sentAppName = sendingAppName;
                this.sentNodename = sendingNodename;
                this.sentTimestamp = System.currentTimeMillis();
                this.sentNanoTime = System.nanoTime();
            }

            // ===== For all commands
            String command;
            String sentAppName;
            String sentNodename;
            long sentTimestamp;
            long sentNanoTime;

            // ===== For "advertise" broadcasts
            boolean clearAppsNodes;
            boolean goingAway;

            // ===== For "chained" commands
            String originalCommand;

            // ===== For cache request replies
            // NOTE: All these will be for the first of any coalesced requests.
            String correlationId;
            String reqNodename;
            long reqTimestamp;
            long reqNanoTime;

            // ===== For the actual updates to clients.
            String reason;
            int dataCount;
            String metadata;
            long uncompressedSize;
            int compressedSize;
            double msTotal; // Total time to produce the Data set
            double msCompress; // .. of which Compress and write to byte array, but that's ~0
            double msSerialize; // .. of which Serialize
            // Note: The actual Deflated data is added as a binary sideload: 'SIDELOAD_KEY_SOURCE_DATA'

            // ====== For sibling commands
            String siblingCommand;
            String siblingStringData;
            // Note: Bytes are sideloaded

            // ===== Electing leader and coalescing requests
            String handlingNodename;
        }

        /**
         * A cache monitor, which can be used to log and monitor the cache server's activity.
         */
        static class CacheMonitor {
            static final int MAX_ENTRIES = 50;
            private final Logger _log;
            private final String _logPrefix;
            private final String _dataName;

            public CacheMonitor(Logger log, String logPrefix, String dataName) {
                _log = log;
                _logPrefix = logPrefix;
                _dataName = dataName;
            }

            private final List<LogEntry> logEntries = new ArrayList<>();
            private final List<ExceptionEntry> exceptionEntries = new ArrayList<>();

            public void logWithMdc(LogLevel level, Map<String, String> mdcs, MonitorCategory monitorCategory,
                    String message) {
                mdcs.forEach(MDC::put);
                try {
                    log(level, monitorCategory, message);
                }
                finally {
                    mdcs.keySet().forEach(MDC::remove);
                }
            }

            public void log(LogLevel level, MonitorCategory monitorCategory, String message) {
                synchronized (logEntries) {
                    if (logEntries.size() >= MAX_ENTRIES) {
                        logEntries.remove(0);
                    }
                    logEntries.add(new LogEntryImpl(level, monitorCategory, message));
                }
                if (DEBUG.equals(level)) {
                    if (_log.isDebugEnabled()) _log.debug(_logPrefix + monitorCategory + " [" + _dataName + "]: "
                            + message);
                }
                else if (INFO.equals(level)) {
                    if (_log.isInfoEnabled()) _log.info(_logPrefix + monitorCategory + " [" + _dataName + "]: "
                            + message);
                }
                else {
                    if (_log.isWarnEnabled()) _log.warn(_logPrefix + monitorCategory + " [" + _dataName + "]: "
                            + message);
                }
            }

            public void exception(MonitorCategory monitorCategory, String message, Throwable throwable) {
                synchronized (exceptionEntries) {
                    if (exceptionEntries.size() >= MAX_ENTRIES) {
                        exceptionEntries.remove(0);
                    }
                    exceptionEntries.add(new ExceptionEntryImpl(monitorCategory, message, throwable));
                }
                if (_log.isWarnEnabled()) _log.error(_logPrefix + monitorCategory + " [" + _dataName + "]: "
                        + message, throwable);
            }

            public List<LogEntry> getLogEntries() {
                synchronized (logEntries) {
                    return Collections.unmodifiableList(new ArrayList<>(logEntries));
                }
            }

            public List<ExceptionEntry> getExceptionEntries() {
                synchronized (exceptionEntries) {
                    return Collections.unmodifiableList(new ArrayList<>(exceptionEntries));
                }
            }

            boolean acknowledgeException(String id, String username) {
                synchronized (exceptionEntries) {
                    for (ExceptionEntry exceptionEntry : exceptionEntries) {
                        if (exceptionEntry.getId().equals(id)) {
                            // ?: Already acknowledged?
                            if (exceptionEntry.isAcknowledged()) {
                                // -> Yes, already acknowledged, so return false.
                                return false;
                            }
                            // E-> Not acknowledged, so acknowledge it, and return true.
                            exceptionEntry.acknowledge(username);
                            return true;
                        }
                    }
                    // No such exception
                    return false;
                }
            }

            int acknowledgeExceptionsUpTo(long timestamp, String username) {
                synchronized (exceptionEntries) {
                    int count = 0;
                    for (ExceptionEntry exceptionEntry : exceptionEntries) {
                        if (exceptionEntry.getTimestamp() <= timestamp) {
                            if (!exceptionEntry.isAcknowledged()) {
                                exceptionEntry.acknowledge(username);
                                count++;
                            }
                        }
                    }
                    return count;
                }
            }
        }

        static class LogEntryImpl implements LogEntry {
            private final LogLevel level;
            private final MonitorCategory _monitorCategory;
            private final String message;
            private final long timestamp = System.currentTimeMillis();

            LogEntryImpl(LogLevel level, MonitorCategory monitorCategory, String message) {
                this.level = level;
                this._monitorCategory = monitorCategory;
                this.message = message;
            }

            @Override
            public MonitorCategory getCategory() {
                return _monitorCategory;
            }

            @Override
            public LogLevel getLevel() {
                return level;
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public long getTimestamp() {
                return timestamp;
            }
        }

        static class ExceptionEntryImpl implements ExceptionEntry {
            private final MonitorCategory _monitorCategory;
            private final String message;
            private final Throwable throwable;
            private final long timestamp = System.currentTimeMillis();
            private final String id = Long.toString(Math.abs(ThreadLocalRandom.current().nextLong()), 36);

            ExceptionEntryImpl(MonitorCategory monitorCategory, String message, Throwable throwable) {
                this._monitorCategory = monitorCategory;
                this.message = message;
                this.throwable = throwable;
            }

            // Synchronized: this
            private String _acknowledgedByUser;
            // Synchronized: this
            private long _acknowledgedTimestamp;

            @Override
            public MonitorCategory getCategory() {
                return _monitorCategory;
            }

            @Override
            public long getTimestamp() {
                return timestamp;
            }

            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getMessage() {
                return message;
            }

            @Override
            public Throwable getThrowable() {
                return throwable;
            }

            @Override
            public String getThrowableAsString() {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                return sw.toString();
            }

            @Override
            public void acknowledge(String acknowledgedByUser) {
                synchronized (this) {
                    if (acknowledgedByUser == null) {
                        throw new IllegalArgumentException("acknowledgedByUser cannot be null.");
                    }
                    if (_acknowledgedByUser != null) {
                        throw new IllegalStateException("ExceptionEntry has already been acknowledged.");
                    }
                    _acknowledgedByUser = acknowledgedByUser;
                    _acknowledgedTimestamp = System.currentTimeMillis();
                }
            }

            @Override
            public Optional<String> getAcknowledgedByUser() {
                synchronized (this) {
                    return Optional.ofNullable(_acknowledgedByUser);
                }
            }

            @Override
            public OptionalLong getAcknowledgedTimestamp() {
                synchronized (this) {
                    return _acknowledgedTimestamp == 0 ? OptionalLong.empty() : OptionalLong.of(_acknowledgedTimestamp);
                }
            }
        }

        // ======== Statics ========

        static String _getCacheRequestQueue(String dataName) {
            return "mats.MatsEagerCache." + dataName + ".UpdateRequest";
        }

        static String _getBroadcastTopic(String dataName) {
            return "mats.MatsEagerCache." + dataName + ".Broadcast";
        }

        // ======== Static formatting utilities ========

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
         * Static method formatting like "8 192 B (8 KiB)".
         */
        static String _formatNiceBytes(long bytes) {
            // Format with thousand-separator bytes, then format as human-readable, same idea as _formatHtmlTimestamp
            return NUMBER_FORMAT.format(bytes) + " B (" + _formatBytes(bytes) + ")";
        }

        /**
         * Static method formatting like "<b>8 192 B</b> <i>(8 KiB)</i>".
         */
        static String _formatHtmlBytes(long bytes) {
            // Format with thousand-separator bytes, then format as human-readable, same idea as _formatHtmlTimestamp
            return "<b>" + NUMBER_FORMAT.format(bytes) + " B</b> <i>(" + _formatBytes(bytes) + ")</i>";
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
                return String.format("%.2fs", seconds);
            }
            long minutes = (long) (seconds / 60);
            if (minutes < 60) {
                return String.format("%dm %ds", minutes, (long) (seconds % 60));
            }
            long hours = minutes / 60;
            return String.format("%dh %dm", hours, minutes % 60);
        }

        /**
         * Static method that formats a millis-since-epoch timestamp as "yyyy-MM-dd HH:mm:ss.SSS".
         */
        static String _formatTimestamp(long millis) {
            return Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault())
                    .format(ISO8601_FORMATTER);
        }

        /**
         * Static method that formats a millis-since-epoch timestamp as "<b>yyyy-MM-dd HH:mm:ss.SSS</b> <i>(1h 23m
         * ago)</i>".
         */
        static String _formatHtmlTimestamp(long millis) {
            if (millis <= 0) {
                return "<i>never</i>";
            }
            // Append how long time ago this was.
            long now = System.currentTimeMillis();
            long diff = now - millis;
            return "<b>" + _formatTimestamp(millis) + "</b> <i>(" + _formatMillis(diff) + " ago)</i>";
        }
    }
}
