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

package io.mats3.test.jupiter;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint.TestEndpoint;

/**
 * Extension which offers multiple variants of endpoints for testing purposes.
 * <ul>
 *     <li>{@link Endpoint Endpoint}</li>
 *     Functions identically to {@link Extension_MatsEndpoint}, with the exception that this implementation offers the
 *     <code>waitForRequest(s)</code> methods enabling verification of side loaded data through the returned context.
 *     <li>{@link EndpointWithState EndpointWithState}</li>
 *     Identical to {@link Endpoint Endpoint}, however, this implementation allows for the specification of a state
 *     class as well enabling one to test scenarios where one has an endpoint which spins off another MATS^3 flow and
 *     seeds the state of the target endpoint.
 *     <li>{@link Terminator Terminator}</li>
 *     Convenience implementation of {@link Endpoint Endpoint} allowing one to drop the specification of reply class,
 *     when one is only interested in catching the incoming message.
 *     <li>{@link TerminatorWithState TerminatorWithState}</li>
 *     Identical to {@link EndpointWithState EndpointWithState}, with the same convenience as
 *     {@link Terminator Terminator} in that there is no need to specify a reply class.
 * </ul>
 * The endpoint's processor can be changed on demand using <code>setProcessLambda</code> method.
 * <p>
 * Must be annotated with {@link RegisterExtension @RegisterExtension}.
 * <p>
 * Retrieve the endpoint's incoming message/messages by calling on of the following methods:
 * <ul>
 * <li>{@link Endpoint#waitForResult() waitForResult()} - Wait for a result(singular) using the default timeout</li>
 * <li>{@link Endpoint#waitForResult(long) waitForResult(long)} - Wait for a result(singular) with user specified
 * timeout</li>
 * <li>{@link Endpoint#waitForResults(int) waitForResults(int)} - Wait for X results using the default timeout</li>
 * <li>{@link Endpoint#waitForResults(int, long) waitForResults(int, long)} - Wait for X results with user specified
 * timeout</li>
 * </ul>
 * Given a case where one does not expect the endpoint to be invoked (no messages received) one can utilize
 * {@link Endpoint#verifyNotInvoked()} to ensure that the endpoint was not in fact invoked during the
 * test.
 * <p>
 * Should one want to utilize these test endpoints in a test which brings up a Spring context containing a
 * {@link MatsFactory} one can utilize the <code>@SpringInjectRulesAndExtensions</code> (in 'mats-spring-test') which
 * will inject/autowire this class automatically by providing the {@link MatsFactory} located in said Spring context.
 *
 * @author Endre Stølsvik, 2025-04-23 - http://stolsvik.com/, endre@stolsvik.com
 * @author Kevin Mc Tiernan, 2025-04-23, kevin.mc.tiernan@storebrand.no
 */
public class Extension_MatsEndpoints {

    /**
     * Creates a Jupiter Extension for a single-staged endpoint whose processor is <i>not</i> defined at start. Sets it up on
     * Jupiter lifecycle 'beforeEach' and tears it down on 'afterEach'.  <b>Notice that a {@link MatsFactory} must
     * be set before it is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload {@link #createEndpoint(Extension_Mats, String, Class, Class)
     * create(extensionMats, endpointId, replyClass, incomingClass)} to easily supply the corresponding
     * <code>{@literal @RegisterExtension}</code> {@link Extension_Mats} for fetching the <code>MatsFactory</code>.
     * <p>
     * <b>Do notice that you need to invoke {@link Endpoint#setProcessLambda(ProcessSingleLambda)
     * setProcessLambda(lambda)} - typically inside the
     * &#64;Test method - before sending messages to it, as there is no default.</b>
     * <p>
     * Should there be no need for a processor, consider using {@link #createTerminator(String, Class, Class)} instead.
     *
     * @param endpointId
     *         of the endpoint.
     * @param replyMsgClass
     *         the class of the reply message generated by this endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Endpoint}
     * @see #createTerminator(String, Class, Class)
     */
    public static <R, I> Endpoint<R, I> createEndpoint(String endpointId, Class<R> replyMsgClass,
            Class<I> incomingMsgClass) {
        return new EndpointImpl<>(endpointId, replyMsgClass, incomingMsgClass);
    }

    /**
     * Expanded version of {@link #createEndpoint(String, Class, Class)} which also allows for the specification of a
     * state class.
     *
     * @param endpointId
     *         of the endpoint.
     * @param replyMsgClass
     *         the class of the reply message generated by this endpoint.
     * @param stateClass
     *         the class of the state object for this endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link EndpointWithState}
     */
    public static <R, S, I> EndpointWithState<R, S, I> createEndpoint(String endpointId, Class<R> replyMsgClass,
            Class<S> stateClass, Class<I> incomingMsgClass) {
        return new EndpointWithStateImpl<>(endpointId, replyMsgClass, stateClass, incomingMsgClass);
    }

