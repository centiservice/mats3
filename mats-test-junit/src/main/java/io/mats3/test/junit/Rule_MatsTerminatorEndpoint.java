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

import javax.inject.Inject;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsTestTerminatorEndpoint;

/**
 * Convenience implementation of {@link Rule_MatsEndpoint} for scenarios where one is implementing a terminator
 * endpoint.
 * <p>
 * Creating a terminator endpoint can be achieved using {@link Rule_MatsEndpoint}, however, regardless of being a
 * terminator or not one has to specify the <i>replyClass</i> when using that class. While this convenience version acts
 * as a true terminator eliding the need for the <i>replyClass</i> all together.
 * <p>
 * The following example illustrates the exact same endpoint using the different classes:
 * <pre>
 * // Terminator using Rule_MatsEndpoint
 * &#64;Rule
 * public Rule_MatsEndpoint&lt;Void, String&gt; _helloEndpoint =
 *             Rule_MatsEndpoint.create("endpointId", Void.class, String.class);
 *
 * // Terminator using Rule_MatsTerminatorEndpoint
 * &#64;Rule
 * public Rule_MatsTerminatorEndpoint&lt;String&gt; _helloEndpoint =
 *             Rule_MatsTerminatorEndpoint.create("endpointId", String.class);
 * </pre>
 * Primary use case for terminator endpoints are to test flows which spawn new MATS^3 flows i.e. endpoints which fork
 * off a new MATS^3 flow as a side effect.
 * <p>
 * An example of this could be an order flow where one wants to notify other services wrt the state being changed on a
 * given order. The implementation could look something like this:
 * <pre>
 * single("finishTransition", ReplyDto.class, IncMsgDto.class, (ctx, msg) -> {
 *      orderRepository.updateState(msg.id, "FINISH");
 *      // :: Notify "other listeners" of the finish transition
 *      ctx.initiate(init -> {
 *          for (String interestedServices : List.of("Service1", "Service2")) {
 *              init.to(interestedServices)
 *                  .send(new StateUpdateDto(...));
 *              }
 *          })
 *      return new ReplyDto(...);
 * });
 * </pre>
 * In order to assert that the notification contains the data points one expects and that the correct targets get their
 * message one could mock the targets with {@link Rule_MatsTerminatorEndpoint}.
 * <pre>
 * &#64;Rule
 * public Rule_MatsTerminatorEndpoint&lt;StateUpdateDto&gt; _serviceMock =
 *             Rule_MatsTerminatorEndpoint.create("Service1", StateUpdateDto.class);
 * </pre>
 * Using {@link Rule_MatsTerminatorEndpoint#waitForMessage} one could read the incoming message from the {@link Result result}
 * via {@link Result#getData() result.getData()}. If one were to side load bytes as part of the message one could also
 * verify this via {@link Result#getContext() result.getContext()},
 * {@link io.mats3.MatsEndpoint.DetachedProcessContext#getBytes(String) context.getBytes(String)}.
 *
 * @author Kevin Mc Tiernan, 2025-04-01, kmctiernan@gmail.com
 * @author Endre Stølsvik 2024-04-01 - http://stolsvik.com/, endre@stolsvik.com
 * @see Rule_MatsEndpoint
 * @see Rule_MatsTerminatorStateEndpoint
 */
public class Rule_MatsTerminatorEndpoint<I> extends AbstractMatsTestTerminatorEndpoint<Void, I> implements TestRule {

    private static final Logger log = LoggerFactory.getLogger(Rule_MatsTerminatorEndpoint.class);

    /**
     * Private constructor, utilize {@link #create(String, Class)} to create an instance of this object.
     */
    private Rule_MatsTerminatorEndpoint(String endpointId, Class<I> incomingMsgClass) {
        super(endpointId, Void.class, incomingMsgClass);
    }

    /**
     * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
     * <p>
     * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
     * <code>@SpringInjectRulesAndExtensions</code> should this Rule be utilized in a test where a Spring context is in
     * play.
     *
     * @param matsFactory
     *         to set.
     * @return this instance of the object.
     */
    @Inject
    @Override
    public Rule_MatsTerminatorEndpoint<I> setMatsFactory(MatsFactory matsFactory) {
        log.debug("+++ JUnit +++ setMatsFactory(" + matsFactory + ") invoked.");
        _matsFactory = matsFactory;
        return this;
    }

    @Override
    public Rule_MatsTerminatorEndpoint<I> setProcessLambda(ProcessTerminatorLambda<Void, I> processLambda) {
        log.debug("+++ JUnit +++ setProcessLambda(" + processLambda + ") invoked.");
        _processLambda = processLambda;
        return this;
    }

    /**
     * Creates a JUnit Rule for a single-staged terminator endpoint whose processor is <i>not</i> defined at start. Sets
     * it up on JUnit lifecycle 'before' and tears it down on 'after'. <b>Notice that a {@link MatsFactory} must be set
     * before it is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload
     * {@link #create(Rule_Mats, String, Class) create(Mats_Rule, endpointId, incomingClass)} to easily supply the
     * corresponding <code>{@literal @ClassRule}</code> {@link Rule_Mats} for fetching the
     * <code>MatsFactory</code>.
     * <p/>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessTerminatorLambda)} - typically inside the
     * <code>{@literal @Test}</code> method - before sending messages to it, as there is no default.</b>
     *
     * @param endpointId
     *         of the endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Rule_MatsTerminatorEndpoint}
     */
    public static <I> Rule_MatsTerminatorEndpoint<I> create(String endpointId, Class<I> incomingMsgClass) {
        return new Rule_MatsTerminatorEndpoint<>(endpointId, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #create(String, Class) create(endpointId, incomingClass)} taking a
     * {@link Rule_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java" environments
     * (read as: non-Spring).
     */
    public static <I> Rule_MatsTerminatorEndpoint<I> create(Rule_Mats matsRule, String endpointId,
            Class<I> incomingMsgClass) {
        Rule_MatsTerminatorEndpoint<I> rule_matsTerminator = new Rule_MatsTerminatorEndpoint<>(endpointId,
                incomingMsgClass);
        // Set MatsFactory from the supplied Rule_Mats
        rule_matsTerminator.setMatsFactory(matsRule.getMatsFactory());
        return rule_matsTerminator;
    }

    // ================== Junit LifeCycle =============================================================================

    /**
     * Note: Shamelessly inspired from: <a href="https://stackoverflow.com/a/48759584">How to combine &commat;Rule and
     * &commat;ClassRule in JUnit 4.12</a>
     */
    @Override
    public Statement apply(Statement base, Description description) {
        if (description.isSuite()) {
            throw new IllegalStateException("The Rule_MatsTerminatorEndpoint should be applied as a @Rule, NOT as a"
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
