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
 * Full version of {@link Rule_MatsTerminatorEndpoint} which allows one to verify incoming state as well as the
 * incoming message.
 * <p>
 * Use case for this implementation would be a flow which is started using a
 * {@link io.mats3.MatsInitiator.MatsInitiate matsInitiate} and setting the
 * {@link io.mats3.MatsInitiator.MatsInitiate#replyTo(String, Object) replyTo(String, Object)} property of the initiate.
 * In such a scenario, one can utilize {@link Rule_MatsTerminatorStateEndpoint} in order to both verify the incoming
 * state and the incoming message.
 *
 * @author Kevin Mc Tiernan, 2025-04-01, kevin.mc.tiernan@storebrand.no
 * @author Endre Stølsvik 2024-04-01 - http://stolsvik.com/, endre@stolsvik.com
 * @see Rule_MatsEndpoint
 * @see Rule_MatsTerminatorEndpoint
 */
public class Rule_MatsTerminatorStateEndpoint<S, I> extends AbstractMatsTestTerminatorEndpoint<S, I>
        implements TestRule {

    private static final Logger log = LoggerFactory.getLogger(Rule_MatsTerminatorStateEndpoint.class);

    /**
     * Private constructor, utilize {@link #create(String, Class, Class)} to create an instance of this object.
     */
    private Rule_MatsTerminatorStateEndpoint(String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        super(endpointId, stateClass, incomingMsgClass);
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
    public Rule_MatsTerminatorStateEndpoint<S, I> setMatsFactory(MatsFactory matsFactory) {
        log.debug("+++ JUnit +++ setMatsFactory(" + matsFactory + ") invoked.");
        _matsFactory = matsFactory;
        return this;
    }

    @Override
    public Rule_MatsTerminatorStateEndpoint<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> processLambda) {
        log.debug("+++ JUnit +++ setProcessLambda(" + processLambda + ") invoked.");
        _processLambda = processLambda;
        return this;
    }

    /**
     * Creates a JUnit Rule for a single-staged terminator endpoint whose processor is <i>not</i> defined at start.
     * Sets it up on JUnit lifecycle 'before' and tears it down on 'after'. <b>Notice that a {@link MatsFactory} must
     * be set before it is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload {@link #create(Rule_Mats, String, Class, Class) create(Mats_Rule, endpointId,
     * stateClass, incomingClass)} to easily supply the corresponding <code>{@literal @ClassRule}</code>
     * {@link Rule_Mats} for fetching the <code>MatsFactory</code>.
     * <p/>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessTerminatorLambda)} - typically inside the
     * <code>{@literal @Test}</code> method - before sending messages to it, as there is no default.</b>
     *
     * @param endpointId
     *         of the endpoint.
     * @param stateClass
     *         of the state in this endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Rule_MatsEndpoint}
     */
    public static <S, I> Rule_MatsTerminatorStateEndpoint<S, I> create(String endpointId, Class<S> stateClass,
            Class<I> incomingMsgClass) {
        return new Rule_MatsTerminatorStateEndpoint<>(endpointId, stateClass, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #create(String, Class, Class) create(endpointId, stateClass, incomingClass)} taking
     * a {@link Rule_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java" environments
     * (read as: non-Spring).
     */
    public static <S, I> Rule_MatsTerminatorStateEndpoint<S, I> create(Rule_Mats matsRule, String endpointId,
            Class<S> stateClass, Class<I> incomingMsgClass) {
        Rule_MatsTerminatorStateEndpoint<S, I> rule_matsTerminator = new Rule_MatsTerminatorStateEndpoint<>(endpointId,
                stateClass, incomingMsgClass);
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
            throw new IllegalStateException("The Rule_MatsTerminatorStateEndpoint should be applied as a @Rule, NOT as"
                                            + " a @ClassRule");
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
