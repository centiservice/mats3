package io.mats3;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.mats3.MatsConfig.StartStoppable;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsStage.StageConfig;

/**
 * The start point for all interaction with MATS - you need to get hold of an instance of this interface to be able to
 * code and configure MATS endpoints, and to perform initiations (i.e. send a message, perform a request, publish a
 * message). This is an implementation specific feature (you might want a JMS-specific {@link MatsFactory}, backed by a
 * ActiveMQ-specific JMS ConnectionFactory). <i>An alternative is to use the SpringConfig "mats-spring" integration,
 * where you do not explicitly use the MatsFactory to code and configure MATS endpoints. Employing SpringConfig of Mats,
 * you'll need to get an instance of MatsFactory into the Spring context.</i>
 * <p/>
 * It is worth realizing that all of the methods {@link #staged(String, Class, Class, Consumer) staged(...config)};
 * {@link #single(String, Class, Class, ProcessSingleLambda) single(...)} and
 * {@link #single(String, Class, Class, Consumer, Consumer, ProcessSingleLambda) single(...configs)};
 * {@link #terminator(String, Class, Class, ProcessTerminatorLambda) terminator(...)} and
 * {@link #terminator(String, Class, Class, Consumer, Consumer, ProcessTerminatorLambda) terminator(...configs)} are
 * just convenience methods to the one {@link #staged(String, Class, Class) staged(...)}. They could just as well have
 * resided in a utility-class. They are included in the API since these relatively few methods seem to cover most
 * scenarios. <i>(Exception to this are the two
 * {@link #subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda) subscriptionTerminator(...)} and
 * {@link #subscriptionTerminator(String, Class, Class, Consumer, Consumer, ProcessTerminatorLambda)
 * subscriptionTerminator(...Consumers)}, as they have different semantics, read the JavaDoc).</i>
 * <p/>
 * Regarding order of the Reply Message, State and Incoming Message, which can be a bit annoying to remember when
 * creating endpoints, and when writing {@link ProcessLambda process lambdas}: They are always ordered like this: <b>R,
 * S, I</b>, i.e. <i>Reply, State, Incoming</i>. This is to resemble a method signature having the implicit {@code this}
 * (or {@code self}) reference as the first argument: {@code ReturnType methodName(this, arguments)}. Thus, if you
 * remember that Mats is created to enable you to write messaging oriented endpoints that <i>look like</i> they are
 * methods, then it might stick! The process lambda thus has args (context, state, incomingMsg), unless it lacks state.
 * Even if it lacks state, the context is always first, and the incoming message is always last.<br/>
 * Examples:
 * <ul>
 * <li>In a Terminator, you have an incoming message, and state (which the initiator set) - a terminator doesn't reply.
 * The params of the {@link #terminator(String, Class, Class, ProcessTerminatorLambda) terminator}-method of MatsFactory
 * is thus [EndpointId, State Class, Incoming Class, process lambda]. The lambda params of the terminator will be:
 * [Context, State, Incoming]</li>
 * <li>For a SingleStage endpoint, you will have incoming message, and reply message - there is no state, since that is
 * an object that traverses between the stages in a multi-stage endpoint, and this endpoint is just a single stage. The
 * params of the {@link #single(String, Class, Class, ProcessSingleLambda)} single stage}-method of MatsFactory is thus
 * [EndpointId, Reply Class, Incoming Class, process lambda]. The lambda params will then be: [Context, Incoming] - the
 * reply type is the the return type of the process lambda.</li>
 * </ul>
 * Note: It should be possible to use instances of <code>MatsFactory</code> as keys in a <code>HashMap</code>, i.e.
 * their equals and hashCode should remain stable throughout the life of the MatsFactory. Depending on the
 * implementation, instance equality may be sufficient. Note that the {@link MatsFactoryWrapper} is implemented to
 * forward equals and hashCode to wrappee.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsFactory extends StartStoppable {
    /**
     * @return the {@link FactoryConfig} on which to configure the factory, e.g. defaults for concurrency.
     */
    FactoryConfig getFactoryConfig();

    /**
     * Simple Consumer&lt;MatsConfig&gt-implementation that does nothing, for use where you e.g. only need to config the
     * stage, not the endpoint, in e.g. the method
     * {@link #terminator(String, Class, Class, Consumer, Consumer, ProcessTerminatorLambda)}.
     */
    Consumer<MatsConfig> NO_CONFIG = config -> {
        /* no-op */ };

    /**
     * Sets up a {@link MatsEndpoint} on which you will add stages. The first stage is the one that will receive the
     * incoming (typically request) DTO, while any subsequent stage is invoked when the service that the previous stage
     * sent a request to, replies.
     * <p/>
     * Unless the state object was sent along with the {@link MatsInitiate#request(Object, Object) request} or
     * {@link MatsInitiate#send(Object, Object) send}, the first stage will get a newly constructed empty state
     * instance, while the subsequent stages will get the state instance in the form it was left in the previous stage.
     *
     * @param endpointId
     *            the identification of this {@link MatsEndpoint}, which are the strings that should be provided to the
     *            {@link MatsInitiate#to(String)} or {@link MatsInitiate#replyTo(String, Object)} methods for this
     *            endpoint to get the message. Typical structure is <code>"OrderService.placeOrder"</code> for public
     *            endpoints, or <code>"OrderService.private.validateOrder"</code> for private (app-internal) endpoints.
     * @param replyClass
     *            the class that this endpoint shall return.
     * @param stateClass
     *            the class of the State DTO that will be sent along the stages.
     * @return the {@link MatsEndpoint} on which to add stages.
     */
    <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass);

    /**
     * Variation of {@link #staged(String, Class, Class)} that can be configured "on the fly".
     */
    <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass,
            Consumer<? super EndpointConfig<R, S>> endpointConfigLambda);

    /**
     * Sets up a {@link MatsEndpoint} that just contains one stage, useful for simple "request the full person data for
     * this/these personId(s)" scenarios. This sole stage is supplied directly, using a specialization of the processor
     * lambda which does not have state (as there is only one stage, there is no other stage to pass state to), but
     * which can return the reply by simply returning it on exit from the lambda.
     * <p/>
     * Do note that this is just a convenience for the often-used scenario where for example a request will just be
     * looked up in the backing data store, and replied directly, using only one stage, not needing any multi-stage
     * processing.
     *
     * @param endpointId
     *            the identification of this {@link MatsEndpoint}, which are the strings that should be provided to the
     *            {@link MatsInitiate#to(String)} or {@link MatsInitiate#replyTo(String, Object)} methods for this
     *            endpoint to get the message. Typical structure is <code>"OrderService.placeOrder"</code> for public
     *            endpoints, or <code>"OrderService.private.validateOrder"</code> for private (app-internal) endpoints.
     * @param replyClass
     *            the class that this endpoint shall return.
     * @param incomingClass
     *            the class of the incoming (typically request) DTO.
     * @param processor
     *            the {@link MatsStage stage} that will be invoked to process the incoming message.
     * @return the {@link MatsEndpoint}, but you should not add any stages to it, as the sole stage is already added.
     */
    <R, I> MatsEndpoint<R, Void> single(String endpointId,
            Class<R> replyClass,
            Class<I> incomingClass,
            ProcessSingleLambda<R, I> processor);

    /**
     * Variation of {@link #single(String, Class, Class, ProcessSingleLambda)} that can be configured "on the fly".
     */
    <R, I> MatsEndpoint<R, Void> single(String endpointId,
            Class<R> replyClass,
            Class<I> incomingClass,
            Consumer<? super EndpointConfig<R, Void>> endpointConfigLambda,
            Consumer<? super StageConfig<R, Void, I>> stageConfigLambda,
            ProcessSingleLambda<R, I> processor);

    /**
     * Sets up a {@link MatsEndpoint} that contains a single stage that typically will be the reply-to endpointId for a
     * {@link MatsInitiate#request(Object, Object) request initiation}, or that can be used to directly send a
     * "fire-and-forget" style {@link MatsInitiate#send(Object) invocation} to. The sole stage is supplied directly.
     * This type of endpoint cannot reply, as it has no-one to reply to (hence "terminator").
     * <p/>
     * Do note that this is just a convenience for the often-used scenario where an initiation requests out to some
     * service, and then the reply needs to be handled - and with that the process is finished. That last endpoint which
     * handles the reply is what is referred to as a terminator, in that it has nowhere to reply to. Note that there is
     * nothing hindering you in setting the replyTo endpointId in a request initiation to point to a single-stage or
     * multi-stage endpoint - however, any replies from those endpoints will just go void.
     * <p/>
     * It is possible to {@link ProcessContext#initiate(InitiateLambda) initiate} from within a terminator, and one
     * interesting scenario here is to do a {@link MatsInitiate#publish(Object) publish} to a
     * {@link #subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda) subscriptionTerminator}. The idea
     * is then that you do the actual processing via a request, and upon the reply processing in the terminator, you
     * update the app database with the updated information (e.g. "order is processed"), and then you publish an "update
     * caches" message to all the nodes of the app, so that they all have the new state of the order in their caches
     * (or, in a push-based GUI logic, you might want to update all users' view of that order). Note that you (as in the
     * processing node) will also get that published message on your instance of the SubscriptionTerminator.
     * <p/>
     * It is technically possible {@link ProcessContext#reply(Object) reply} from within a terminator - but it hard to
     * envision many wise usage scenarios for this, as the stack at a terminator would probably be empty.
     *
     * @param endpointId
     *            the identification of this {@link MatsEndpoint}, which are the strings that should be provided to the
     *            {@link MatsInitiate#to(String)} or {@link MatsInitiate#replyTo(String, Object)} methods for this
     *            endpoint to get the message. Typical structure is <code>"OrderService.placeOrder"</code> for public
     *            endpoints (which then is of a "fire-and-forget" style, since a terminator is not meant to reply), or
     *            <code>"OrderService.terminator.validateOrder"</code> for private (app-internal) terminators that is
     *            targeted by the {@link MatsInitiate#replyTo(String, Object) replyTo(endpointId,..)} invocation of an
     *            initiation.
     * @param stateClass
     *            the class of the State DTO that will may be provided by the
     *            {@link MatsInitiate#request(Object, Object) request initiation} (or that was sent along with the
     *            {@link MatsInitiate#send(Object, Object) invocation}).
     * @param incomingClass
     *            the class of the incoming (typically reply) DTO.
     * @param processor
     *            the {@link MatsStage stage} that will be invoked to process the incoming message.
     * @return the {@link MatsEndpoint}, but you should not add any stages to it, as the sole stage is already added.
     */
    <S, I> MatsEndpoint<Void, S> terminator(String endpointId,
            Class<S> stateClass,
            Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor);

    /**
     * Variation of {@link #terminator(String, Class, Class, ProcessTerminatorLambda)} that can be configured "on the
     * fly".
     */
    <S, I> MatsEndpoint<Void, S> terminator(String endpointId,
            Class<S> stateClass,
            Class<I> incomingClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor);

    /**
     * Special kind of terminator that, in JMS-style terms, subscribes to a topic instead of listening to a queue (i.e.
     * it uses "pub-sub"-style messaging, instead of queue-based). You may only communicate with this type of endpoints
     * by using the {@link MatsInitiate#publish(Object)} or {@link MatsInitiate#replyToSubscription(String, Object)}
     * methods.
     * <p/>
     * <b>Notice that the concurrency of a SubscriptionTerminator is always 1, as it makes no sense to have multiple
     * processors for a subscription - all of the processors would just get an identical copy of each message.</b> If
     * you do need to handle massive amounts of messages, or your work handling is slow, you should instead of handling
     * the work in the processor itself, rather accept the message as fast as possible and send the work out to be
     * processed by some kind of thread pool. (The tool <code>MatsFuturizer</code> does this for its Future-completion
     * handling).
     *
     * @param endpointId
     *            the identification of this {@link MatsEndpoint}, which are the strings that should be provided to the
     *            {@link MatsInitiate#to(String)} or {@link MatsInitiate#replyTo(String, Object)} methods for this
     *            endpoint to get the message.
     * @param stateClass
     *            the class of the State DTO that will may be provided by the
     *            {@link MatsInitiate#request(Object, Object) request initiation} (or that was sent along with the
     *            {@link MatsInitiate#send(Object, Object) invocation}).
     * @param incomingClass
     *            the class of the incoming (typically reply) DTO.
     * @param processor
     *            the {@link MatsStage stage} that will be invoked to process the incoming message.
     * @return the {@link MatsEndpoint}, but you should not add any stages to it, as the sole stage is already added.
     */
    <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId,
            Class<S> stateClass,
            Class<I> incomingClass,
            ProcessTerminatorLambda<S, I> processor);

    /**
     * Variation of {@link #subscriptionTerminator(String, Class, Class, ProcessTerminatorLambda)} that can be
     * configured "on the fly", <b>but notice that the concurrency of a SubscriptionTerminator is always 1</b>.
     */
    <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId,
            Class<S> stateClass,
            Class<I> incomingClass,
            Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
            Consumer<? super StageConfig<Void, S, I>> stageConfigLambda,
            ProcessTerminatorLambda<S, I> processor);

    /**
     * @return all {@link MatsEndpoint}s created on this {@link MatsFactory}.
     */
    List<MatsEndpoint<?, ?>> getEndpoints();

    /**
     * @param endpointId
     *            which {@link MatsEndpoint} to return, if present.
     * @return the requested {@link MatsEndpoint} if present, {@link Optional#empty()} if not.
     */
    Optional<MatsEndpoint<?, ?>> getEndpoint(String endpointId);

    /**
     * Gets or creates the default Initiator (whose name is 'default') from which to initiate new Mats processes, i.e.
     * send a message from "outside of Mats" to a Mats endpoint - <b>NOTICE: This is an active object that can carry
     * backend resources, and it is Thread Safe: You are not supposed to create one instance per message you send!</b>
     * <p />
     * <b>IMPORTANT NOTICE!!</b> The MatsInitiator returned from this specific method is special when used within a Mats
     * Stage's context (i.e. the Thread running a Mats Stage): Any initiations performed with it within a Mats Stage
     * will have the same transactional demarcation as an initiation performed using
     * {@link ProcessContext#initiate(InitiateLambda) ProcessContext.initiate(..)}. The idea here is that you thus can
     * create methods that both can be used from the outside of a Mats Stage (thus resulting in an ordinary initiation),
     * but if the same method is invoked within a Mats Stage, the initiation will partake in the same transactional
     * demarcation as the rest of what happens within that Mats Stage. Note that you get a bit strange semantics wrt.
     * the exceptions that {@link MatsInitiator#initiate(InitiateLambda) MatsInitiator.initiate(..)} and
     * {@link MatsInitiator#initiateUnchecked(InitiateLambda) MatsInitiator.initiateUnchecked(..)} raises: Outside of a
     * Mats Stage, they can throw in the given situations those exceptions describe. However, within a Mats Stage, they
     * will never throw those exceptions, since the actual initiation is not performed until the Mats Stage exits. But,
     * if you want to make such a dual mode method that can be employed both outside and within a Mats Stage, you should
     * thus code for the "Outside" mode, handling those Exceptions as you would in an ordinary initiation.
     * <p />
     * If you would really NOT want this - i.e. you for some reason want the initiation performed within the stage to
     * execute even though the Mats Stage fails - you may use {@link #getOrCreateInitiator(String)}, and you can even
     * request the same underlying default initiator by just supplying that method with the argument "default". Please,
     * however, make sure you understand the quite heavy consequence of this: If the Mats Stage throws, the
     * retry-mechanism will kick in, running the Mats Stage one more time, and you will thus potentially send that
     * message many times - one time per retry - since such an initiation with a NON-default MatsInitiator is
     * specifically then <i>not</i> part of the Stage's transactional demarcation.
     * <p />
     * <b>Just to ensure that this point comes across: The returned MatsInitiator is Thread Safe, and meant for reuse:
     * You are <em>not</em> supposed to create one instance of {@link MatsInitiator} per message you need to send, and
     * then close it afterwards - rather either create one for the entire application, or e.g. for each component:</b>
     * The {@code MatsInitiator} can have underlying backend resources attached to it - which also means that it needs
     * to be {@link MatsInitiator#close() closed} for a clean application shutdown (Note that all MatsInitiators are
     * closed when {@link #stop(int) MatsFactory.stop()} is invoked).
     *
     * @return the default <code>MatsInitiator</code>, whose name is 'default', on which messages can be
     *         {@link MatsInitiator#initiate(InitiateLambda) initiated}.
     * @see ContextLocal#getAttribute(Class, String...)
     */
    MatsInitiator getDefaultInitiator();

    /**
     * Gets or creates a new Initiator from which to initiate new Mats processes, i.e. send a message from "outside of
     * Mats" to a Mats endpoint - <b>NOTICE: This is an active object that can carry backend resources, and it is Thread
     * Safe: You are not supposed to create one instance per message you send!</b>
     * <p />
     * A reason for wanting to make more than one {@link MatsInitiator} could be that each initiator might have its own
     * connection to the underlying message broker. You also might want to name the initiators based on what part of the
     * application uses it; The name of the initiator shows up in monitors and tooling.
     * <p />
     * <b>IMPORTANT NOTICE!!</b> Please read the JavaDoc of {@link #getDefaultInitiator()} for important information
     * wrt. transactional demarcation when employing a NON-default MatsInitiator <i>within a Mats Stage</i>.
     * <p />
     * <b>Just to ensure that this point comes across: The returned MatsInitiator is Thread Safe, and meant for reuse:
     * You are <em>not</em> supposed to create one instance of {@link MatsInitiator} per message you need to send, and
     * then close it afterwards - rather either create one for the entire application, or e.g. for each component:</b>
     * The {@code MatsInitiator} can have underlying backend resources attached to it - which also means that it needs
     * to be {@link MatsInitiator#close() closed} for a clean application shutdown (Note that all MatsInitiators are
     * closed when {@link #stop(int) MatsFactory.stop()} is invoked).
     *
     * @return a {@link MatsInitiator}, on which messages can be {@link MatsInitiator#initiate(InitiateLambda)
     *         initiated}.
     */
    MatsInitiator getOrCreateInitiator(String name);

    /**
     * @return all {@link MatsInitiator}s created on this {@link MatsFactory}.
     */
    List<MatsInitiator> getInitiators();

    /**
     * Starts all endpoints that has been created by this factory, by invoking {@link MatsEndpoint#start()} on them.
     * <p/>
     * Subsequently clears the {@link #holdEndpointsUntilFactoryIsStarted()}-flag.
     */
    @Override
    void start();

    /**
     * <b>If this method is invoked before any endpoint is created, the endpoints will not start even though
     * {@link MatsEndpoint#finishSetup()} is invoked on them, but will wait till {@link #start()} is invoked on the
     * factory.</b> This feature should be employed in most setups where the MATS endpoints might use other services or
     * components whose order of creation and initialization are difficult to fully control, e.g. typically in an IoC
     * container like Spring. Set up the "internal machinery" of the system, including internal services, and only after
     * all this is running, <i>then</i> fire up the endpoints. If this is not done, the endpoints might start consuming
     * messages off of the MQ (there might already be messages waiting when the service boots), and thus invoke
     * services/components that are not yet fully started.
     * <p/>
     * Note: To implement delayed start for a specific endpoint, simply hold off on invoking
     * {@link MatsEndpoint#finishSetup()} until you are OK with it being started and hence starts consuming messages
     * (e.g. when the needed cache service is finished populated); This semantics works both when
     * holdEndpointsUntilFactoryIsStarted() has been invoked or not.
     *
     * @see MatsEndpoint#finishSetup()
     * @see MatsEndpoint#start()
     */
    void holdEndpointsUntilFactoryIsStarted();

    /**
     * Waits until all endpoints have fully entered the receive-loops, i.e. runs
     * {@link MatsEndpoint#waitForReceiving(int)} on all the endpoints started from this factory.
     * <p />
     * <b>Note: If there are no Endpoints registered, this will immediately return <code>true</code>!</b>
     */
    @Override
    boolean waitForReceiving(int timeoutMillis);

    /**
     * Stops all endpoints and initiators, by invoking {@link MatsEndpoint#stop(int)} on all the endpoints, and
     * {@link MatsInitiator#close()} on all initiators that has been created by this factory. They can be started again
     * individually, or all at once by invoking {@link #start()}
     * <p/>
     * Should be invoked at application shutdown.
     */
    @Override
    boolean stop(int gracefulShutdownMillis);

    /**
     * Convenience method, particularly for Spring's default destroy mechanism: Default implemented to invoke
     * {@link #stop(int) stop(30 seconds)}.
     */
    default void close() {
        stop(30_000);
    }

    /**
     * In a situation where you might be given a {@link MatsFactoryWrapper}, but need to find a wrappee that implements
     * a specific interface, this method allows you to just always call <code>matsFactory.unwrapTo(iface)</code> instead
     * of first checking whether it is a proxy and only then cast and unwrap.
     *
     * @return default <code>this</code> if <code>iface.isAssignableFrom(thisClass)</code>, otherwise throws
     *         <code>IllegalArgumentException</code> (overridden by wrappers).
     */
    @SuppressWarnings("unchecked")
    default <I> I unwrapTo(Class<I> iface) {
        if (iface == null) {
            throw new NullPointerException("iface");
        }
        if (iface.isAssignableFrom(getClass())) {
            return (I) this;
        }
        throw new IllegalArgumentException("This [" + this + "] doesn't implement [" + iface + "].");
    }

    /**
     * In a situation where you might be given a {@link MatsFactoryWrapper}, but need the actual implementation, this
     * method allows you to just always call <code>matsFactory.unwrapFully()</code> instead of first checking whether it
     * is a proxy and only then cast and unwrap.
     *
     * @return default <code>this</code> for implementations (overridden by wrappers).
     */
    default MatsFactory unwrapFully() {
        return this;
    }

    /**
     * Provides ThreadLocal access to attributes from the {@link MatsInitiate} initiate context and {@link MatsStage}
     * process context - currently {@link #getAttribute(Class, String...)}, which can provide you with the
     * transactionally demarcated SQL Connection if the Mats implementation provides such.
     */
    class ContextLocal {
        private static volatile BiFunction<Class<?>, String[], Optional<?>> callback;

        /**
         * Provides a ThreadLocal-accessible variant of the {@link ProcessContext#getAttribute(Class, String...)
         * ProcessContext.getAttribute(..)} and {@link MatsInitiate#getAttribute(Class, String...)
         * MatsInitiate.getAttribute(..)} methods: If the executing thread is within a
         * {@link MatsInitiator#initiate(InitiateLambda) Mats initiation}, or is currently processing a
         * {@link MatsStage}, the return value will be the same as if the relevant <code>getAttribute(..)</code> method
         * was invoked. Otherwise, if the current thread is not processing a stage or performing an initiation,
         * <code>Optional.empty()</code> is returned.
         * <p/>
         * If the Mats implementation has a transactional SQL Connection, it shall be available by
         * <code>ContextLocal.getAttribute(Connection.class)</code> when in the relevant contexts (init or stage).
         *
         * @param type
         *            The expected type of the attribute
         * @param name
         *            The (optional) (hierarchical) name(s) of the attribute.
         * @param <T>
         *            The type of the attribute.
         * @return Optional of the attribute in question - if not in relevant context, <code>Optional.empty()</code> is
         *         always returned.
         *
         * @see ProcessContext#getAttribute(Class, String...)
         * @see MatsInitiate#getAttribute(Class, String...)
         * @see #getDefaultInitiator()
         */
        @SuppressWarnings("unchecked")
        public static <T> Optional<T> getAttribute(Class<T> type, String... name) {
            return (Optional<T>) callback.apply(type, name);
        }
    }

    /**
     * Provides for a way to configure factory-wide elements and defaults.
     */
    interface FactoryConfig extends MatsConfig {
        /**
         * Sets the name of the MatsFactory, default is "" (empty string).
         *
         * @param name
         *            the name that the MatsFactory should have.
         */
        FactoryConfig setName(String name);

        /**
         * @return the name of the MatsFactory, as set by {@link #setName(String)}, shall return "" (empty string) if
         *         not set, never {@code null}.
         */
        String getName();

        /**
         * @return the number of CPUs that Mats shall assume there is available. Default should be
         *         {@link Runtime#availableProcessors() Runtime.getRuntime().availableProcessors()}. Implementations
         *         should honor the System Property "mats.cpus" and let that override this number, e.g. "-Dmats.cpus=8"
         *         on command line.
         */
        int getNumberOfCpus();

        /**
         * Sets a Function that may modify the TraceId of Mats flows that are initiated "from the outside", i.e. not
         * from within a Stage. The intended use is to automatically prefix the Mats flow TraceId with some sort of
         * contextual request id, typically when the Mats flow is initiated in a HTTP/REST context where the incoming
         * request carries a "X-Request-ID" or "X-Correlation-ID" header. The idea is then that this header is picked
         * out in some form of filter, placed in some ThreadLocal context, and then prepended to the TraceId that the
         * Mats flow is initiated with using the provided function. For example, if the the HTTP Request has a
         * X-Request-ID of "abc", and the Mats flow is initiated with TraceId "123", the resulting TraceId for the Mats
         * flow would be "abc+123" (the plus character being the preferred separator for composed TraceIds, i.e.
         * TraceIds that are put together by successively more specific information).
         * <p />
         * The Function gets the entire user provided TraceId as input (as set via {@link MatsInitiate#traceId(String)},
         * and should return a fully formed TraceId that should be used instead. Needless to say, it should never throw,
         * and if it doesn't have any contextual id to prefix with, it should return whatever that was passed into it.
         *
         * @param modifier
         *            the function that accepts the TraceId that the Mats flow is initiated with, and returns the
         *            TraceId that the Mats flow should actually get.
         * @return <code>this</code> for chaining.
         */
        FactoryConfig setInitiateTraceIdModifier(Function<String, String> modifier);

        /**
         * Sets the prefix that should be applied to the endpointIds to get queue or topic name in the underlying
         * messaging system - the default is <code>"mats."</code>. Needless to say, two MatsFactories which are
         * configured differently here will not be able to communicate. <b>Do not change this unless you have a
         * compelling reason to why</b>, as you then stray away from the standard, and it will be harder to later merge
         * two setups.
         *
         * @param prefix
         *            the prefix that should be applied to the endpointIds to get queue or topic name in the underlying
         *            messaging system. Default is <code>"mats."</code>.
         * @return <code>this</code> for chaining.
         */
        FactoryConfig setMatsDestinationPrefix(String prefix);

        /**
         * @return the prefix that is applied to endpointIds to get the queue and topic and topic names. Defaults to
         *         <code>"mats."</code>.
         */
        String getMatsDestinationPrefix();

        /**
         * Sets the key name on which to store the "wire representation" of the Mats message if the underlying mechanism
         * uses some kind of Map - the default is <code>"mats:trace"</code>. (The JMS Implementation uses a JMS
         * MapMessage, and this is the key). Needless to say, two MatsFactories which are configured differently here
         * will not be able to communicate. <b>Do not change this unless you have a compelling reason to why</b>, as you
         * then stray away from the standard, and it will be harder to later merge two setups.
         *
         * @param key
         *            the key name on which to store the "wire representation" of the Mats message if the underlying
         *            mechanism uses some kind of Map. Default is <code>"mats:trace"</code>.
         * @return <code>this</code> for chaining.
         */
        FactoryConfig setMatsTraceKey(String key);

        /**
         * @return the key name on which to store the "wire representation" of the Mats message if the underlying
         *         mechanism uses some kind of Map. (The JMS Implementation uses a JMS MapMessage, and this is the key).
         *         Default is <code>"mats:trace"</code>.
         */
        String getMatsTraceKey();

        /**
         * @return the name of the application/service that employs MATS, set at MatsFactory construction time.
         */
        String getAppName();

        /**
         * @return the version string of the application/service that employs MATS, set at MatsFactory construction
         *         time.
         */
        String getAppVersion();

        /**
         * Sets the nodename that {@link #getNodename()} should return. This is not necessary if the current hosts's
         * hostname is a good enough nodename (which it in a normal production system should be, both running on iron,
         * VMs and containers like Docker or Kubernetes), as that is what is returned by the getter by default. Must be
         * set before anything that depends on the return value of {@link #getNodename()} is started, i.e. right away
         * after having created the MatsFactory instance.
         *
         * @param nodename
         *            the nodename that this MatsFactory should report from {@link #getNodename()}.
         * @return <code>this</code> for chaining.
         */
        FactoryConfig setNodename(String nodename);

        /**
         * Returns a node-specific identifier, that is, a name which is different between different instances of the
         * same app running of different nodes. This can be used to make node-specific topics, which are nice when you
         * need a message to return to the node that sent it, due to some synchronous process waiting for the message
         * (which entirely defeats the Messaging Oriented Middleware Architecture, but sometimes you need a solution..).
         * This string is used for such a purpose in the MatsFuturizer tool and the "MatsSockets" WebSocket system. The
         * nodename is also used by the MatsTrace "wire protocol" as debug information. It is not used by any
         * Mats-internal parts.
         *
         * @return the nodename, which by default should be the hostname which the application is running on.
         */
        String getNodename();

        /**
         * @return the Mats Implementation name.
         */
        String getMatsImplementationName();

        /**
         * @return the Mats Implementation version.
         */
        String getMatsImplementationVersion();

        /**
         * This method is only relevant for tooling, and thus "hidden away" in this config class. It uses the
         * MatsFactory-configured deserializer mechanism to instantiate the specified class. The rationale for this is
         * to either test whether a STO (State Transfer Object) or a DTO (Data Transfer Object) actually can be
         * instantiated (if e.g. Jackson is used as serialization mechanism, which is default for the "MatsTrace"
         * implementation, it needs a no-args constructor to be able to instantiate, while if using GSON - which employs
         * "Objenesis" for instantiation - a non-args constructor is not necessary) - or to just get hold of a new
         * instance to do some kind of introspection (this is e.g. needed for Mats' SpringConfig when using
         * {@code @MatsClassMapping}).
         *
         * @param type
         *            the Class that should be instantiated.
         * @param <T>
         *            the type of the Class.
         * @return a new instance
         * @throws RuntimeException
         *             if the class could not be instantiated (e.g. lacking no-args constructor which can be an issue
         *             depending on the serialization mechanism).
         */
        <T> T instantiateNewObject(Class<T> type);

        // Overridden to return the more specific FactoryConfig instead of MatsConfig
        @Override
        FactoryConfig setConcurrency(int concurrency);
    }

    /**
     * Base Wrapper interface which Mats-specific Wrappers implements, defining four "wrappee" methods.
     *
     * @param <T>
     *            the type of the wrapped instance.
     */
    interface MatsWrapper<T> {
        void setWrappee(T target);

        T unwrap();

        @SuppressWarnings("unchecked")
        default <I> I unwrapTo(Class<I> iface) {
            if (iface == null) {
                throw new NullPointerException("iface");
            }
            if (iface.isAssignableFrom(getClass())) {
                return (I) this;
            }
            T unwrapped = unwrap();
            if (iface.isAssignableFrom(unwrapped.getClass())) {
                return (I) unwrapped;
            }
            if (unwrapped instanceof MatsWrapper) {
                return ((MatsWrapper<T>) unwrapped).unwrapTo(iface);
            }
            throw new IllegalArgumentException("This [" + this + "] doesn't implement [" + iface
                    + "], and neither do the wrappee [" + unwrapped + "].");
        }

        /**
         * @return the fully unwrapped instance: If the returned instance from {@link #unwrap()} is itself a
         *         {@link MatsWrapper MatsWrapper}, it will recurse down by invoking this method
         *         (<code>unwrapFully()</code>) again on the returned target.
         */
        default T unwrapFully() {
            T target = unwrap();
            // ?: If further wrapped, recurse down. Otherwise return.
            if (target instanceof MatsWrapper) {
                // -> Yes, further wrapped, so recurse
                @SuppressWarnings("unchecked")
                MatsWrapper<T> wrapped = (MatsWrapper<T>) target;
                return wrapped.unwrapFully();
            }
            // E-> No, not wrapped - this is the end target.
            return target;
        }
    }

    /**
     * A base Wrapper for {@link MatsFactory}, which simply implements MatsFactory, takes a MatsFactory instance and
     * forwards all calls to that. Use this if you need to wrap the MatsFactory, where most of the methods are
     * pass-through to the target, as any changes to the MatsFactory interface then won't break your wrapper.
     * <p />
     * Note: The {@link #hashCode()} and {@link #equals(Object)} are implemented to forward to the wrappee.
     */
    class MatsFactoryWrapper implements MatsWrapper<MatsFactory>, MatsFactory {
        /**
         * This field is private - all methods invoke {@link #unwrap()} to get the instance, which you should too if you
         * override any methods. If you want to take control of the wrapped MatsFactory instance, then override
         * {@link #unwrap()}.
         */
        private MatsFactory _targetMatsFactory;

        /**
         * Standard constructor, taking the wrapped {@link MatsFactory} instance.
         *
         * @param targetMatsFactory
         *            the {@link MatsFactory} instance which {@link #unwrap()} will return (and hence all forwarded
         *            methods will use).
         */
        public MatsFactoryWrapper(MatsFactory targetMatsFactory) {
            setWrappee(targetMatsFactory);
        }

        /**
         * No-args constructor, which implies that you either need to invoke {@link #setWrappee(MatsFactory)} before
         * publishing the instance (making it available for other threads), or override {@link #unwrap()} to provide the
         * desired {@link MatsFactory} instance. In these cases, make sure to honor memory visibility semantics - i.e.
         * establish a happens-before edge between the setting of the instance and any other threads getting it.
         */
        public MatsFactoryWrapper() {
            /* no-op */
        }

        /**
         * Sets the wrapped {@link MatsFactory}, e.g. in case you instantiated it with the no-args constructor. <b>Do
         * note that the field holding the wrapped instance is not volatile nor synchronized</b>. This means that if you
         * want to set it after it has been published to other threads, you will have to override both this method and
         * {@link #unwrap()} to provide for needed memory visibility semantics, i.e. establish a happens-before edge
         * between the setting of the instance and any other threads getting it. A <code>volatile</code> field would
         * work nice.
         *
         * @param targetMatsFactory
         *            the {@link MatsFactory} which is returned by {@link #unwrap()}, unless that is overridden.
         */
        public void setWrappee(MatsFactory targetMatsFactory) {
            _targetMatsFactory = targetMatsFactory;
        }

        /**
         * @return the wrapped {@link MatsFactory}. All forwarding methods invokes this method to get the wrapped
         *         {@link MatsFactory}, thus if you want to get creative wrt. how and when the MatsFactory is decided,
         *         you can override this method.
         */
        public MatsFactory unwrap() {
            if (_targetMatsFactory == null) {
                throw new IllegalStateException("MatsFactory.MatsFactoryWrapper.unwrap():"
                        + " The '_targetMatsFactory' is not set!");
            }
            return _targetMatsFactory;
        }

        /**
         * Resolve the "deadly diamond of death", by calling to <code>MatsWrapper.super.unwrapTo(iface)</code>;
         */
        @Override
        public <I> I unwrapTo(Class<I> iface) {
            return MatsWrapper.super.unwrapTo(iface);
        }

        @Override
        public MatsFactory unwrapFully() {
            return unwrap().unwrapFully();
        }

        @Override
        public FactoryConfig getFactoryConfig() {
            return unwrap().getFactoryConfig();
        }

        @Override
        public <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass) {
            return unwrap().staged(endpointId, replyClass, stateClass);
        }

        @Override
        public <R, S> MatsEndpoint<R, S> staged(String endpointId, Class<R> replyClass, Class<S> stateClass,
                Consumer<? super EndpointConfig<R, S>> endpointConfigLambda) {
            return unwrap().staged(endpointId, replyClass, stateClass, endpointConfigLambda);
        }

        @Override
        public <R, I> MatsEndpoint<R, Void> single(String endpointId, Class<R> replyClass, Class<I> incomingClass,
                ProcessSingleLambda<R, I> processor) {
            return unwrap().single(endpointId, replyClass, incomingClass, processor);
        }

        @Override
        public <R, I> MatsEndpoint<R, Void> single(String endpointId, Class<R> replyClass, Class<I> incomingClass,
                Consumer<? super EndpointConfig<R, Void>> endpointConfigLambda,
                Consumer<? super StageConfig<R, Void, I>> stageConfigLambda, ProcessSingleLambda<R, I> processor) {
            return unwrap().single(endpointId, replyClass, incomingClass, endpointConfigLambda,
                    stageConfigLambda, processor);
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> terminator(String endpointId, Class<S> stateClass, Class<I> incomingClass,
                ProcessTerminatorLambda<S, I> processor) {
            return unwrap().terminator(endpointId, stateClass, incomingClass, processor);
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> terminator(String endpointId, Class<S> stateClass, Class<I> incomingClass,
                Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
                Consumer<? super StageConfig<Void, S, I>> stageConfigLambda, ProcessTerminatorLambda<S, I> processor) {
            return unwrap().terminator(endpointId, stateClass, incomingClass, endpointConfigLambda,
                    stageConfigLambda, processor);
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
                Class<I> incomingClass, ProcessTerminatorLambda<S, I> processor) {
            return unwrap().subscriptionTerminator(endpointId, stateClass, incomingClass, processor);
        }

        @Override
        public <S, I> MatsEndpoint<Void, S> subscriptionTerminator(String endpointId, Class<S> stateClass,
                Class<I> incomingClass, Consumer<? super EndpointConfig<Void, S>> endpointConfigLambda,
                Consumer<? super StageConfig<Void, S, I>> stageConfigLambda, ProcessTerminatorLambda<S, I> processor) {
            return unwrap().subscriptionTerminator(endpointId, stateClass, incomingClass,
                    endpointConfigLambda, stageConfigLambda, processor);
        }

        @Override
        public List<MatsEndpoint<?, ?>> getEndpoints() {
            return unwrap().getEndpoints();
        }

        @Override
        public Optional<MatsEndpoint<?, ?>> getEndpoint(String endpointId) {
            return unwrap().getEndpoint(endpointId);
        }

        @Override
        public MatsInitiator getDefaultInitiator() {
            return unwrap().getDefaultInitiator();
        }

        @Override
        public MatsInitiator getOrCreateInitiator(String name) {
            return unwrap().getOrCreateInitiator(name);
        }

        @Override
        public List<MatsInitiator> getInitiators() {
            return unwrap().getInitiators();
        }

        @Override
        public void holdEndpointsUntilFactoryIsStarted() {
            unwrap().holdEndpointsUntilFactoryIsStarted();
        }

        @Override
        public void start() {
            unwrap().start();
        }

        @Override
        public boolean waitForReceiving(int timeoutMillis) {
            return unwrap().waitForReceiving(timeoutMillis);
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            return unwrap().stop(gracefulShutdownMillis);
        }

        @Override
        public int hashCode() {
            return unwrap().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return unwrap().equals(obj);
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "[" + unwrap().toString() + "]";
        }
    }
}
