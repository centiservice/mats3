package io.mats3.test.jupiter;

import javax.inject.Inject;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.MatsEndpoint.ProcessSingleLambda;
import io.mats3.MatsFactory;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint;

/**
 * Extension to create a single staged endpoint whose reply/processor can be changed throughout its life, i.e. per test
 * (e.g. answer "Sorry, no can do." for the first test, and then "Yes, we can!" for the next test). The Extension can be
 * instantiated {@link #create(String, Class, Class, ProcessSingleLambda) with} or {@link #create(String, Class, Class)
 * without} a predefined processor. Useful for mocking endpoints in tests where you need predictable replies, and may
 * also be used to verify that an endpoint was <em>not</em> invoked.
 * <p>
 * The endpoint processor can be changed on demand using {@link #setProcessLambda(ProcessSingleLambda)}
 * <p>
 * Must be annotated with {@link org.junit.jupiter.api.extension.RegisterExtension @RegisterExtension}.
 * <p>
 * Retrieve the endpoint's received(incoming) message/messages by calling on of the following methods:
 * <ul>
 * <li>{@link Extension_MatsEndpoint#waitForRequest()} - Wait for a message(singular) using the default timeout</li>
 * <li>{@link Extension_MatsEndpoint#waitForRequest(long)} - Wait for a message(singular) with user specified
 * timeout</li>
 * <li>{@link Extension_MatsEndpoint#waitForRequests(int)} - Wait for X messages using the default timeout</li>
 * <li>{@link Extension_MatsEndpoint#waitForRequests(int, long)} - Wait for X messages with user specified timeout</li>
 * </ul>
 * Given a case where one does not expect the endpoint to be invoked (no messages received) one can utilize
 * {@link Extension_MatsEndpoint#verifyNotInvoked()} to ensure that the endpoint was not in fact invoked during the
 * test.
 * <p>
 * If no process lambda is specified for the endpoint it will act as a terminator, thus it does not generate a reply.
 * <p>
 *
 * <pre>
 * &#64;RegisterExtension
 * public Extension_MatsEndpoint&lt;String, String&gt; _world = Extension_MatsEndpoint.single(endpointFactory, "World",
 *         String.class, String.class, (context, in) -> in + "World");
 * </pre>
 *
 * Should one want to utilize this test endpoint approach in a test which brings up a Spring context which contains a
 * {@link MatsFactory} one can utilize the <code>@SpringInjectRulesAndExtensions</code> which will inject/autowire this
 * class automatically by providing the {@link MatsFactory} located in said Spring context.
 *
 * <pre>
 *     &#64;TestExecutionListeners(value = {ExtensionMatsAutowireTestExecutionListener.class},
 *     mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
 * </pre>
 *
 * @param <R>
 *            The reply class of the message generated by this endpoint. (Reply Class)
 * @param <I>
 *            The incoming message class for this endpoint. (Request Class)
 * @author Kevin Mc Tiernan, 22-10-2020, kmctiernan@gmail.com
 */
public class Extension_MatsEndpoint<R, I> extends AbstractMatsTestEndpoint<R, I> implements BeforeEachCallback,
        AfterEachCallback {

    /**
     * Private constructor, utilize {@link #create(String, Class, Class)} to create an instance of this object.
     */
    private Extension_MatsEndpoint(String endpointId, Class<R> replyMsgClass, Class<I> incomingMsgClass) {
        super(endpointId, replyMsgClass, incomingMsgClass);
    }

    /**
     * Sets the internal {@link MatsFactory} to be utilized for the creation of this endpoint.
     * <p>
     * If not utilized explicitly can also be injected/autowired through the use of the test execution listener
     * <code>SpringInjectRulesAndExtensions</code>> should this Extension be utilized in a test where a Spring context
     * is in play.
     *
     * @param matsFactory
     *            to set.
     * @return this instance of the object.
     */
    @Inject
    @Override
    public Extension_MatsEndpoint<R, I> setMatsFactory(MatsFactory matsFactory) {
        _matsFactory = matsFactory;
        return this;
    }

    @Override
    public Extension_MatsEndpoint<R, I> setProcessLambda(ProcessSingleLambda<R, I> processLambda) {
        _processLambda = processLambda;
        return this;
    }

    /**
     * Creates a Jupiter Extension for a single-staged endpoint whose processor is <i>not</i> defined at start. Sets it
     * up on JUnit lifecycle 'before' and tears it down on 'after'. <b>Notice that a {@link MatsFactory} must be set
     * before it is usable!</b> In a Spring environment, you should probably employ the
     * <code>@SpringInjectRulesAndExtensions</code> to make this happen automagically. In a "pure Java" environment,
     * consider the convenience overload {@link #create(Extension_Mats, String, Class, Class) create(Mats_Rule,
     * endpointId, replyClass, incomingClass)} to easily supply the corresponding
     * <code>{@literal @RegisterExtension}</code> {@link Extension_Mats} for fetching the <code>MatsFactory</code>.
     * <p>
     * <b>Do notice that you need to invoke {@link #setProcessLambda(ProcessSingleLambda)} - typically inside the
     * &#64;Test method - before sending messages to it, as there is no default.</b>
     *
     * @param endpointId
     *            of the endpoint.
     * @param replyMsgClass
     *            the class of the reply message generated by this endpoint.
     * @param incomingMsgClass
     *            the incoming message class for this endpoint.
     * @return {@link Extension_Mats} without a predefined processLambda.
     */
    public static <R, I> Extension_MatsEndpoint<R, I> create(String endpointId, Class<R> replyMsgClass,
            Class<I> incomingMsgClass) {
        return new Extension_MatsEndpoint<>(endpointId, replyMsgClass, incomingMsgClass);
    }

    /**
     * Convenience variant of {@link #create(String, Class, Class) create(endpointId, replyClass, incomingClass)} taking
     * a {@link Extension_Mats} as first argument for fetching the {@link MatsFactory}, for use in "pure Java"
     * environments (read as: non-Spring).
     */
    public static <R, I> Extension_MatsEndpoint<R, I> create(Extension_Mats matsRule, String endpointId,
            Class<R> replyMsgClass,
            Class<I> incomingMsgClass) {
        Extension_MatsEndpoint<R, I> extension_matsEndpoint = new Extension_MatsEndpoint<>(endpointId, replyMsgClass,
                incomingMsgClass);
        // Set MatsFactory from the supplied Rule_Mats
        extension_matsEndpoint.setMatsFactory(matsRule.getMatsFactory());
        return extension_matsEndpoint;
    }

    // ================== Jupiter LifeCycle ===========================================================================

    @Override
    public void beforeEach(ExtensionContext context) {
        super.before();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        super.after();
    }
}
