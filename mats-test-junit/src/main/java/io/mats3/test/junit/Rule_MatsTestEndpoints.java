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

package io.mats3.test.junit;

import java.util.List;

import javax.inject.Inject;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.test.MatsTestEndpoint.IEndpoint;
import io.mats3.test.MatsTestEndpoint.IEndpointWithState;
import io.mats3.test.MatsTestEndpoint.ITerminator;
import io.mats3.test.MatsTestEndpoint.ITerminatorWithState;
import io.mats3.test.MatsTestEndpoint.Message;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint;

/**
 * Rule which offers multiple variants of endpoints for testing purposes.
 * <ul>
 * <li>{@link Endpoint Endpoint}</li> Functions identically to {@link Rule_MatsEndpoint}, with the exception that this
 * implementation offers the <code>waitForRequest(s)</code> methods enabling verification of side loaded data through
 * the returned context.
 * <li>{@link EndpointWithState EndpointWithState}</li> Identical to {@link Endpoint Endpoint}, however, this
 * implementation allows for the specification of a state class as well enabling one to test scenarios where one has an
 * endpoint which spins off another MATS^3 flow and seeds the state of the target endpoint.
 * <li>{@link Terminator Terminator}</li> Convenience implementation of {@link Endpoint Endpoint} allowing one to drop
 * the specification of reply class, when one is only interested in catching the incoming message.
 * <li>{@link ITerminatorWithState TerminatorWithState}</li> Identical to {@link EndpointWithState EndpointWithState},
 * with the same convenience as {@link Terminator Terminator} in that there is no need to specify a reply class.
 * </ul>
 * The endpoint's processor can be changed on demand using <code>setProcessLambda</code> method.
 * <p>
 * Must be annotated with {@link Rule @Rule}. Do not use with {@link ClassRule @ClassRule}
 * <p>
 * Retrieve the endpoint's incoming message/messages by calling on of the following methods:
 * <ul>
 * <li>{@link Endpoint#awaitInvocation() waitForResult()} - Wait for a result(singular) using the default timeout</li>
 * <li>{@link Endpoint#awaitInvocation(long) waitForResult(long)} - Wait for a result(singular) with user specified
 * timeout</li>
 * <li>{@link Endpoint#awaitInvocations(int) waitForResults(int)} - Wait for X results using the default timeout</li>
 * <li>{@link Endpoint#awaitInvocations(int, long) waitForResults(int, long)} - Wait for X results with user specified
 * timeout</li>
 * </ul>
 * Given a case where one does not expect the endpoint to be invoked (no messages received) one can utilize
 * {@link Endpoint#verifyNotInvoked()} to ensure that the endpoint was not in fact invoked during the test.
 * <p>
 * Should one want to utilize these test endpoints in a test which brings up a Spring context containing a
 * {@link MatsFactory} one can utilize the <code>@SpringInjectRulesAndExtensions</code> (in 'mats-spring-test') which
 * will inject/autowire this class automatically by providing the {@link MatsFactory} located in said Spring context.
 *
 * @author Endre Stølsvik, 2025-04-23 - http://stolsvik.com/, endre@stolsvik.com
 * @author Kevin Mc Tiernan, 2025-04-23, kevin.mc.tiernan@storebrand.no
 */
public class Rule_MatsTestEndpoints {

    /**
     * Creates a JUnit Rule for a single-staged endpoint whose processor is <i>not</i> defined at start. Sets it up on
     * JUnit lifecycle 'before' and tears it down on 'after'. <b>Notice that a {@link MatsFactory} must be set before it
     * is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload {@link #createEndpoint(Rule_Mats, String, Class, Class) createEndpoint(MATS,
     * endpointId, replyClass, stateClass, incomingClass)} to easily supply the corresponding
     * <code>{@literal @ClassRule}</code> {@link Rule_Mats} for fetching the <code>MatsFactory</code>.
     * <p/>
     * <b>Do notice that you need to invoke {@link Endpoint#setProcessLambda(ProcessSingleLambda)
     * setProcessLambda(lambda)} - typically inside the <code>{@literal @Test}</code> method - before sending messages
     * to it, as there is no default.</b>
     * <p>
     * Should there be no need for a processor, consider using {@link #createTerminator(String, Class, Class)} instead.
     *
     * @param endpointId
     *            of the endpoint.
     * @param replyMsgClass
     *            the class of the reply message generated by this endpoint.
     * @param incomingMsgClass
     *            the incoming message class for this endpoint.
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
     *            of the endpoint.
     * @param replyMsgClass
     *            the class of the reply message generated by this endpoint.
     * @param stateClass
     *            the class of the state object for this endpoint.
     * @param incomingMsgClass
     *            the incoming message class for this endpoint.
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
     *            of the endpoint.
     * @param incomingMsgClass
     *            the incoming message class for this endpoint.
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
     *            of the endpoint.
     * @param stateClass
     *            the class of the state object for this endpoint.
     * @param incomingMsgClass
     *            the incoming message class for this endpoint.
     * @return {@link TerminatorWithState}
     */
    public static <S, I> TerminatorWithState<S, I> createTerminator(String endpointId, Class<S> stateClass,
            Class<I> incomingMsgClass) {
        return new TerminatorWithStateImpl<>(endpointId, stateClass, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #createEndpoint(String, Class, Class) create(endpointId, replyClass,
     * incomingClass)} taking a {@link Rule_Mats} as first argument for fetching the {@link MatsFactory}, for use in
     * "pure Java" environments (read as: non-Spring).
     */
    public static <R, I> Endpoint<R, I> createEndpoint(Rule_Mats matsRule, String endpointId, Class<R> replyMsgClass,
            Class<I> incomingMsgClass) {
        return new EndpointImpl<>(endpointId, replyMsgClass, incomingMsgClass)
                .setMatsFactory(matsRule.getMatsFactory());
    }

