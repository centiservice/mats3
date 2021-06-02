package io.mats3.test.jupiter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Simple;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;
import io.mats3.util_activemq.MatsLocalVmActiveMq;

/**
 * Illustrates that {@link Extension_MatsEndpoint} is autowired by Spring when using the test execution listener
 * provided by this library. We use two static inner <code>{@literal @Configuration classes}</code>, which automagically
 * is picked up by the Spring test facility:
 * <ol>
 * <li>{@link MatsSpringContext} to set up the Mats infrastructure. This thus sets up the MatsFactory, which "magically"
 * will be injected into the {@link Extension_MatsEndpoint} with the help from the
 * {@link SpringInjectRulesAndExtensions}. Note that the configured beans in this Configuration could much simpler have
 * been "imported" into a test by means of the <code>{@literal @MatsTestContext}</code> annotation from the
 * mats-spring-test project.</li>
 * <li>{@link SetupTerminatorMatsEndpoint} to set up a Terminator using the direct, programmatic non-SpringConfig way,
 * this needing the <code>MatsFactory</code> injected, as well as a {@link MatsTestLatch} to notify the test that it has
 * received the reply.</li>
 * </ol>
 * There are two separate tests, one using a {@link MatsFuturizer} to wait for the reply from the "Hello"-endpoint
 * defined by the Rule_MatsEndpoint, and another using the Terminator set up in the second Configuration.
 *
 * @author Kevin Mc Tiernan, 2020-11-10, kmctiernan@gmail.com
 * @author Endre Stølsvik - 2020-11-15 18:07 - http://endre.stolsvik.com
 */
@ExtendWith(SpringExtension.class)
@SpringInjectRulesAndExtensions
public class J_SpringTestMatsEndpoint {

    /**
     * NOTICE: This entire <code>{@literal @Configuration}</code> class can be replaced by annotating either the test
     * class (the outer class here, {@link J_SpringTestMatsEndpoint}), or an inner
     * <code>{@literal @Configuration}</code>-class (i.e. {@link SetupTerminatorMatsEndpoint}) with the
     * <code>{@literal @MatsSimpleTestContext}</code> annotation from the mats-spring-test project.
     */
    @Configuration
    public static class MatsSpringContext {
        @Bean
        public MatsFuturizer futurizer(MatsFactory matsFactory) {
            return MatsFuturizer.createMatsFuturizer(matsFactory, "SpringTest");
        }

        @Bean
        public MatsLocalVmActiveMq mqBroker() {
            return MatsLocalVmActiveMq.createDefaultInVmActiveMq();
        }

        @Bean
        public MatsInitiator matsInitiator(MatsFactory matsFactory) {
            return matsFactory.getDefaultInitiator();
        }

        @Bean
        public MatsFactory matsFactory(MatsLocalVmActiveMq broker) {
            JmsMatsJmsSessionHandler_Simple jmsMatsJmsSessionHandler_simple = JmsMatsJmsSessionHandler_Simple.create(
                    broker.getConnectionFactory());
            return JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("MATS-JUNIT", "test",
                    jmsMatsJmsSessionHandler_simple, MatsSerializerJson.create());
        }

        @Bean
        public MatsTestLatch matsTestLatch() {
            return new MatsTestLatch();
        }
    }

    /**
     * Set up a Terminator using standard Mats JavaConfig.
     */
    @Configuration
    public static class SetupTerminatorMatsEndpoint {
        @Inject
        private MatsTestLatch _latch;

        @Inject
        private MatsFactory _matsFactory;

        @PostConstruct
        public void setupTerminator() {
            _matsFactory.terminator("Terminator",
                    void.class, String.class, (ctx, state, msg) -> _latch.resolve(ctx, null, msg));
        }
    }

    /**
     * Set up an Endpoint using the {@link Extension_MatsEndpoint}.
     */
    @RegisterExtension
    // Extensions's need of MatsFactory is autowired by Spring due to the @SpringInjectRulesAndExtensions annotation
    public Extension_MatsEndpoint<String, String> _hello = Extension_MatsEndpoint
            .create("HelloEndpoint", String.class, String.class)
            .setProcessLambda((ctx, msg) -> "Hello " + msg);

    @Inject
    private MatsTestLatch _matsTestLatch;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsInitiator _matsInitiator;

    @Test
    public void testMatsTestEndpointUsingFuturizer() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Act
        CompletableFuture<Reply<String>> future = _matsFuturizer.futurizeNonessential("Unit_Test_Futurizer",
                "UnitTestUsingFuture",
                "HelloEndpoint",
                String.class,
                "World!");

        // :: Assert

        // Assert that the MatsEndpoint defined by Rule_MatsEndpoint got (and processed) the request.
        String requestMessage = _hello.waitForRequest();
        Assertions.assertEquals("World!", requestMessage);

        // Wait for the reply
        Reply<String> reply = future.get(10, TimeUnit.SECONDS);

        // Assert that the endpoint replied
        Assertions.assertEquals("Hello World!", reply.getReply());
    }

    @Test
    public void testMatsTestEndpointUsingMatsTestLatch() {
        // :: Act
        _matsInitiator.initiateUnchecked(msg -> msg
                .traceId("Unit_Test_MatsTestLatch")
                .from("UnitTestUsingTestLatch")
                .to("HelloEndpoint")
                .replyTo("Terminator", null)
                .request("Beautiful World!"));

        // Wait for the Terminator to receive the reply
        Result<Void, String> result = _matsTestLatch.waitForResult();

        // :: Assert
        // Assert that the Terminator got the expected reply from "Hello"-endpoint.
        Assertions.assertEquals("Hello Beautiful World!", result.getData());
    }
}
