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

import java.util.Optional;

import javax.inject.Inject;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.ProcessTerminatorLambda;
import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsTestTerminatorEndpoint;

/**
 * Full version of {@link Extension_MatsTerminatorEndpoint} which allows one to verify incoming state as well as the
 * incoming message.
 * <p>
 * Use case for this implementation would be a flow which is started using a
 * {@link io.mats3.MatsInitiator.MatsInitiate matsInitiate} and setting the
 * {@link io.mats3.MatsInitiator.MatsInitiate#replyTo(String, Object) replyTo(String, Object)} property of the initiate.
 * In such a scenario, one can utilize {@link Extension_MatsTerminatorEndpoint} in order to both verify the incoming
 * state and the incoming message.
 *
 * @author Kevin Mc Tiernan, 2025-04-07, kevin.mc.tiernan@storebrand.no
 * @author Endre Stølsvik 2024-04-07 - http://stolsvik.com/, endre@stolsvik.com
 * @see Extension_MatsEndpoint
 * @see Extension_MatsTerminatorEndpoint
 */
public class Extension_MatsTerminatorStateEndpoint<S, I> extends AbstractMatsTestTerminatorEndpoint<S, I> implements
        BeforeEachCallback, AfterEachCallback {
    private static final Logger log = LoggerFactory.getLogger(Extension_MatsTerminatorStateEndpoint.class);

    /**
     * Private constructor, utilize {@link #create(String, Class, Class)} to create an instance of this object.
     */
    private Extension_MatsTerminatorStateEndpoint(String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        super(endpointId, stateClass, incomingMsgClass);
    }

    /**
     * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
     * <p>
     * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
     * <code>SpringInjectRulesAndExtensions</code> should this Extension be utilized in a test where a Spring context
     * is in play.
     *
     * @param matsFactory
     *         to set.
     * @return this instance of the object.
     */
    @Inject
    @Override
    public Extension_MatsTerminatorStateEndpoint<S, I> setMatsFactory(MatsFactory matsFactory) {
        log.debug("+++ Jupiter +++ setMatsFactory(" + matsFactory + ") invoked.");
        _matsFactory = matsFactory;
        return this;
    }

    @Override
    public Extension_MatsTerminatorStateEndpoint<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> processLambda) {
        log.debug("+++ JUnit +++ setProcessLambda(" + processLambda + ") invoked.");
        _processLambda = processLambda;
        return this;
    }

    /**
     * Creates a Jupiter Extension for a single-staged terminator endpoint whose processor is <i>not</i> defined at
     * start. Sets it up on JUnit lifecycle 'before' and tears it down on 'after'. <b>Notice that a {@link MatsFactory}
     * must be set before it is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload
     * {@link #create(Extension_Mats, String, Class, Class) create(extensionMats, endpointId, stateClass,
     * incomingClass)} to easily supply the corresponding
     * <code>{@literal @RegisterExtension}</code> {@link Extension_Mats} for fetching the <code>MatsFactory</code>.
     * <p>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessTerminatorLambda)} - typically inside the
     * &#64;Test method - before sending messages to it, as there is no default.</b>
     *
     * @param endpointId
     *         of the endpoint.
     * @param incomingMsgClass
     *         the incoming message class for this endpoint.
     * @return {@link Extension_Mats} without a predefined processLambda.
     */
    public static <S, I> Extension_MatsTerminatorStateEndpoint<S, I> create(String endpointId, Class<S> stateClass,
            Class<I> incomingMsgClass) {
        return new Extension_MatsTerminatorStateEndpoint<>(endpointId, stateClass, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #create(String, Class, Class) create(endpointId, stateClass, incomingClass)} taking
     * a {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring) - but note that if you also use {@link Extension_Mats}, a MatsFactory will
     * also be available in the ExtensionContext, which this extension then will find and use.
     * <p>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessTerminatorLambda)} - typically inside the
     * &#64;Test method - before sending messages to it, as there is no default.</b>
     */
    public static <S, I> Extension_MatsTerminatorStateEndpoint<S, I> create(Extension_Mats extensionMats,
            String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        Extension_MatsTerminatorStateEndpoint<S, I> extension_matsTerminatorendpoint =
                new Extension_MatsTerminatorStateEndpoint<>(endpointId, stateClass, incomingMsgClass);
        // Set MatsFactory from the supplied Rule_Mats
        extension_matsTerminatorendpoint.setMatsFactory(extensionMats.getMatsFactory());
        return extension_matsTerminatorendpoint;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        // ?: Do we have the MatsFactory set yet?
        if (_matsFactory == null) {
            // -> No, so let's see if we can find it in the ExtensionContext (throws if not).
            Optional<Extension_Mats> matsFromContext = Extension_Mats.findFromContext(context);
            if (matsFromContext.isPresent()) {
                setMatsFactory(matsFromContext.get().getMatsFactory());
            }
            else {
                throw new IllegalStateException("MatsFactory is not set. Didn't find Extension_Mats in"
                        + " ExtensionContext, so couldn't get it from there either."
                        + " Either set it explicitly using setMatsFactory(matsFactory), or use"
                        + " Extension_Mats (which adds itself to the ExtensionContext), and"
                        + " ensure that it is initialized before this"
                        + " Extension_MatsTerminatorStateEndpoint.");
            }
        }
        super.before();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        super.after();
    }
}