    /**
     * Convenience variant of {@link #createEndpoint(String, Class, Class, Class) create(endpointId, replyClass,
     * stateClass, incomingClass)} taking a {@link Rule_Mats} as first argument for fetching the {@link MatsFactory},
     * for use in "pure Java" environments (read as: non-Spring).
     */
    public static <R, S, I> EndpointWithState<R, S, I> createEndpoint(Rule_Mats matsRule, String endpointId,
            Class<R> replyMsgClass, Class<S> stateClass, Class<I> incomingMsgClass) {
        return new EndpointWithStateImpl<>(endpointId, replyMsgClass, stateClass, incomingMsgClass)
                .setMatsFactory(matsRule.getMatsFactory());
    }

    /**
     * Convenience variant of {@link #createTerminator(String, Class) createTerminator(endpointId, incomingClass)}
     * taking a {@link Rule_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <I> Terminator<I> createTerminator(Rule_Mats matsRule,
            String endpointId, Class<I> incomingMsgClass) {
        return new TerminatorImpl<>(endpointId, incomingMsgClass)
                .setMatsFactory(matsRule.getMatsFactory());
    }

    /**
     * Convenience variant of {@link #createTerminator(String, Class, Class) create(endpointId, stateClass,
     * incomingClass)} taking a {@link Rule_Mats} as first argument for fetching the {@link MatsFactory}, for use in
     * "pure Java" environments (read as: non-Spring).
     */
    public static <S, I> TerminatorWithState<S, I> createTerminator(Rule_Mats matsRule,
            String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        return new TerminatorWithStateImpl<>(endpointId, stateClass, incomingMsgClass)
                .setMatsFactory(matsRule.getMatsFactory());
    }

    // ================================================================================================================
    // Interface overrides
    // ================================================================================================================

    public interface Endpoint<R, I> extends IEndpoint<R, I>, TestRule {
        Rule_MatsTestEndpoints.Endpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> processLambda);