    /**
     * Simplified version of {@link #createEndpoint(String, Class, Class)}, which does not require a reply class. Useful
     * for mock endpoints where one is simply interested in the incoming message.
     *
     * @param endpointId
     *         of the endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Terminator}
     */
    public static <I> Terminator<I> createTerminator(String endpointId, Class<I> incomingMsgClass) {
        return new TerminatorImpl<>(endpointId, incomingMsgClass);
    }

    /**
     * Expanded version of {@link #createTerminator(String, Class)}, which also allows for the specification of a state
     * class.
     *
     * @param endpointId
     *         of the endpoint.
     * @param stateClass
     *         the class of the state object for this endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Terminator}
     */
    public static <S, I> TerminatorWithState<S, I> createTerminator(String endpointId, Class<S> stateClass,
            Class<I> incomingMsgClass) {
        return new TerminatorWithStateImpl<>(endpointId, stateClass, incomingMsgClass) { };
    }

    /**
     * Convenience variant of
     * {@link #createEndpoint(String, Class, Class) create(endpointId, replyClass, incomingClass)} taking a
     * {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <R, I> Endpoint<R, I> createEndpoint(Extension_Mats matsRule, String endpointId,
            Class<R> replyMsgClass,
            Class<I> incomingMsgClass) {
        EndpointImpl<R, I> rule_matsEndpoint = new EndpointImpl<>(endpointId, replyMsgClass, incomingMsgClass);
        // Set MatsFactory from the supplied Rule_Mats
        rule_matsEndpoint.setMatsFactory(matsRule.getMatsFactory());
        return rule_matsEndpoint;
    }

    /**
     * Convenience variant of
     * {@link #createEndpoint(String, Class, Class, Class) create(endpointId, replyClass, stateClass, incomingClass)}
     * taking a {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <R, S, I> EndpointWithState<R, S, I> createEndpoint(Extension_Mats matsRule, String endpointId,
            Class<R> replyMsgClass, Class<S> stateClass, Class<I> incomingMsgClass) {
        EndpointWithStateImpl<R, S, I> rule_matsEndpoint = new EndpointWithStateImpl<>(endpointId, replyMsgClass,
                stateClass, incomingMsgClass);
        // Set MatsFactory from the supplied Rule_Mats
        rule_matsEndpoint.setMatsFactory(matsRule.getMatsFactory());
        return rule_matsEndpoint;
    }

    /**
     * Convenience variant of
     * {@link #createTerminator(String, Class) createTerminator(endpointId, incomingClass)} taking a
     * {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <I> Terminator<I> createTerminator(Extension_Mats matsRule,
            String endpointId, Class<I> incomingMsgClass) {
        TerminatorImpl<I> iTerminatorNoState = new TerminatorImpl<>(endpointId, incomingMsgClass);
        iTerminatorNoState.setMatsFactory(matsRule.getMatsFactory());
        return iTerminatorNoState;
    }

    /**
     * Convenience variant of
     * {@link #createTerminator(String, Class, Class) create(endpointId, stateClass, incomingClass)} taking a
     * {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <S, I> TerminatorWithState<S, I> createTerminator(Extension_Mats matsRule,
            String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        TerminatorWithStateImpl<S, I> siTerminator =
                new TerminatorWithStateImpl<>(endpointId, stateClass, incomingMsgClass);
        siTerminator.setMatsFactory(matsRule.getMatsFactory());
        return siTerminator;
    }

    // ================================================================================================================
    // Interfaces
    // ================================================================================================================

    /**
     * Mock endpoint which processes an incoming message and returns a reply. Ideal for mocking intermediate endpoints
     * in a multi-stage flow whether they are internal to the application or external.
     * <p>
     * If no processor is defined this will function as a {@link Terminator}, thus one should consider using that
     * instead if one ends up in a test situation where the processor is never defined.
     *
     * @param <R>
     *         The reply class of the message generated by this endpoint.
     * @param <I>
     *         The incoming message class for this endpoint.
     */
    public interface Endpoint<R, I> extends EndpointWithState<R, Void, I>, BeforeEachCallback, AfterEachCallback {
        @Override
        Endpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> lambda);

