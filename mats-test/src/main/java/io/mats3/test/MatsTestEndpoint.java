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

package io.mats3.test;

import java.util.List;

import org.apache.activemq.command.Endpoint;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * Test utility that provides for a quick way to create assertable endpoints and terminators for testing purposes,
 * integrated with JUnit 4 as a {@code Rule}, and Junit 5 (Jupiter) as an {@code Extension}, suitable for making
 * collaborators in a test scenario, i.e. emulating external services or internal endpoints. The tooling's main purpose
 * is to be able to wait for and assert incoming messages (along with its state and {@link ProcessContext
 * ProcessContext} to get e.g. sideloads), and to change the stage processor on the fly so that it can be used to test
 * different scenarios in each test method in a class.
 * <p>
 * To get instances of this interface, use the JUnit 4 Rule {@code Rule_MatsTestEndpoints} or the JUnit 5 (Jupiter)
 * Extension {@code Extension_MatsTestEndpoints}.
 * <p>
 * The endpoint's processor can be changed for each test method by using the <code>setProcessLambda(..lambda..)</code>
 * method.
 * <p>
 * There are 4 different endpoint types to choose from, depending on your needs - they each have their separate
 * sub-interfaces:
 * <ul>
 * <li>{@link IEndpoint Endpoint}</li> A single-stage Endpoint which is expected to reply when invoked. If the Process
 * Lambda is not set when the await-methods are invoked, you will get an {@link IllegalStateException}.
 * <li>{@link IEndpointWithState EndpointWithState}</li> Identical to {@link Endpoint Endpoint}, however, this
 * implementation allows for the specification of a state class, i.e.g the "initial state" concept that can be used when
 * sending messages (typically to "private" endpoints within the same service).
 * <li>{@link ITerminator Terminator}</li> A Terminator which is not expected to reply when invoked. It is not required
 * to set the Process Lambda for Terminators, since you might just want to assert that the endpoint was invoked and get
 * the incoming message.
 * <li>{@link ITerminatorWithState TerminatorWithState}</li> A Terminator that also takes state.
 * </ul>
 * Retrieve the endpoint's incoming message/messages by calling on of the following methods (that is, on the JUnit 4 or
 * JUnit 5 interface variants, lacking the "I" prefix):
 * <ul>
 * <li>{@link MatsTestEndpoint#awaitInvocation() awaitInvocation()} - Wait for an incoming message (singular) using the
 * default timeout (30 sec).</li>
 * <li>{@link MatsTestEndpoint#awaitInvocation(long) awaitInvocation(long)} - Wait for an incoming message (singular)
 * with the specified timeout. Setting a lower timeout than the default makes little sense.</li>
 * <li>{@link MatsTestEndpoint#awaitInvocations(int) awaitInvocations(int)} - Wait for the specified number of incoming
 * messages using the default timeout (30 sec)</li>
 * <li>{@link MatsTestEndpoint#awaitInvocations(int, long) awaitInvocations(int, long)} - Wait for the specified number
 * of incoming messages using the specified timeout. Setting a lower timeout than the default makes little sense.</li>
 * </ul>
 * Given a case where one does not expect the endpoint to be invoked (no messages received) one can utilize
 * {@link MatsTestEndpoint#verifyNotInvoked()} to ensure that the endpoint was not in fact invoked during the test.
 * <p>
 * Should one want to utilize these test endpoints in a test which brings up a Spring context containing a
 * {@link MatsFactory} one can utilize the <code>@SpringInjectRulesAndExtensions</code> (in 'mats-spring-test') which
 * will inject/autowire this class automatically by providing the {@link MatsFactory} located in said Spring context.
 * <p>
 * Small tip wrt. typing out the left-hand-side interface type and generics, which is a bit tedious: These instances
 * will be on fields of the test class. Since Java doesn't allow <code>var</code> for fields, you can use the following
 * jig: Write it out as such "<code>@Rule String _endpoint = Rule_MatsTestEndpoints.createEndpoint("epId",
 * ReplyDTO.class, StateSTO.class, requestDTO.class)</code>", that is, just use a dummy type for the left-hand-side
 * while writing out what you actually want on the right-hand-side. This will obviously be red-penned by your IDE. Then,
 * use your IDE's "auto-fix" functionality to change the type of the field to the correct type with generics (which here
 * would be "<code>@Rule EndpointWithState&lt;ReplyDTO, StateSTO, RequestDTO&gt; _endpoint = ...</code>")
 * <p>
 * <i>History: This solution is based on the older {@code Rule|Extension_MatsEndpoint}, with the following changes: The
 * multiple 4 endpoint types, and the {@link Message Message} interface to represent the incoming message. The author
 * list reflects this history.</i>
 *
 * @param <R>
 *            The reply class of the message generated by this endpoint.
 * @param <S>
 *            The state class of the endpoint.
 * @param <I>
 *            The incoming message class for this endpoint.
 *
 * @author Geir Gullestad Pettersen, 2017 - geirgp@gmail.com
 * @author Johan Herman Hausberg, 2017 - jhausber@gmail.com
 * @author Asbjørn Aarrestad, 2017 - asbjorn@aarrestad.com
 * @author Endre Stølsvik, 2017 - http://stolsvik.com/, endre@stolsvik.com
 * @author Kevin Mc Tiernan, 2020-10-22, kmctiernan@gmail.com
 * @author Kevin Mc Tiernan, 2025-04-23, kevin.mc.tiernan@storebrand.no
 * @author Endre Stølsvik, 2025-04-23 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsTestEndpoint<R, S, I> {
    /**
     * Must be called before the endpoint is used, to set the MatsFactory on which this endpoint should be created. You
     * can call it directly, or use the convenience-variants on the factories that take the corresponding
     * <code>Rule_Mats</code> or <code>Extension_Mats</code> as first argument, or for Spring-scenarios use the
     * <code>@SpringInjectRulesAndExtensions</code> which will inject the MatsFactory for you.
     *
     * @param matsFactory
     *            The MatsFactory to set.
     * @return this instance of the object, for chaining.
     */
    MatsTestEndpoint<R, S, I> setMatsFactory(MatsFactory matsFactory);

    /**
     * Verify that the endpoint was not invoked up to this point - <b>but remember that it is notoriously difficult to
     * verify that something <i>has not</i> happened in such a very asynchronous system that Mats3 is</b>: The Mats flow
     * might not yet have reached the point where this message potentially could have been sent, and even if it has, it
     * might be that the test endpoint has not yet gotten the message. You must at least verify that you are <i>past</i>
     * the point where the message possibly could have been sent, e.g. by having verified that the Mats flow have
     * completed by having reached the Terminator. You should put any such "has not happened" assertions at the end of
     * your test. To make it slightly more probable that such a message gets through, there's a 25 milliseconds wait
     * before the test is performed.
     */
    void verifyNotInvoked();

    /**
     * Wait for a single message to arrive at this endpoint. This will block until a message arrives, or the default
     * timeout (30 sec) has passed, in which case an {@link AssertionError} is thrown.
     *
     * @return the Message.
     */
    Message<S, I> awaitInvocation();

    /**
     * Wait for a single message to arrive at this endpoint. This will block until a message arrives, or the specified
     * timeout has passed, in which case an {@link AssertionError} is thrown.
     *
     * @return the Message.
     */
    Message<S, I> awaitInvocation(long millisToWait);

    /**
     * Wait for a number of messages to arrive at this endpoint. This will block until the specified number of messages
     * arrive, or the default timeout (30 sec) has passed, in which case an {@link AssertionError} is thrown.
     *
     * @param expectedNumberOfIncomingMsgs
     *            the number of messages to wait for.
     * @return the list of Messages.
     */
    List<Message<S, I>> awaitInvocations(int expectedNumberOfIncomingMsgs);

    /**
     * Wait for a number of messages to arrive at this endpoint. This will block until the specified number of messages
     * arrive, or the specified timeout has passed, in which case an {@link AssertionError} is thrown.
     *
     * @param expectedNumberOfIncomingMsgs
     *            the number of messages to wait for.
     * @param millisToWait
     *            the number of milliseconds to wait before timing out.
     * @return the list of Messages.
     */
    List<Message<S, I>> awaitInvocations(int expectedNumberOfIncomingMsgs, long millisToWait);

    /**
     * Representation of the incoming message to the endpoint, as returned by the await-methods.
     */
    interface Message<S, I> {
        /**
         * Get the {@link ProcessContext} for this message. This is the context that was passed to the endpoint when it
         * was invoked. You may get sideloads, and other information from this context.
         *
         * @return the {@link ProcessContext} for this message.
         */
        DetachedProcessContext getContext();

        /**
         * Get the state of the endpoint. This is the state that was passed to the endpoint when it was invoked.
         *
         * @return the state that was passed to the endpoint when it was invoked.
         */
        S getState();

        /**
         * Get the incoming message. This is the message that was passed to the endpoint when it was invoked.
         *
         * @return the incoming message.
         */
        I getData();
    }

    /**
     * Mock endpoint which processes an incoming message and returns a reply. Ideal for mocking intermediate endpoints
     * in a multi-stage flow whether they are internal to the application or external.
     * <p>
     * If no processor is defined this will function as a {@link ITerminator}, thus one should consider using that
     * instead if one ends up in a test situation where the processor is never defined.
     *
     * @param <R>
     *            The reply class of the message generated by this endpoint.
     * @param <I>
     *            The incoming message class for this endpoint.
     */
    interface IEndpoint<R, I> extends MatsTestEndpoint<R, Void, I> {
        /**
         * Specify the processing lambda to be executed by the endpoint aka the endpoint logic. This is typically
         * invoked either inside a test method to setup the behavior for that specific test or once through the initial
         * setup when creating the test endpoint.
         *
         * @param processLambda
         *            which the endpoint should execute on an incoming request.
         */
        IEndpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> processLambda);

        /**
         * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
         * <p>
         * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
         * <code>@SpringInjectRulesAndExtensions</code> should this Rule be utilized in a test where a Spring context is
         * in play.
         *
         * @param matsFactory
         *            to set.
         * @return this instance of the object.
         */
        @Override
        IEndpoint<R, I> setMatsFactory(MatsFactory matsFactory);
    }

    /**
     * Equivalent to {@link IEndpoint}, but allows for the definition of a state class. Thus, can be utilized to
     * validate the passed state for flows which spin off new MATS^3 flows and seeds the state of the target endpoint
     * through the use of {@link MatsInitiate#send(Object, Object)}.
     *
     * @param <R>
     *            The reply class of the message generated by this endpoint.
     * @param <S>
     *            The state class of the endpoint.
     * @param <I>
     *            The incoming message class for this endpoint.
     */
    interface IEndpointWithState<R, S, I> extends MatsTestEndpoint<R, S, I> {
        /**
         * Specify the processing lambda to be executed by the endpoint aka the endpoint logic. This is typically
         * invoked either inside a test method to setup the behavior for that specific test or once through the initial
         * setup when creating the test endpoint.
         *
         * @param processLambda
         *            which the endpoint should execute on an incoming request.
         */
        IEndpointWithState<R, S, I> setProcessLambda(ProcessSingleStateLambda<R, S, I> processLambda);

        /**
         * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
         * <p>
         * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
         * <code>@SpringInjectRulesAndExtensions</code> should this Rule be utilized in a test where a Spring context is
         * in play.
         *
         * @param matsFactory
         *            to set.
         * @return this instance of the object.
         */
        @Override
        IEndpointWithState<R, S, I> setMatsFactory(MatsFactory matsFactory);
    }

    @FunctionalInterface
    interface ProcessSingleStateLambda<R, S, I> {
        R process(ProcessContext<R> ctx, S state, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Simplified version of {@link IEndpoint}, which doesn't require the specification of a return class. Useful for
     * scenarios where one is only interested in the incoming message and has no need for processor.
     *
     * @param <I>
     *            The incoming message class for this endpoint.
     */
    interface ITerminator<I> extends MatsTestEndpoint<Void, Void, I> {
        /**
         * Specify the processing lambda to be executed by the endpoint aka the endpoint logic. This is typically
         * invoked either inside a test method to setup the behavior for that specific test or once through the initial
         * setup when creating the test endpoint.
         *
         * @param processLambda
         *            which the endpoint should execute on an incoming request.
         */
        ITerminator<I> setProcessLambda(ProcessTerminatorNoStateLambda<I> processLambda);

        /**
         * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
         * <p>
         * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
         * <code>@SpringInjectRulesAndExtensions</code> should this Rule be utilized in a test where a Spring context is
         * in play.
         *
         * @param matsFactory
         *            to set.
         * @return this instance of the object.
         */
        @Override
        ITerminator<I> setMatsFactory(MatsFactory matsFactory);
    }

    @FunctionalInterface
    interface ProcessTerminatorNoStateLambda<I> {
        void process(ProcessContext<Void> ctx, I msg) throws MatsRefuseMessageException;
    }

    /**
     * Expanded version of {@link ITerminator}, which also allows for the specification of a state class.
     *
     * @param <S>
     *            The state class of the endpoint.
     * @param <I>
     *            The incoming message class for this endpoint.
     */
    interface ITerminatorWithState<S, I> extends MatsTestEndpoint<Void, S, I> {
        /**
         * Specify the processing lambda to be executed by the endpoint aka the endpoint logic. This is typically
         * invoked either inside a test method to setup the behavior for that specific test or once through the initial
         * setup when creating the test endpoint.
         *
         * @param processLambda
         *            which the endpoint should execute on an incoming request.
         */
        ITerminatorWithState<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> processLambda);

        /**
         * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
         * <p>
         * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
         * <code>@SpringInjectRulesAndExtensions</code> should this Rule be utilized in a test where a Spring context is
         * in play.
         *
         * @param matsFactory
         *            to set.
         * @return this instance of the object.
         */
        @Override
        ITerminatorWithState<S, I> setMatsFactory(MatsFactory matsFactory);
    }
}