        @Override
        Rule_MatsTestEndpoints.Endpoint<R, I> setMatsFactory(MatsFactory matsFactory);
    }

    public interface EndpointWithState<R, S, I> extends IEndpointWithState<R, S, I>,
            TestRule {
        Rule_MatsTestEndpoints.EndpointWithState<R, S, I> setProcessLambda(
                ProcessSingleStateLambda<R, S, I> processLambda);

        @Override
        IEndpointWithState<R, S, I> setMatsFactory(MatsFactory matsFactory);
    }

    public interface Terminator<I> extends ITerminator<I>, TestRule {
        Rule_MatsTestEndpoints.Terminator<I> setProcessLambda(ProcessTerminatorNoStateLambda<I> processLambda);

        @Override
        Rule_MatsTestEndpoints.Terminator<I> setMatsFactory(MatsFactory matsFactory);
    }

    public interface TerminatorWithState<S, I> extends ITerminatorWithState<S, I>,
            TestRule {
        Rule_MatsTestEndpoints.TerminatorWithState<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> processLambda);

        @Override
        Rule_MatsTestEndpoints.TerminatorWithState<S, I> setMatsFactory(MatsFactory matsFactory);
    }

    // ================================================================================================================
    // Impls
    // ================================================================================================================

    private static class EndpointImpl<R, I> extends JUnitCommonsImpl<R, Void, I>
            implements Endpoint<R, I> {

        EndpointImpl(String endpointId, Class<R> replyMsgClass, Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, void.class, incomingMsgClass);
        }

        @Override
        public Rule_MatsTestEndpoints.Endpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Rule_MatsTestEndpoints.Endpoint<R, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class EndpointWithStateImpl<R, S, I> extends JUnitCommonsImpl<R, S, I>
            implements EndpointWithState<R, S, I> {

        EndpointWithStateImpl(String endpointId, Class<R> replyMsgClass, Class<S> stateClass,
                Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, stateClass, incomingMsgClass);
        }

        @Override
        public Rule_MatsTestEndpoints.EndpointWithState<R, S, I> setProcessLambda(
                ProcessSingleStateLambda<R, S, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Rule_MatsTestEndpoints.EndpointWithState<R, S, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class TerminatorImpl<I> extends JUnitCommonsImpl<Void, Void, I>
            implements Terminator<I> {

        TerminatorImpl(String endpointId, Class<I> incomingMsgClass) {
            super(endpointId, void.class, void.class, incomingMsgClass);
        }

        @Override
        public Rule_MatsTestEndpoints.Terminator<I> setProcessLambda(ProcessTerminatorNoStateLambda<I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Rule_MatsTestEndpoints.Terminator<I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }
    }

    private static class TerminatorWithStateImpl<S, I> extends JUnitCommonsImpl<Void, S, I>
            implements TerminatorWithState<S, I> {

        TerminatorWithStateImpl(String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
            super(endpointId, void.class, stateClass, incomingMsgClass);
        }

        @Override
        public Rule_MatsTestEndpoints.TerminatorWithState<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> lambda) {
            _processLambda = lambda;
            return this;
        }

        @Inject
        @Override
        public Rule_MatsTestEndpoints.TerminatorWithState<S, I> setMatsFactory(MatsFactory matsFactory) {
            _matsFactory = matsFactory;
            return this;
        }

        // ================== Junit LifeCycle =========================================================================
    }

    /**
     * This is a common impl for all of the implementations within this class, it provides the default overrides for the
     * {@link #awaitInvocation() waitForResult}(s) methods as well as the JUnit lifecycle methods.
     */
    private static abstract class JUnitCommonsImpl<R, S, I> extends AbstractMatsTestEndpoint<R, S, I>
            implements TestRule {

        protected JUnitCommonsImpl(String endpointId, Class<R> replyMsgClass, Class<S> stateClass,
                Class<I> incomingMsgClass) {
            super(endpointId, replyMsgClass, stateClass, incomingMsgClass);
        }

        @Override
        public Message<S, I> awaitInvocation() {
            assertProcessLambdaSetIfRelevant();
            return super.awaitInvocation();
        }

        @Override
        public Message<S, I> awaitInvocation(long millisToWait) {
            assertProcessLambdaSetIfRelevant();
            return super.awaitInvocation(millisToWait);
        }

        @Override
        public List<Message<S, I>> awaitInvocations(int expectedNumberOfIncomingMsgs) {
            assertProcessLambdaSetIfRelevant();
            return super.awaitInvocations(expectedNumberOfIncomingMsgs);
        }

        @Override
        public List<Message<S, I>> awaitInvocations(int expectedNumberOfIncomingMsgs, long millisToWait) {
            assertProcessLambdaSetIfRelevant();
            return super.awaitInvocations(expectedNumberOfIncomingMsgs, millisToWait);
        }

        private void assertProcessLambdaSetIfRelevant() {
            // ?: Is it a Terminator?
            if ((this instanceof ITerminator) || (this instanceof ITerminatorWithState)) {
                // -> Yes, and Terminators do not need process lambda set.
                return;
            }
            if (_processLambda == null) {
                throw new IllegalStateException("The process lambda has not been set for test endpoint '"
                        + _endpoint.getEndpointConfig().getEndpointId() + "', and it is not a Terminator-type."
                        + " Please set it using setProcessLambda() before awaitInvocation, or use a Terminator-type.");
            }
        }

        // ================== Junit LifeCycle =========================================================================

        /**
         * Note: Shamelessly inspired from: <a href="https://stackoverflow.com/a/48759584">How to combine &commat;Rule
         * and &commat;ClassRule in JUnit 4.12</a>
         */
        public Statement apply(Statement base, Description description) {
            if (description.isSuite()) {
                throw new IllegalStateException("The Rule_MatsEndpoints should be applied as a @Rule, NOT as a"
                        + " @ClassRule");
            }

            return new Statement() {
                public void evaluate() throws Throwable {
                    before();
                    try {
                        base.evaluate();
                    }
                    finally {
                        after();
                    }
                }
            };
        }
    }
}
