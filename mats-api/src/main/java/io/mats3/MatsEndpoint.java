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

package io.mats3;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.mats3.MatsConfig.StartStoppable;
import io.mats3.MatsFactory.ContextLocal;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsFactory.MatsWrapper;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.MatsStage.StageConfig;

/**
 * Represents a Mats Endpoint - you create instances from the {@link MatsFactory} (or use the Spring integration).
 * <p/>
 * <i>Implementation Note: It shall be possible to use instances of <code>MatsEndpoint</code> as keys in a
 * <code>HashMap</code>, i.e. their equals and hashCode should remain stable throughout the life of the MatsFactory -
 * and similar instances but with different MatsFactory are <i>not</i> equals. Depending on the implementation, instance
 * equality may be sufficient.</i>
 *
 * @param <R> the reply DTO type for this endpoint.
 * @param <S> the state STO type for this endpoint.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsEndpoint<R, S> extends StartStoppable {

    /**
     * @return the config for this endpoint. If endpoint is not yet started, you may invoke mutators on it.
     */
    EndpointConfig<R, S> getEndpointConfig();

    /**
     * @return the parent {@link MatsFactory}.
     */
    MatsFactory getParentFactory();

    /**
     * Adds a new stage to a multi-stage endpoint. If this is the last stage of a multi-stage endpoint, you must invoke
     * {@link #finishSetup()} afterwards - or you could instead use the {@link #lastStage(Class, ProcessReturnLambda)}
     * variant which does this automatically.
     *
     * @see MatsObject
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO. If
     *            the special type {@link MatsObject}, this stage can take any type.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<R, S, I> stage(Class<I> incomingClass, ProcessLambda<R, S, I> processor);

    /**
     * Variation of {@link #stage(Class, ProcessLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<R, S, I> stage(Class<I> incomingClass, Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            ProcessLambda<R, S, I> processor);

    /**
     * Adds the last stage to a multi-stage endpoint, which also {@link #finishSetup() finishes setup} of the endpoint.
     * Note that the last-stage concept is just a convenience that lets the developer reply from the endpoint with a
     * <code>return replyDTO</code> statement - you may just as well add a standard stage, and invoke the
     * {@link ProcessContext#reply(Object)} method. Note: If using a normal stage as the last stage, you must remember
     * to invoke {@link #finishSetup()} afterwards, as that is then not done automatically.
     *
     * @param <I>
     *            the type of the incoming DTO. The very first stage's incoming DTO is the endpoint's incoming DTO. If
     *            the special type {@link MatsObject}, this stage can take any type.
     * @param processor
     *            the lambda that will be invoked when messages arrive in the corresponding queue.
     */
    <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass, ProcessReturnLambda<R, S, I> processor);

    /**
     * Variation of {@link #lastStage(Class, ProcessReturnLambda)} that can be configured "on the fly".
     */
    <I> MatsStage<R, S, I> lastStage(Class<I> incomingClass, Consumer<? super StageConfig<R, S, I>> stageConfigLambda,
            ProcessReturnLambda<R, S, I> processor);

    /**
     * @return a List of {@link MatsStage}s, representing all the stages of the endpoint. The order is the same as the
     *         order in which the stages will be invoked. For single-staged endpoints and terminators, this list is of
     *         size 1.
     */
    List<MatsStage<R, S, ?>> getStages();

    /**
     * The lambda that shall be provided by the developer for the process stage(s) for the endpoint - provides the
     * context, state and incoming message DTO.
     */
    @FunctionalInterface
    interface ProcessLambda<R, S, I> {
        void process(ProcessContext<R> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} that makes it possible to do a "return
     * replyDto" at the end of the stage, which is just a convenient way to invoke
     * {@link MatsEndpoint.ProcessContext#reply(Object)}. Used for the last process stage of a multistage endpoint.
     */
    @FunctionalInterface
    interface ProcessReturnLambda<R, S, I> {
        R process(ProcessContext<R> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have a state, and have the same
     * return-semantics as {@link MatsEndpoint.ProcessReturnLambda ProcessReturnLambda} - used for single-stage
     * endpoints as these does not have multiple stages to transfer state between.
     * <p/>
     * However, since it is possible to send state along with the request, one may still use the
     * {@link MatsEndpoint.ProcessReturnLambda ProcessReturnLambda} for single-stage endpoints, but in this case you
     * need to code it up yourself by making a multi-stage and then just adding a single lastStage.
     */
    @FunctionalInterface
    interface ProcessSingleLambda<R, I> {
        R process(ProcessContext<R> ctx, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Specialization of {@link MatsEndpoint.ProcessLambda ProcessLambda} which does not have reply specified - used for
     * terminator endpoints. It has state, as the initiator typically have state that it wants the terminator to get.
     */
    @FunctionalInterface
    interface ProcessTerminatorLambda<S, I> {
        void process(ProcessContext<Void> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Should be invoked when all stages has been added. Will automatically be invoked by invocation of
     * {@link #lastStage(Class, ProcessReturnLambda)}, which again implies that it will be invoked when creating
     * {@link MatsFactory#single(String, Class, Class, ProcessSingleLambda) single-stage endpoints} and
     * {@link MatsFactory#terminator(String, Class, Class, ProcessTerminatorLambda) terminators} and
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda) subscription
     * terminators}.
     * <p/>
     * This sets the state of the endpoint to "finished setup", and will invoke {@link #start()} on the endpoint,
     * <b>unless</b> {@link MatsFactory#holdEndpointsUntilFactoryIsStarted()} has been invoked prior to creating the
     * endpoint.
     * <p/>
     * You may implement "delayed start" of an endpoint by <b>not</b> invoking finishedSetup() after setting it up.
     * Taking into account the first chapter of this JavaDoc, note that you must then <b>only</b> use the
     * {@link MatsFactory#staged(String, Class, Class) staged} type of Endpoint setup, as the others implicitly invokes
     * finishSetup(), and also <b>not</b> invoke {@link #lastStage(Class, ProcessReturnLambda) lastStage} on it as this
     * also implicitly invokes finishSetup(). When setting an Endpoint up without calling finishSetup(), even when
     * {@link MatsFactory#start()} is invoked, such a not-finished endpoint will then not be started. You may then later
     * invoke finishSetup(), e.g. when any needed caches are finished populated, and the endpoint will then be finished
     * and started.
     * <p/>
     * Another way to implement "delayed start" is to obviously just not create the endpoint until later: MatsFactory
     * has no <i>"that's it, now all endpoints must have been created"</i>-lifecycle stage, and can fire up new
     * endpoints until the JVM is dead.
     */
    void finishSetup();

    /**
     * Starts the endpoint (unless {@link #finishSetup()} has NOT been invoked), invoking {@link MatsStage#start()} on
     * any not-yet started stages of the endpoint (which should be all of them at application startup).
     */
    @Override
    void start();

    /**
     * Waits till all stages of the endpoint has entered their receive-loops, i.e. invokes
     * {@link MatsStage#waitForReceiving(int)} on all {@link MatsStage}s of the endpoint.
     * <p/>
     * Note: This method makes most sense for
     * {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)
     * SubscriptionTerminators}: These are based on MQ Topics, whose semantics are that if you do not listen right when
     * someone says something, you will not hear it. This means that a SubscriptionTerminator will never receive a
     * message that was sent <i>before</i> it had started the receive-loop. Thus, if you in a service-"boot" phase send
     * a message whose result will come in to a SubscriptionTerminator, you will not receive this result if the
     * receive-loop has not started. This is relevant for certain cache setups where you listen for event updates, and
     * when "booting" the cache, you need to be certain that you have started receiving updates before asking for the
     * "initial load" of the cache. It is also relevant for tools like the <code>MatsFuturizer</code>, which uses a
     * node-specific Topic for the final reply message from the requested service; If the SubscriptionTerminator has not
     * yet made it to the receive-loop, any replies will simply be lost and the future never completed.
     * <p/>
     * Note: Currently, this only holds for the initial start. If the entity has started the receive-loop at some point,
     * it will always immediately return - even though it is currently stopped.
     *
     * @return whether it was started (i.e. <code>true</code> if successfully started listening for messages).
     */
    @Override
    boolean waitForReceiving(int timeoutMillis);

    /**
     * Stops the endpoint, invoking {@link MatsStage#stop(int)} on all {@link MatsStage}s.
     *
     * @return whether it was successfully stopped (i.e. <code>true</code> if successfully stopped all listening
     *         threads).
     */
    @Override
    boolean stop(int gracefulShutdownMillis);

    /**
     * <b>Should most probably only be used for testing!</b>
     * <p/>
     * First invokes {@link #stop(int) stop(gracefulShutdownMillis)}, and if successful <b>removes</b> the endpoint from
     * its MatsFactory. This enables a new endpoint to be registered with the same endpointId as the one removed (which
     * otherwise is not accepted). This might be of interest in testing scenarios, where you want to <i>change</i> the
     * implementation of an endpoint from one test to another. There is currently no known situation where this makes
     * sense to do "in production": Once the system is set up with the correct endpoints, it should most probably stay
     * that way!
     *
     * @return whether the {@link #stop(int gracefulShutdownMillis)} was successful, and thus whether the endpoint was
     *         removed from its MatsFactory.
     */
    boolean remove(int gracefulShutdownMillis);

    /**
     * For the incoming message type, this represents the equivalent of Java's {@link Object} - a "generic" incoming
     * message whose type is not yet determined. When you know, you invoke {@link #toClass(Class)} to get it "casted"
     * (i.e. deserialized) to the specified type.
     */
    interface MatsObject {
        /**
         * Deserializes the incoming message class to the desired type - assuming that it actually is a serialized
         * representation of that class.
         *
         * @param type
         *            the class that the incoming message should be deserialized to.
         * @param <T>
         *            the type of 'type'
         * @return the deserialized object.
         * @throws IllegalArgumentException
         *             if the incoming message could not be deserialized to the desired type.
         */
        <T> T toClass(Class<T> type) throws IllegalArgumentException;
    }

    /**
     * Provides for both configuring the endpoint (before it is started), and introspecting the configuration.
     */
    interface EndpointConfig<R, S> extends MatsConfig {
        /**
         * @return the endpointId if this {@link MatsEndpoint}.
         */
        String getEndpointId();

        /**
         * @return whether this Endpoint is "subscription based", as when created with
         *         {@link MatsFactory#subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)}.
         */
        boolean isSubscription();

        /**
         * @return the class that will be sent as reply for this endpoint.
         */
        Class<R> getReplyClass();

        /**
         * @return the class used for the endpoint's state.
         */
        Class<S> getStateClass();

        /**
         * @return the class expected for incoming messages to this endpoint (decided by the first {@link MatsStage}).
         */
        Class<?> getIncomingClass();

        /**
         * Sets the origin for this Endpoint, i.e. where it was created. Use this to set something sane if the
         * {@link #getOrigin() automatically created creation info} is useless, as it is when Mats' SpringConfig defines
         * the endpoints (thus it employs this method to set something more informative). It should be a single line, no
         * line feeds, but tooling might split the String into multiple lines on the character ';'. It should definitely
         * be short, but informative.
         */
        EndpointConfig<R, S> setOrigin(String info);

        /**
         * @return some human-interpretable information about where in the codebase this Endpoint was created. If this
         *         is displayed in a multi-line capable situation, you should split on ';'. An attempt at some automatic
         *         creation is performed, based on instantiating an exception and introspecting the result. If this
         *         doesn't yield any good result, it can be overridden by {@link #setOrigin(String)}, as is done by
         *         Mats' SpringConfig.
         */
        String getOrigin();

        // Overridden to return the more specific EndpointConfig instead of MatsConfig
        @Override
        EndpointConfig<R, S> setAttribute(String key, Object value);

        // Overridden to return the more specific EndpointConfig instead of MatsConfig
        @Override
        EndpointConfig<R, S> setConcurrency(int concurrency);

        // Overridden to return the more specific EndpointConfig instead of MatsConfig
        @Override
        EndpointConfig<R, S> setInteractiveConcurrency(int concurrency);
    }

    /**
     * The part of {@link ProcessContext} that exposes the "getter" side of the context, which enables it to be exposed
     * outside of the process lambda. It is effectively the "passive" parts of the context, i.e. not initiating new
     * messages, setting properties etc. Look for usage in the "MatsFuturizer" tool in the tools-lib.
     */
    interface DetachedProcessContext {
        /**
         * @return the {@link MatsInitiate#traceId(CharSequence) trace id} for the processed message.
         * @see MatsInitiate#traceId(CharSequence)
         */
        String getTraceId();

        /**
         * @return the endpointId that is processed, i.e. the id of <i>this</i> endpoint. Should probably never be
         *         necessary, but accessible for introspection.
         */
        String getEndpointId();

        /**
         * @return the stageId that is processed, i.e. the id of <i>this</i> stage. It will be equal to
         *         {@link #getEndpointId()} for the first stage in multi-stage-endpoint, and for the sole stage of a
         *         single-stage and terminator endpoint. Should probably never be necessary, but accessible for
         *         introspection.
         */
        String getStageId();

        /**
         * @return the {@link FactoryConfig#getAppName() AppName} of the MatsFactory from which the currently processing
         *         message came. Thus, if this message is the result of a 'next' call, it will be yourself.
         */
        String getFromAppName();

        /**
         * @return the {@link FactoryConfig#getAppVersion() AppVersion} of the MatsFactory from which the currently
         *         processing message came. Thus, if this message is the result of a 'next' call, it will be yourself.
         */
        String getFromAppVersion();

        /**
         * @return the stageId from which the currently processing message came. Note that the stageId of the initial
         *         stage of an endpoint is equal to the endpointId. If this endpoint is the initial target of the
         *         initiation, this value is equal to {@link #getInitiatorId()}.
         */
        String getFromStageId();

        /**
         * @return the {@link Instant} which this message was created on the sending stage.
         */
        Instant getFromTimestamp();

        /**
         * @return the {@link FactoryConfig#getAppName() AppName} of the MatsFactory that initiated the Flow which the
         *         currently processing is a part of. Thus, if this endpoint is the initial target of the initiation,
         *         this value is equal to {@link #getFromAppName()}.
         */
        String getInitiatingAppName();

        /**
         * @return the {@link FactoryConfig#getAppVersion() AppVersion} of the MatsFactory that initiated the Flow which
         *         the currently processing is a part of. Thus, if this endpoint is the initial target of the
         *         initiation, this value is equal to {@link #getFromAppVersion()}.
         */
        String getInitiatingAppVersion();

        /**
         * @return the "initiatorId" set by the initiation with {@link MatsInitiate#from(String)}.
         */
        String getInitiatorId();

        /**
         * @return the {@link Instant} which this message was initiated, i.e. sent from a MatsInitiator (or within a
         *         Stage).
         */
        Instant getInitiatingTimestamp();

        /**
         * @return the unique messageId for the incoming message from Mats - which can be used to catch
         *         double-deliveries.
         */
        String getMatsMessageId();

        /**
         * @return the unique messageId for the incoming message, from the underlying message system - which could be
         *         used to catch double-deliveries, but do prefer {@link #getMatsMessageId()}. (For a JMS
         *         Implementation, this will be the "JMSMessageID").
         */
        String getSystemMessageId();

        /**
         * This is relevant if stashing or otherwise when a stage is accessing an external system (e.g. another MQ)
         * which have a notion of persistence.
         *
         * @return whether the current Mats flow is non-persistent - read {@link MatsInitiate#nonPersistent()}.
         */
        boolean isNonPersistent();

        /**
         * This is relevant if stashing or otherwise when a stage is accessing an external system (e.g. another MQ)
         * which have a notion of prioritization.
         *
         * @return whether the current Mats flow is interactive (prioritized) - read {@link MatsInitiate#interactive()}.
         */
        boolean isInteractive();

        /**
         * Hint to monitoring/logging/auditing systems that this call flow is not very valuable to fully audit,
         * typically because it is just a "getter" of information for display to a user, or is health check request to
         * see if the endpoint is up and answers in a timely manner.
         *
         * @return whether the current Mats flow is "no audit" - read {@link MatsInitiate#noAudit()}.
         */
        boolean isNoAudit();

        /**
         * @return the keys on which {@link #getBytes(String) bytes} lives.
         */
        Set<String> getBytesKeys();

        /**
         * Get binary "sideloads" from the incoming message.
         *
         * @param key
         *            the key for which to retrieve a binary payload from the incoming message.
         * @return the requested byte array.
         *
         * @see #getBytesKeys()
         * @see ProcessContext#addBytes(String, byte[])
         * @see #getString(String)
         * @see #getTraceProperty(String, Class)
         */
        byte[] getBytes(String key);

        /**
         * @return the keys on which {@link #getString(String) strings} lives.
         */
        Set<String> getStringKeys();

        /**
         * Get <code>String</code> "sideloads" from the incoming message.
         *
         * @param key
         *            the key for which to retrieve a String payload from the incoming message.
         * @return the requested String.
         *
         * @see #getStringKeys()
         * @see ProcessContext#addString(String, String)
         * @see #getBytes(String)
         * @see #getTraceProperty(String, Class)
         */
        String getString(String key);

        /**
         * Retrieves the Mats Trace property with the specified name, deserializing the value to the specified class,
         * using the active MATS serializer. Read more on {@link ProcessContext#setTraceProperty(String, Object)}.
         *
         * @param propertyName
         *            the name of the Mats Trace property to retrieve.
         * @param clazz
         *            the class to which the value should be deserialized.
         * @return the value of the Mats Trace property, deserialized as the specified class.
         * @see ProcessContext#setTraceProperty(String, Object)
         */
        <T> T getTraceProperty(String propertyName, Class<T> clazz);

        /**
         * @return a for-human-consumption, multi-line debug-String representing the current processing context,
         *         typically the "MatsTrace" up to the current stage. The format is utterly arbitrary, can and will
         *         change between versions and revisions, and <b>shall <u>NOT</u> be used programmatically!!</b>
         */
        String toString();
    }

    /**
     * A way for the process stage to communicate with the library, providing methods to invoke a request, send a reply
     * (for multi-stage endpoints, this provides a way to do a "early return"), initiate a new message etc.
     */
    interface ProcessContext<R> extends DetachedProcessContext {
        /**
         * Attaches a binary payload ("sideload") to the next outgoing message, being it a request, reply, next or
         * nextDirect. Note that for initiations, you have the same method on the {@link MatsInitiate} instance.
         * <p/>
         * The rationale for having this is to not have to encode a largish byte array inside the JSON structure that
         * carries the Request or Reply DTO - byte arrays represent very badly in JSON.
         * <p/>
         * Note: The byte array is not compressed (as might happen with the DTO), so if the payload is large, you might
         * want to consider compressing it before attaching it (and will then have to decompress it on the receiving
         * side).
         * <p/>
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         *
         * @param key
         *            the key on which to store the byte array payload, <code>null</code> is not allowed. The receiver
         *            will have to use this key to get the payload out again.
         * @param payload
         *            the payload to store, <code>null</code> is not allowed.
         * @see #getBytes(String)
         * @see #addString(String, String)
         * @see #getString(String)
         */
        void addBytes(String key, byte[] payload);

        /**
         * Attaches a <code>String</code> payload ("sideload") to the next outgoing message, being it a request, reply,
         * next or nextDirect. Note that for initiations, you have the same method on the {@link MatsInitiate} instance.
         * <p/>
         * The rationale for having this is to not have to encode a largish string document inside the JSON structure
         * that carries the Request or Reply DTO.
         * <p/>
         * Note: The String payload is not compressed (as might happen with the DTO), so if the payload is large, you
         * might want to consider compressing it before attaching it and instead use the
         * {@link #addBytes(String, byte[]) addBytes(..)} method (and will then have to decompress it on the receiving
         * side).
         * <p/>
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         *
         * @param key
         *            the key on which to store the String payload, <code>null</code> is not allowed. The receiver will
         *            have to use this key to get the payload out again.
         * @param payload
         *            the payload to store, <code>null</code> is not allowed.
         * @see #getString(String)
         * @see #addBytes(String, byte[])
         * @see #getBytes(String)
         */
        void addString(String key, String payload);

        /**
         * Adds a property that will "stick" with the Mats Trace from this call on out. Note that for initiations, you
         * have the same method on the {@link MatsInitiate} instance. The functionality effectively acts like a
         * {@link ThreadLocal} when compared to normal java method invocations: If the Initiator adds it, all subsequent
         * stages will see it, on any stack level, including the terminator. If a stage in a service nested some levels
         * down in the stack adds it, it will be present in all subsequent stages including all the way to the
         * Terminator. Note that any initiations within a Stage will also inherit trace properties present on the
         * Stage's incoming message.
         * <p/>
         * Possible use cases: You can for example "sneak along" some property meant for Service X through an invocation
         * of intermediate Service A (which subsequently calls Service X), where the signature (DTO) of the intermediate
         * Service A does not provide such functionality. Another usage would be to add some "global context variable",
         * e.g. "current user", that is available for any down-stream Service that requires it. Both of these scenarios
         * can obviously lead to pretty hard-to-understand code if used extensively: When employed, you should code
         * rather defensively, where if this property is not present when a stage needs it, it should throw
         * {@link MatsRefuseMessageException} and clearly explain that the property needs to be present.
         * <p/>
         * Note: This will be added to the subsequent {@link #request(String, Object) request}, {@link #reply(Object)
         * reply} or {@link #next(Object) next} message - and then cleared. Thus, if you perform multiple request or
         * next calls, then each must have their binaries, strings and trace properties set separately. (Any
         * {@link #initiate(InitiateLambda) initiations} are separate from this, neither getting nor consuming binaries,
         * strings nor trace properties set on the <code>ProcessContext</code> - they must be set on the
         * {@link MatsInitiate MatsInitiate} instance within the initiate-lambda).
         * <p/>
         * Note: <i>incoming</i> trace properties (that was present on the incoming message) will be added to <i>all</i>
         * outgoing message, <i>including initiations within the stage</i>.
         *
         * @param propertyName
         *            the name of the property
         * @param propertyValue
         *            the value of the property, which will be serialized using the active MATS serializer.
         * @see #getTraceProperty(String, Class)
         * @see #addString(String, String)
         * @see #addBytes(String, byte[])
         */
        void setTraceProperty(String propertyName, Object propertyValue);

        /**
         * Adds a measurement of a described variable, in a base unit, for this Initiation (but for timings, use
         * {@link #logTimingMeasurement(String, String, long, String...) the other method!}) - <b>be sure to understand
         * that the three String parameters are <i>constants</i> for each measurement.</b> To exemplify, you may measure
         * five different things in an Initiation, i.e. "number of items in order", "total amount for order in dollar",
         * etc - and each of these obviously have different metricId, metricDescription and possibly different baseUnit
         * from each other. BUT, the specific arguments for "number of items in order" (outside the measure itself!)
         * shall not change between one Initiation and the next (of the same "type", i.e. place in the code): e.g. the
         * metricDescription shall <b>NOT</b> be dynamically constructed to e.g. say <i>"Number of items in order 1234
         * for customer 5678"</i>.
         * <p />
         * Note: It is illegal to use the same 'metricId' for more than one measurement for a given Initiation, and this
         * also goes between 'ordinary measurements' (using this method) and
         * {@link #logTimingMeasurement(String, String, long, String...) 'timing measurements'}.
         * <p />
         * <b>Inclusion as metric by plugin 'mats-intercept-micrometer'</b>: A new meter will be created (and cached),
         * of type <code>DistributionSummary</code>, with the 'name' set to
         * <code>"mats.exec.ops.measure.{metricId}.{baseUnit}"</code> ("measure"-&gt;"time" for timings), and
         * 'description' to description. (Tags/labels already added on the meter by the plugin include 'appName',
         * 'initiatorId', 'initiatingAppName', 'stageId' (for stages), and 'initiatorName' (for inits)). Read about
         * parameter 'labelKeyValue' below.
         * <p />
         * <b>Inclusion as log line by plugin 'mats-intercept-logging'</b>: A log line will be output by each added
         * measurement, where the MDC for that log line will have an entry with key
         * <code>"mats.exec.ops.measure.{metricId}.{baseUnit}"</code>. ("measure"-&gt;"time" for timings). Read about
         * parameter 'labelKeyValue' below.
         * <p />
         * It generally makes most sense if the same metrics are added for each processing of a particular Initiation,
         * i.e. if the "number of items" are 0, then that should also be recorded along with the "total amount for order
         * in dollar" as 0, not just elided. Otherwise, your metrics will be skewed.
         * <p />
         * You should use a dot-notation for the metricId if you want to add multiple meters with a
         * hierarchical/subdivision layout. Do not use the four suffixes ".info", ".total", ".created" and ".bucket"!
         * <p />
         * The vararg 'labelKeyValue' is an optional element where the String-array consist of one or several alternate
         * key, value pairs. <b>Do not employ this feature unless you know what the effects are, and you actually need
         * it!</b> This will be added as labels/tags to the metric, and added to the SLF4J MDC for the measurement log
         * line with the key being <code>"mats.exec.ops.measure.{metricId}.{baseUnit}.tag.{labelKey}"</code>
         * ("measure"-&gt;"time" for timings), and the value being the <code>{labelValue}</code>. The keys should be
         * constants as explained for the other parameters, while the value can change, but only between a given set of
         * values (think <code>enum</code>) - using e.g. the 'customerId' as value doesn't make sense and will blow up
         * your metric cardinality. Notice that if you do employ e.g. two labels, each having one of three values,
         * <i>you'll effectively create 9 different meters</i>, where your measurement will go to one of them.
         * <p />
         * <b>NOTICE: If you want to do a timing, then instead use
         * {@link #logTimingMeasurement(String, String, long, String...)}</b>
         *
         * @param metricId
         *            constant, short, possibly dot-separated if hierarchical, id for this particular metric, e.g.
         *            "items" or "amount", or "db.query.orders". Do not use the four suffixes ".info", ".total",
         *            ".created" and ".bucket"!
         * @param metricDescription
         *            constant, textual description for this metric, e.g. "Number of items in customer order", "Total
         *            amount of customer order"
         * @param baseUnit
         *            the unit for this measurement, e.g. "quantity" (for a count measure), "dollar" (for an amount), or
         *            "bytes" (for a document size).
         * @param measure
         *            value of the measurement
         * @param labelKeyValue
         *            a String-vararg array consisting of alternate key,value pairs which will becomes labels or tags or
         *            entries for the metrics and log lines. <b>Read the JavaDoc above; the keys shall be "static" for a
         *            specific measure, while the values can change between a specific small set values.</b>
         */
        void logMeasurement(String metricId, String metricDescription, String baseUnit, double measure,
                String... labelKeyValue);

        /**
         * Same as {@link #logMeasurement(String, String, String, double, String...) addMeasurement(..)}, but
         * specifically for timings - <b>Read that JavaDoc!</b> (The reason for having timings as a specific method, is
         * that different "output methods" like Micrometer/Prometheus metrics, and slf4j logging with MDC-based
         * key/value, employ different magnitudes for the time-based measurements).
         * <p />
         * Note: It is illegal to use the same 'metricId' for more than one measurement for a given Initiation, and this
         * also goes between 'timing measurements' and {@link #logMeasurement(String, String, String, double, String...)
         * 'ordinary measurements'}.
         * <p />
         * For the metrics-plugin 'mats-intercept-micrometer' plugin, the 'baseUnit' argument is deduced to whatever is
         * appropriate for the receiving metrics system, e.g. for Prometheus it is "seconds", even though you always
         * record the measurement in nanoseconds using this method.
         * <p />
         * For the logging-plugin 'mats-intercept-logging' plugin, the timing in the log line will be in milliseconds
         * (with fractions), even though you always record the measurement in nanoseconds using this method.
         *
         * @param metricId
         *            constant, short, possibly dot-separated if hierarchical, id for this particular metric, e.g.
         *            "db.query.orders" or "calcprofit". Do not use the four suffixes ".info", ".total", ".created" and
         *            ".bucket"!
         * @param metricDescription
         *            constant, textual description for this metric, e.g. "Time taken to execute order query", "Time
         *            taken to calculate profit or loss".
         * @param nanos
         *            time taken <b>in nanoseconds</b>.
         * @param labelKeyValue
         *            a String-vararg array consisting of alternate key,value pairs which will becomes labels or tags or
         *            entries for the metrics and log lines. Read the JavaDoc at
         *            {@link #logMeasurement(String, String, String, double, String...) addMeasurement(..)}
         */
        void logTimingMeasurement(String metricId, String metricDescription, long nanos, String... labelKeyValue);

        /**
         * Returns a binary representation of the current Mats flow's incoming execution point, which can be
         * {@link MatsInitiate#unstash(byte[], Class, Class, Class, ProcessLambda) unstashed} again at a later time
         * using the {@link MatsInitiator}, thereby providing a simplistic "continuation" feature in Mats. You will have
         * to find storage for these bytes yourself - an obvious place is the co-transactional database that the stage
         * typically has available. This feature gives the ability to "pause" the current Mats flow, and later restore
         * the execution from where it left off, probably with some new information that have been gathered in the
         * meantime. This can typically relieve the Mats Stage Processing thread from having to wait for another
         * service's execution (whose execution must then be handled by some other thread). This could be a longer
         * running process, or a process whose execution time is variable, maybe residing on a Mats-external service
         * structure: E.g. some REST service that sometimes lags, or sometimes is down in smaller periods. Or a service
         * on a different Message Broker. Once this "Mats external" processing has finished, that thread can invoke
         * {@link MatsInitiate#unstash(byte[], Class, Class, Class, ProcessLambda) unstash(stashBytes,...)} to get the
         * Mats flow going again. Notice that functionally, the unstash-operation is a kind of initiation, only that
         * this type of initiation doesn't start a <i>new</i> Mats flow, rather <i>continuing an existing flow</i>.
         * <p/>
         * <b>Notice that this feature should not typically be used to "park" a Mats flow for days.</b> One might have a
         * situation where a part of an order flow potentially needs manual handling, e.g. validating a person's
         * identity if this has not been validated before. It might (should!) be tempting to employ the stash function
         * then: Stash the Mats flow in a database. Make a GUI where the ID-validation can be performed by some
         * employee. When the ID is either accepted or denied, you unstash the Mats flow with the result, getting a very
         * nice continuous mats flow for new orders which is identical whether or not ID validation needs to be
         * performed. However, if this ID-validation process can take days or weeks to execute, it will be a poor
         * candidate for the stash-feature. The reason is that embedded within the execution context which you get a
         * binary serialization of, there might be several serialized <i>state</i> representations of the endpoints
         * laying upstream of this call flow. When you "freeze" these by invoking stash, you have immediately made a
         * potential future deserialization-crash if you change the code of those upstream endpoints (which quite
         * probably resides in different code bases than the one employing the stash feature), specifically changes of
         * the state classes they employ: When you deploy these code changes while having multiple flows frozen in
         * stashes, you will have a problem when they are later unstashed and the Mats flow returns to those endpoints
         * whose state classes won't deserialize back anymore. It is worth noting that you always have these problems
         * when doing deploys where the state classes of Mats endpoints change - it is just that usually, there won't be
         * any, and at least not many, such flows in execution at the precise deploy moment (and also, that changing the
         * state classes are in practice really not that frequent). However, by stashing over days, instead of a normal
         * Mats flow that take seconds, you massively increase the time window in which such deserialization problems
         * can occur. You at least have to consider this if employing the stash-functionality.
         * <p/>
         * <b>Note about data and metadata which should be stored along with the stash-bytes:</b> You only get a binary
         * serialized incoming execution context in return from this method (which includes the incoming message,
         * incoming state, the execution stack and {@link ProcessContext#getTraceProperty(String, Class) trace
         * properties}, but not "sideloaded" {@link ProcessContext#getBytes(String) bytes} and
         * {@link ProcessContext#getString(String) strings}). The returned byte array are utterly opaque seen from the
         * Mats API side (however, depending on the serialization mechanism employed in the Mats implementation, you
         * might be able to peek into them anyway - but this should at most be used for debugging/monitoring
         * introspection). Therefore, any information from the incoming message, or from your state object, or anything
         * else from the {@link DetachedProcessContext} which is needed to actually execute the job that should be
         * performed outside of the Mats flow, <u>must be picked out manually</u> before exiting the process lambda.
         * This also goes for "sideloaded" objects ({@link ProcessContext#getBytes(String) bytes} and
         * {@link ProcessContext#getString(String) strings}) - which will not be available inside the unstashed process
         * lambda (they are not a part of the stash-bytes). Also, you should for debugging/monitoring purposes also
         * store at least the Mats flow's {@link ProcessContext#getTraceId() TraceId} and a timestamp along with the
         * stash and data. You should probably also have some kind of monitoring / health checks for stashes that have
         * become stale - i.e. stashes that have not been unstashed for a considerable time, and whose Mats flow have
         * thus stopped up, and where the downstream endpoints/stages therefore will not get invoked.
         * <p/>
         * <b>Notes:</b>
         * <ul>
         * <li>Invoking {@code stash()} will not affect the stage processing in any way other than producing a
         * serialized representation of the current incoming execution point. You can still send out messages. You could
         * even reply, but then, what would be the point of stashing?</li>
         * <li>Repeated invocations within the same stage will yield (effectively) the same stash, as any processing
         * done inside the stage before invoking {@code stash()} don't affect the <i>incoming</i> execution point.</li>
         * <li>You will have to exit the current process lambda yourself - meaning that this cannot be used in a
         * {@link MatsEndpoint#lastStage(Class, ProcessReturnLambda) lastStage}, as you cannot return from such a stage
         * without actually sending a reply ({@code return null} replies with {@code null}). Instead employ a
         * {@link MatsEndpoint#stage(Class, ProcessLambda) normal stage}, using {@link ProcessContext#reply(Object)} to
         * return a reply if needed.</li>
         * <li>Mats won't care if you unstash() the same stash multiple times, but your downstream parts of the Mats
         * flow might find this a bit strange.</li>
         * </ul>
         *
         * @return a binary representation of the current Mats flow's incoming execution point (i.e. any incoming state
         *         and the incoming message - along with the Mats flow stack at this point). It shall start with the 4
         *         ASCII letters "MATS", and then 4 more letters representing which mechanism is employed to construct
         *         the rest of the byte array.
         */
        byte[] stash();

        /**
         * Sends a request message, meaning that the specified endpoint will be invoked, with the reply-to endpointId
         * set to the next stage in the multi-stage endpoint.
         * <p/>
         * This will throw if the current process stage is a terminator, single-stage endpoint or the last endpoint of a
         * multi-stage endpoint, as there then is no next stage to reply to.
         * <p/>
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} messages; OR a
         * single {@link #reply(Object) reply}, {@link #next(Object) next}, {@link #nextDirect(Object) nextDirect}, or
         * {@link #goTo(String, Object) goTo} message. The reason that multiple requests are allowed is that this could
         * be used in a scatter-gather scenario - where the replies come in to the next stage <i>of the same
         * endpoint</i>. However, multiple replies to the <i>invoking</i> endpoint makes very little sense, which is why
         * only one reply is allowed, and it cannot be combined with request or next, nor goto, as then the next stage
         * could also perform a reply.
         * <p/>
         * Note: The current state and DTO is serialized <i>when invoking this method</i>. This means that in case of
         * multiple requests, you may change the state in between each request, and the next stage will get different
         * "incoming states" for each of the replies, which may be of use in a scatter-gather scenario.
         *
         * @param endpointId
         *            which endpoint to invoke
         * @param requestDto
         *            the message that should be sent to the specified endpoint.
         */
        MessageReference request(String endpointId, Object requestDto);

        /**
         * Sends a reply to the requesting service. When returning an object from a
         * {@link MatsEndpoint#lastStage(Class, ProcessReturnLambda) lastStage} lambda, this is the method that actually
         * gets invoked.
         * <p/>
         * This will be ignored if there is no endpointId on the stack, i.e. if this endpoint it is semantically a
         * terminator (the <code>replyTo</code> of an initiation's request), or if it is the last stage of an endpoint
         * that was invoked directly (using {@link MatsInitiate#send(Object) MatsInitiate.send(msg)}).
         * <p/>
         * It is possible to do "early return" in a multi-stage endpoint by invoking this method in a stage that is not
         * the last.
         * <p/>
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} messages; OR a
         * single {@link #reply(Object) reply}, {@link #next(Object) next}, {@link #nextDirect(Object) nextDirect}, or
         * {@link #goTo(String, Object) goTo} message. The reason that multiple requests are allowed is that this could
         * be used in a scatter-gather scenario - where the replies come in to the next stage <i>of the same
         * endpoint</i>. However, multiple replies to the <i>invoking</i> endpoint makes very little sense, which is why
         * only one reply is allowed, and it cannot be combined with request or next, nor goto, as then the next stage
         * could also perform a reply.
         * <p/>
         * Note: The current state and DTO is serialized <i>when invoking this method</i>. Any changes to the state
         * object or the DTO performed afterwards won't be present on the reply-receiving stage.
         *
         * @param replyDto
         *            the reply DTO to return to the invoker.
         */
        MessageReference reply(R replyDto);

        /**
         * Sends a message which passes the control to the next stage of a multi-stage endpoint. The functionality is
         * meant for doing conditional requests, e.g.:
         *
         * <pre>
         * if (something missing) {
         *     request another endpoint with reply coming to next stage.
         * }
         * else {
         *     pass to next stage
         * }
         * </pre>
         *
         * This can be of utility if an endpoint in some situations requires more information from a collaborating
         * endpoint, while in other situations it does not require that information.
         * <p/>
         * <b>Note: You should rather use {@link #nextDirect(Object)} if your transactional demarcation needs allows
         * it!</b>
         * <p/>
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} messages; OR a
         * single {@link #reply(Object) reply}, {@link #next(Object) next}, {@link #nextDirect(Object) nextDirect}, or
         * {@link #goTo(String, Object) goTo} message. The reason that multiple requests are allowed is that this could
         * be used in a scatter-gather scenario - where the replies come in to the next stage <i>of the same
         * endpoint</i>. However, multiple replies to the <i>invoking</i> endpoint makes very little sense, which is why
         * only one reply is allowed, and it cannot be combined with request or next, nor goto, as then the next stage
         * could also perform a reply.
         * <p/>
         * Note: The current state and DTO is serialized <i>when invoking this method</i>. Any changes to the state
         * object or the DTO performed afterwards won't be present in the subsequent stage.
         *
         * @see #nextDirect(Object)
         *
         * @param nextDto
         *            the object for the next stage's incoming DTO, which must match what the next stage expects. When
         *            using this method to skip a request, it probably often makes sense to set it to <code>null</code>,
         *            which the next stage then must handle correctly.
         */
        MessageReference next(Object nextDto);

        /**
         * Specialized, less resource demanding, and faster "direct" variant of {@link #next(Object)} which executes the
         * next stage of a multi-stage endpoint within the same stage processor and transactional demarcation that this
         * stage is in - that is, there is no actual message sent. This ensures that you neither incur any transactional
         * cost nor the overhead of serialization and sending a message on the message queue with subsequent receiving
         * and deserialization.
         * <p/>
         * The functionality is meant for doing conditional calls, e.g.:
         *
         * <pre>
         * if (something missing) {
         *     request another endpoint with reply coming to next stage.
         * }
         * else {
         *     directly execute the next stage
         * }
         * </pre>
         *
         * This can be of utility if an endpoint in some situations requires more information from a collaborating
         * endpoint, while in other situations it does not require that information. A scenario nextDirect is
         * particularly good for is lazy cache population where the caching of the information only incurs cost if the
         * information is missing, since if the information is present in the cache, you can do a close to zero cost
         * nextDirect invocation. Had you been using {@link #next(Object) ordinary next}, you would incur the cost of
         * sending and receiving a message even though the information is present in the cache.
         * <p/>
         * Implementation details which are unspecified and whose effects or non-effects shall not be relied on:
         * <ul>
         * <li>It is unspecified which thread runs the next stage: The invocation may be done by the same stage
         * processor thread which executes this stage, or the execution may be done by another thread. Therefore, you
         * cannot expect any ThreadLocals set in this lambda to to be present in the next.</li>
         * <li>It is unspecified how the next lambda is invoked: You cannot expect this to behave like a method call to
         * the next lambda (i.e. the next stage has been executed when the method returns), nor can you expect the
         * lambda to be executed only when the current lambda has exited (i.e. behaving like a message). Therefore,
         * invocation of this method should be the very last operation in the current lambda so that this does not make
         * a difference.</li>
         * </ul>
         * <p/>
         * Some notes:
         * <ul>
         * <li>There is no new <i>message system message</i> created for a nextDirect, so e.g. messageIds when in the
         * next stage will refer to the previous stage's incoming message.</li>
         * <li>Since there is no message system message, the message broker won't see any trace of a nextDirect. This
         * also means that you'll get less counts of messages on the next stage's queue.</li>
         * <li>The stage processing thread for the stage doing nextDirect is busy while the next stage executes, either
         * by actually executing it, or waiting for the execution, depending on the Mats implementation. If the next
         * stage is slow, any queue build-up will therefore happen on the stage that performed the nextDirect. Any
         * adjustment of concurrency must be done on the stage actually receiving message system messages.</li>
         * <li>It is legal to do "series" of nextDirects, i.e. that one stage does a nextDirect, and then the next stage
         * also does a nextDirect.</li>
         * <li>Neither the state object or the DTO is serialized and deserialized, but instead passed directly to the
         * next stage. This ties back to the point about unspecified way of invoking the next lambda and that the
         * invocation of nextDirect should be the last operation in the current stage: It is unspecified whether a
         * change to the state object or DTO <i>after</i> the invocation of nextDirect will be visible to the next
         * stage. (This as opposed to all the other message sending methods, where it is specified that the objects are
         * serialized upon method invocation, and any changes done afterwards will not be visible to the receiver.)</li>
         * <li>If the next stage throws an exception, it will be the current stage that eventually DLQs - this might be
         * confusing when researching an error.</li>
         * <li>If employing MatsTrace (which the JMS impl do), it will contain no record of the nextDirect having been
         * performed. For example, if the message crops up on a DLQ, any nextDirects in the flow will not be
         * visible.</li>
         * <li>There are implications for the Interceptors, some of which might be subtle. E.g. any preprocess and
         * deserialization timings, and message sizes, will be 0. Also, read up on the <code>stageCompleted(..)</code>
         * vs. <code>stageCompletedNextDirect(..)</code></li>
         * </ul>
         * <p/>
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} messages; OR a
         * single {@link #reply(Object) reply}, {@link #next(Object) next}, {@link #nextDirect(Object) nextDirect}, or
         * {@link #goTo(String, Object) goTo} message. The reason that multiple requests are allowed is that this could
         * be used in a scatter-gather scenario - where the replies come in to the next stage <i>of the same
         * endpoint</i>. However, multiple replies to the <i>invoking</i> endpoint makes very little sense, which is why
         * only one reply is allowed, and it cannot be combined with request or next, nor goto, as then the next stage
         * could also perform a reply.
         */
        void nextDirect(Object nextDirectDto);

        /**
         * Sends a message which passes the current call stack over to another endpoint, so that when that endpoint
         * replies, it will return to the endpoint which invoked this endpoint.
         * <p/>
         * This would be of use in dispatcher scenarios, where you might have a case-style evaluation of the incoming
         * message, and then either handle it yourself, or send the control over to some other endpoint (possibly one of
         * several other endpoints) - so that when they again reply, it will be to the original caller.
         * <p/>
         * A specific dispatcher-like situation where this could be of use, is if you have an endpoint that for some
         * specific (known) entities consumes a particularly large amount of memory. For example, you might have a
         * specific set of frequent customers which when loaded takes much more memory than a normal customer. If you
         * get an influx of orders for these customers at the same time, your standard endpoint with a high concurrency
         * could lead to an out-of-memory situation. A solution here could be to instantiate that same endpoint code at
         * two different endpointIds - the public one, and a private variant with much lower concurrency. The standard,
         * public endpoint would at the initial stage evaluate if this was one of the known memory-hogging customers,
         * and if so, make a context.goto(..) over to the other private endpoint with lower concurrency thereby ensuring
         * that the memory usage would be contained. (The endpoint would have to evaluate if it is the public or private
         * instance wrt. whether it should do the eval-then-goto: Either by looking at its endpointId (check if it is
         * the private, low-concurrency variant), or by use of the {@link #goTo(String, Object, Object)
         * initialState}-feature, or by modifying the DTO before goTo, or by
         * {@link ProcessContext#addString(String, String) sideloads}).
         * <p/>
         * Another use is <i>tail calls</i>, whereby one endpoint A does some preprocessing, and then invokes another
         * endpoint B, but where endpoint A really just want to directly reply with the reply from endpoint B. The extra
         * reply stage of endpoint A is thus totally useless and just incurs additional message passing and processing.
         * You can instead just goTo endpoint B, which achieves just this outcome: When endpoint B now replies, <i>it
         * will reply to the caller of endpoint A</i>.
         * <p/>
         * Note: Legal outgoing flows: Either one or several {@link #request(String, Object) request} messages; OR a
         * single {@link #reply(Object) reply}, {@link #next(Object) next}, {@link #nextDirect(Object) nextDirect}, or
         * {@link #goTo(String, Object) goTo} message. The reason that multiple requests are allowed is that this could
         * be used in a scatter-gather scenario - where the replies come in to the next stage <i>of the same
         * endpoint</i>. However, multiple replies to the <i>invoking</i> endpoint makes very little sense, which is why
         * only one reply is allowed, and it cannot be combined with request or next, nor goto, as then the next stage
         * could also perform a reply.
         * <p/>
         * Note: The current state and DTO is serialized <i>when invoking this method</i>. Any changes to the state
         * object or the DTO performed afterwards won't be present for the targeted endpoint.
         *
         * @param endpointId
         *            which endpoint to go to.
         * @param gotoDto
         *            the message that should be sent to the specified endpoint. Will in a dispatcher scenario often be
         *            the incoming DTO for the current endpoint, while in a tail call situation be the requestDto for
         *            the target endpoint.
         */
        MessageReference goTo(String endpointId, Object gotoDto);

        /**
         * <b>Variation of {@link #goTo(String, Object)} method</b>, where the incoming state is sent along.
         * <p/>
         * <b>This only makes sense if the same code base "owns" both the current endpoint, and the endpoint to which
         * this message is sent.</b>
         *
         * @param endpointId
         *            which endpoint to go to.
         * @param gotoDto
         *            the message that should be sent to the specified endpoint. Will in a dispatcher scenario often be
         *            the incoming DTO for the current endpoint, while in a tail call situation be the requestDto for
         *            the target endpoint.
         * @param initialTargetSto
         *            the object which the target endpoint will get as its initial stage STO (State Transfer Object).
         */
        MessageReference goTo(String endpointId, Object gotoDto, Object initialTargetSto);

        /**
         * Initiates a new message out to an endpoint. This is effectively the same as invoking
         * {@link MatsInitiator#initiate(InitiateLambda lambda) the same method} on a {@link MatsInitiator} gotten via
         * {@link MatsFactory#getOrCreateInitiator(String)}, only that this way works within the transactional context
         * of the {@link MatsStage} which this method is invoked within. Also, the traceId and from-endpointId is
         * predefined, but it is still recommended to set the traceId, as that will append the new string on the
         * existing traceId (joined with a "|" character), making log tracking (e.g. when debugging) better. Note: This
         * prepending will not happen if the first char of the TraceId is a "!" character - which will be removed.
         * <p/>
         * <b>IMPORTANT NOTICE!!</b> The {@link MatsInitiator} returned from {@link MatsFactory#getDefaultInitiator()
         * MatsFactory.getDefaultInitiator()} is "magic" in that when employed from within a Mats Stage's context
         * (thread), it works exactly as this method: Any initiations performed participates in the Mats Stage's
         * transactional demarcation. Read more at the JavaDoc of default initiator's
         * {@link MatsFactory#getDefaultInitiator() JavaDoc}..
         *
         * @param lambda
         *            provides the {@link MatsInitiate} instance on which to create the message to be sent.
         */
        void initiate(InitiateLambda lambda);

        /**
         * The Runnable will be performed after messaging and external resources (DB) have been committed. An example
         * can be if the Mats-lambda inserts a row in a database that should be processed by some other component (i.e.
         * a service running with some Threads), and thus wants to wake up that component telling it that new work is
         * available. Problem is then that if this "wakeUp()" call is done within the lambda, the row is not technically
         * there yet - as we're still within the SQL transaction demarcation. Therefore, if the process-service wakes up
         * really fast and tries to find the new work, it will not see anything yet. (It might then presume that e.g.
         * another node of the service-cluster took care of whatever woke it up, and go back to sleep.)
         * <p/>
         * Note: This is per processing; Setting it is only relevant for the current message. If you invoke the method
         * more than once, only the last Runnable will be run. If you set it to <code>null</code>, you "cancel" any
         * previously set Runnable.
         * <p/>
         * Note: If any Exception is raised from the stage lambda code after the Runnable has been set, or any Exception
         * is raised by the processing or committing, the Runnable will not be run.
         * <p/>
         * Note: If the <code>doAfterCommit</code> Runnable throws a {@link RuntimeException}, it will be logged on
         * ERROR level, then ignored.
         *
         * @param runnable
         *            the code to run right after the transaction of both external resources and messaging has been
         *            committed. Setting to <code>null</code> "cancels" any previously set Runnable.
         */
        void doAfterCommit(Runnable runnable);

        /**
         * Provides a way to get hold of (optional) attributes/objects from the Mats implementation, either specific to
         * the Mats implementation in use, or configured into this instance of the Mats implementation. Is mirrored by
         * the same method at {@link MatsInitiate#getAttribute(Class, String...)}. There is also a
         * ThreadLocal-accessible version at {@link ContextLocal#getAttribute(Class, String...)}.
         * <p/>
         * Mandatory: If the Mats implementation has a transactional SQL Connection, it shall be available by
         * <code>'context.getAttribute(Connection.class)'</code>.
         *
         * @param type
         *            The expected type of the attribute
         * @param name
         *            The (optional) (hierarchical) name(s) of the attribute.
         * @param <T>
         *            The type of the attribute.
         * @return Optional of the attribute in question, the optionality pointing out that it depends on the Mats
         *         implementation or configuration whether it is available.
         *
         * @see ProcessContext#getAttribute(Class, String...)
         * @see ContextLocal#getAttribute(Class, String...)
         */
        <T> Optional<T> getAttribute(Class<T> type, String... name);

        /**
         * @return default <code>this</code> for implementations, overridden by wrappers.
         */
        default ProcessContext<R> unwrapFully() {
            return this;
        }
    }

    /**
     * Can be thrown by the {@link ProcessLambda} of the {@link MatsStage}s to denote that it would prefer this message
     * to be instantly put on a <i>Dead Letter Queue</i> (the stage processing, including any database actions, will
     * still be rolled back as with any other exception thrown out of a ProcessLambda). This is just advisory - the
     * message might still be presented a number of times to the {@link MatsStage} in question even though the stage
     * throws <code>MatsRefuseMessageException</code> every time.
     */
    class MatsRefuseMessageException extends Exception {
        public MatsRefuseMessageException(String message, Throwable cause) {
            super(message, cause);
        }

        public MatsRefuseMessageException(String message) {
            super(message);
        }
    }

    /**
     * A base Wrapper for {@link ProcessContext}, which simply implements ProcessContext, takes a ProcessContext
     * instance and forwards all calls to that.
     */
    class ProcessContextWrapper<R> implements MatsWrapper<ProcessContext<R>>, ProcessContext<R> {
        /**
         * This field is private - all methods invoke {@link #unwrap()} to get the instance, which you should too if you
         * override any methods. If you want to take control of the wrapped ProcessContext instance, then override
         * {@link #unwrap()}.
         */
        private ProcessContext<R> _targetProcessContext;

        /**
         * Standard constructor, taking the wrapped {@link ProcessContext} instance.
         *
         * @param targetProcessContext
         *            the {@link ProcessContext} instance which {@link #unwrap()} will return (and hence all forwarded
         *            methods will use).
         */
        public ProcessContextWrapper(ProcessContext<R> targetProcessContext) {
            setWrappee(targetProcessContext);
        }

        /**
         * No-args constructor, which implies that you either need to invoke {@link #setWrappee(ProcessContext)} before
         * publishing the instance (making it available for other threads), or override {@link #unwrap()} to provide the
         * desired {@link ProcessContext} instance. In these cases, make sure to honor memory visibility semantics -
         * i.e. establish a happens-before edge between the setting of the instance and any other threads getting it.
         */
        public ProcessContextWrapper() {
            /* no-op */
        }

        /**
         * Sets the wrapped {@link ProcessContext}, e.g. in case you instantiated it with the no-args constructor. <b>Do
         * note that the field holding the wrapped instance is not volatile nor synchronized</b>. This means that if you
         * want to set it after it has been published to other threads, you will have to override both this method and
         * {@link #unwrap()} to provide for needed memory visibility semantics, i.e. establish a happens-before edge
         * between the setting of the instance and any other threads getting it. A <code>volatile</code> field would
         * work nice.
         *
         * @param targetProcessContext
         *            the {@link ProcessContext} which is returned by {@link #unwrap()}, unless that is overridden.
         */
        public void setWrappee(ProcessContext<R> targetProcessContext) {
            _targetProcessContext = targetProcessContext;
        }

        /**
         * @return the wrapped {@link ProcessContext}. All forwarding methods invokes this method to get the wrapped
         *         {@link ProcessContext}, thus if you want to get creative wrt. how and when the ProcessContext is
         *         decided, you can override this method.
         */
        public ProcessContext<R> unwrap() {
            if (_targetProcessContext == null) {
                throw new IllegalStateException("MatsEndpoint.ProcessContextWrapper.unwrap():"
                        + " The '_targetProcessContext' is not set!");
            }
            return _targetProcessContext;
        }

        @Override
        public ProcessContext<R> unwrapFully() {
            return unwrap().unwrapFully();
        }

        @Override
        public String getTraceId() {
            return unwrap().getTraceId();
        }

        @Override
        public String getEndpointId() {
            return unwrap().getEndpointId();
        }

        @Override
        public String getStageId() {
            return unwrap().getStageId();
        }

        @Override
        public String getFromAppName() {
            return unwrap().getFromAppName();
        }

        @Override
        public String getFromAppVersion() {
            return unwrap().getFromAppVersion();
        }

        @Override
        public String getFromStageId() {
            return unwrap().getFromStageId();
        }

        @Override
        public Instant getFromTimestamp() {
            return unwrap().getFromTimestamp();
        }

        @Override
        public String getInitiatingAppName() {
            return unwrap().getInitiatingAppName();
        }

        @Override
        public String getInitiatingAppVersion() {
            return unwrap().getInitiatingAppVersion();
        }

        @Override
        public String getInitiatorId() {
            return unwrap().getInitiatorId();
        }

        @Override
        public Instant getInitiatingTimestamp() {
            return unwrap().getInitiatingTimestamp();
        }

        @Override
        public String getMatsMessageId() {
            return unwrap().getMatsMessageId();
        }

        @Override
        public String getSystemMessageId() {
            return unwrap().getSystemMessageId();
        }

        @Override
        public boolean isNonPersistent() {
            return unwrap().isNonPersistent();
        }

        @Override
        public boolean isInteractive() {
            return unwrap().isInteractive();
        }

        @Override
        public boolean isNoAudit() {
            return unwrap().isNoAudit();
        }

        @Override
        public Set<String> getBytesKeys() {
            return unwrap().getBytesKeys();
        }

        @Override
        public byte[] getBytes(String key) {
            return unwrap().getBytes(key);
        }

        @Override
        public Set<String> getStringKeys() {
            return unwrap().getStringKeys();
        }

        @Override
        public String getString(String key) {
            return unwrap().getString(key);
        }

        @Override
        public <T> T getTraceProperty(String propertyName, Class<T> clazz) {
            return unwrap().getTraceProperty(propertyName, clazz);
        }

        @Override
        public void addBytes(String key, byte[] payload) {
            unwrap().addBytes(key, payload);
        }

        @Override
        public void addString(String key, String payload) {
            unwrap().addString(key, payload);
        }

        @Override
        public void setTraceProperty(String propertyName, Object propertyValue) {
            unwrap().setTraceProperty(propertyName, propertyValue);
        }

        @Override
        public void logMeasurement(String metricId, String metricDescription, String baseUnit, double measure,
                String... labelKeyValue) {
            unwrap().logMeasurement(metricId, metricDescription, baseUnit, measure, labelKeyValue);
        }

        @Override
        public void logTimingMeasurement(String metricId, String metricDescription, long nanos,
                String... labelKeyValue) {
            unwrap().logTimingMeasurement(metricId, metricDescription, nanos, labelKeyValue);
        }

        @Override
        public byte[] stash() {
            return unwrap().stash();
        }

        @Override
        public MessageReference request(String endpointId, Object requestDto) {
            return unwrap().request(endpointId, requestDto);
        }

        @Override
        public MessageReference reply(R replyDto) {
            return unwrap().reply(replyDto);
        }

        @Override
        public MessageReference next(Object nextDto) {
            return unwrap().next(nextDto);
        }

        @Override
        public void nextDirect(Object nextDirectDto) {
            unwrap().nextDirect(nextDirectDto);
        }

        @Override
        public MessageReference goTo(String endpointId, Object gotoDto) {
            return unwrap().goTo(endpointId, gotoDto);
        }

        @Override
        public MessageReference goTo(String endpointId, Object gotoDto, Object initialTargetSto) {
            return unwrap().goTo(endpointId, gotoDto, initialTargetSto);
        }

        @Override
        public void initiate(InitiateLambda lambda) {
            unwrap().initiate(lambda);
        }

        @Override
        public void doAfterCommit(Runnable runnable) {
            unwrap().doAfterCommit(runnable);
        }

        @Override
        public <T> Optional<T> getAttribute(Class<T> type, String... name) {
            return unwrap().getAttribute(type, name);
        }

        @Override
        public String toString() {
            return "ProcessContextWrapper[" + this.getClass().getSimpleName() + "]:" + unwrap().toString();
        }
    }
}