        @Override
        Endpoint<R, I> setMatsFactory(MatsFactory matsFactory);
    }

    /**
     * Equivalent to {@link Endpoint}, but allows for the definition of a state class. Thus, can be utilized to validate
     * the passed state for flows which spin off new MATS^3 flows and seeds the state of the target endpoint through
     * the use of {@link MatsInitiate#send(Object, Object)}.
     *
     * @param <R>
     *         The reply class of the message generated by this endpoint.
     * @param <S>
     *         The state class of the endpoint.
     * @param <I>
     *         The incoming message class for this endpoint.
     */
    public interface EndpointWithState<R, S, I> extends TestEndpoint<R, S, I>, BeforeEachCallback, AfterEachCallback {
        EndpointWithState<R, S, I> setProcessLambda(ProcessSingleLambda<R, I> lambda);

        @Override
        EndpointWithState<R, S, I> setMatsFactory(MatsFactory matsFactory);
    }

    /**
     * Simplified version of {@link Endpoint}, which doesn't require the specification of a return class. Useful for
     * scenarios where one is only interested in the incoming message and has no need for processor.
     *
     * @param <I>
     *         The incoming message class for this endpoint.
     */
    public interface Terminator<I> extends TerminatorWithState<Void, I>, BeforeEachCallback, AfterEachCallback {
        @Override
        Terminator<I> setProcessLambda(ProcessTerminatorLambda<Void, I> lambda);

        @Override
        Terminator<I> setMatsFactory(MatsFactory matsFactory);
    }

    /**
     * Expanded version of {@link Terminator}, which also allows for the specification of a state class.
     *
     * @param <S>
     *         The state class of the endpoint.
     * @param <I>
     *         The incoming message class for this endpoint.
     */
    public interface TerminatorWithState<S, I> extends TestEndpoint<Void, S, I>, BeforeEachCallback, AfterEachCallback {
        TerminatorWithState<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> lambda);

        @Override
        TerminatorWithState<S, I> setMatsFactory(MatsFactory matsFactory);
    }

    // ================================================================================================================
    // Impls
    // ================================================================================================================

    private static class EndpointImpl<R, I> extends EndpointWithStateImpl<R, Void, I>
            implements Endpoint<R, I> {

        EndpointImpl(String endpointId, Class<R> replyMsgClass, Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, Void.class, incomingMsgClass);
        }

        @Override
        public Endpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Endpoint<R, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class EndpointWithStateImpl<R, S, I> extends JupiterCommonsImpl<R, S, I>
            implements EndpointWithState<R, S, I> {

        EndpointWithStateImpl(String endpointId, Class<R> replyMsgClass, Class<S> stateClass,
                Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, stateClass, incomingMsgClass);
        }

        @Override
        public EndpointWithState<R, S, I> setProcessLambda(ProcessSingleLambda<R, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public EndpointWithState<R, S, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class TerminatorImpl<I> extends TerminatorWithStateImpl<Void, I>
            implements Terminator<I> {

        TerminatorImpl(String endpointId, Class<I> incomingMsgClass) {
            super(endpointId, Void.class, incomingMsgClass);
        }

        @Override
        public Terminator<I> setProcessLambda(ProcessTerminatorLambda<Void, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Terminator<I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class TerminatorWithStateImpl<S, I> extends JupiterCommonsImpl<Void, S, I>
            implements TerminatorWithState<S, I> {

        TerminatorWithStateImpl(String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
            super(endpointId, Void.class, stateClass, incomingMsgClass);
        }

        @Override
        public TerminatorWithState<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public TerminatorWithState<S, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    /**
     * Common base class for the all the impls.
     *
     * @see EndpointImpl
     * @see EndpointWithStateImpl
     * @see TerminatorImpl
     * @see TerminatorWithStateImpl
     */
    private static abstract class JupiterCommonsImpl<R, S, I> extends AbstractMatsTestEndpoint<R, S, I> {

        protected JupiterCommonsImpl(String endpointId, Class<R> replyMsgClass, Class<S> stateClass,
                Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, stateClass, incomingMsgClass);
        }

        @Override
        public Result<S, I> waitForResult() {
            return super.waitForResult();
        }

        @Override
        public Result<S, I> waitForResult(long millisToWait) {
            return super.waitForResult(millisToWait);
        }

        @Override
        public List<Result<S, I>> waitForResults(int expectedNumberOfIncomingMsgs) {
            return super.waitForResults(expectedNumberOfIncomingMsgs);
        }

        @Override
        public List<Result<S, I>> waitForResults(int expectedNumberOfIncomingMsgs, long millisToWait) {
            return super.waitForResults(expectedNumberOfIncomingMsgs, millisToWait);
        }

        // ================== Jupiter LifeCycle =======================================================================

        public void beforeEach(ExtensionContext context) {
            // ?: Do we have the MatsFactory set yet?
            if (_matsFactory == null) {
                // -> No, so let's see if we can find it in the ExtensionContext (throws if not).
                Optional<Extension_Mats> matsFromContext = Extension_Mats.findFromContext(context);
                if (matsFromContext.isPresent()) {
                    _matsFactory = matsFromContext.get().getMatsFactory();
                }
                else {
                    throw new IllegalStateException("MatsFactory is not set. Didn't find Extension_Mats in"
                            + " ExtensionContext, so couldn't get it from there either. Either set it explicitly"
                            + " using setMatsFactory(matsFactory), or use Extension_Mats (which adds itself to the"
                            + " ExtensionContext), and ensure that it is initialized before this"
                            + " Extension_MatsEndpoints field.");
                }
            }
            super.before();
        }

        public void afterEach(ExtensionContext context) {
            super.after();
        }
    }
}
