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
 * Convenience implementation of {@link Extension_MatsEndpoint} for scenarios where one is implementing a terminator
 * endpoint.
 * <p>
 * Creating a terminator endpoint can be achieved using {@link Extension_MatsEndpoint}, however, regardless of being a
 * terminator or not one has to specify the <i>replyClass</i> when using that class. While this convenience version acts
 * as a true terminator eliding the need for the <i>replyClass</i> all together.
 * <p>
 * The following example illustrates the exact same endpoint using the different classes:
 * <pre>
 * // Terminator using Extension_MatsEndpoint
 * &#64;RegisterExtension
 * public Extension_MatsEndpoint&lt;Void, String&gt; _helloEndpoint =
 *             Extension_MatsEndpoint.create("endpointId", Void.class, String.class);
 *
 * // Terminator using Extension_MatsTerminatorEndpoint
 * &#64;RegisterExtension
 * public Extension_MatsTerminatorEndpoint&lt;String&gt; _helloEndpoint =
 *             Extension_MatsTerminatorEndpoint.create("endpointId", String.class);
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
 * message one could mock the targets with {@link Extension_MatsTerminatorEndpoint}.
 * <pre>
 * &#64;RegisterExtension
 * public Extension_MatsTerminatorEndpoint&lt;StateUpdateDto&gt; _serviceMock =
 *             Extension_MatsTerminatorEndpoint.create("Service1", StateUpdateDto.class);
 * </pre>
 * Using {@link Extension_MatsTerminatorEndpoint#waitForMessage} one could read the incoming message from the
 * {@link Result result} via {@link Result#getData() result.getData()}. If one were to side load bytes as part of the
 * message one could also verify this via {@link Result#getContext() result.getContext()},
 * {@link io.mats3.MatsEndpoint.DetachedProcessContext#getBytes(String) context.getBytes(String)}.
 *
 * @author Kevin Mc Tiernan, 2025-04-03, kevin.mc.tiernan@storebrand.no
 * @author Endre Stølsvik 2024-04-03 - http://stolsvik.com/, endre@stolsvik.com
 * @see Extension_MatsEndpoint
 * @see Extension_MatsTerminatorStateEndpoint
 */
public class Extension_MatsTerminatorEndpoint<I> extends AbstractMatsTestTerminatorEndpoint<Void, I>
        implements BeforeEachCallback, AfterEachCallback {
    private static final Logger log = LoggerFactory.getLogger(Extension_MatsTerminatorEndpoint.class);

    /**
     * Private constructor, utilize {@link #create(String, Class)} to create an instance of this object.
     */
    private Extension_MatsTerminatorEndpoint(String endpointId, Class<I> incomingMsgClass) {
        super(endpointId, Void.class, incomingMsgClass);
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
    public Extension_MatsTerminatorEndpoint<I> setMatsFactory(MatsFactory matsFactory) {
        log.debug("+++ Jupiter +++ setMatsFactory(" + matsFactory + ") invoked.");
        _matsFactory = matsFactory;
        return this;
    }

    @Override
    public Extension_MatsTerminatorEndpoint<I> setProcessLambda(ProcessTerminatorLambda<Void, I> processLambda) {
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
     * {@link #create(Extension_Mats, String, Class) create(extensionMats, endpointId, incomingClass)} to easily supply
     * the corresponding
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
    public static <I> Extension_MatsTerminatorEndpoint<I> create(String endpointId, Class<I> incomingMsgClass) {
        return new Extension_MatsTerminatorEndpoint<>(endpointId, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #create(String, Class) create(endpointId, incomingClass)} taking a
     * {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring) - but note that if you also use {@link Extension_Mats}, a MatsFactory will
     * also be available in the ExtensionContext, which this extension then will find and use.
     * <p>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessTerminatorLambda)} - typically inside the
     * &#64;Test method - before sending messages to it, as there is no default.</b>
     */
    public static <I> Extension_MatsTerminatorEndpoint<I> create(Extension_Mats extensionMats, String endpointId,
            Class<I> incomingMsgClass) {
        Extension_MatsTerminatorEndpoint<I> extension_matsTerminatorendpoint = new Extension_MatsTerminatorEndpoint<>(
                endpointId, incomingMsgClass);
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
                        + " Extension_MatsTerminatorEndpoint.");
            }
        }
        super.before();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        super.after();
    }
}
