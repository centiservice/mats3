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

package io.mats3.test.junit.matsendpoint;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Simple;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.abstractunit.AbstractMatsTestEndpoint;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.test.junit.Rule_MatsEndpoint;
import io.mats3.test.junit.Rule_MatsEndpoints;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Illustrates that {@link Rule_MatsEndpoint} is autowired by Spring when using the test execution listener provided by
 * this library. We use two static inner <code>{@literal @Configuration classes}</code>, which automagically is picked
 * up by the Spring test facility:
 * <ol>
 * <li>{@link MatsSpringContext} to set up the Mats infrastructure. This thus sets up the MatsFactory, which "magically"
 * will be injected into the {@link Rule_MatsEndpoint} with the help from the {@link SpringInjectRulesAndExtensions}.
 * Note that the configured beans in this Configuration could much simpler have been "imported" into a test by means of
 * the <code>{@literal @MatsTestContext}</code> annotation from the mats-spring-test project.</li>
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
@RunWith(SpringJUnit4ClassRunner.class)
@SpringInjectRulesAndExtensions
public class U_RuleMatsEndpoint_SpringWiringTest {

    /**
     * NOTICE: This entire <code>{@literal @Configuration}</code> class can be replaced by annotating either the test
     * class (the outer class here, {@link U_RuleMatsEndpoint_SpringWiringTest}), or an inner
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
        public MatsTestBroker mqBroker() {
            return MatsTestBroker.create();
        }

        @Bean
        public MatsInitiator matsInitiator(MatsFactory matsFactory) {
            return matsFactory.getDefaultInitiator();
        }

        @Bean
        public MatsFactory matsFactory(MatsTestBroker broker) {
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
     * Set up a Terminator using proper/actual Mats JavaConfig.
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
     * Set up an Endpoint using the {@link Rule_MatsEndpoint}.
     */
    @Rule // Rule's need of MatsFactory is autowired by Spring due to the @SpringInjectRulesAndExtensions annotation
    public Rule_MatsEndpoint<String, String> _hello = Rule_MatsEndpoint
            .create("HelloEndpoint", String.class, String.class)
            .setProcessLambda((ctx, msg) -> "Hello " + msg);

    @Rule
    public Rule_MatsEndpoints.Terminator<String> _terminator =
            Rule_MatsEndpoints.createTerminator("MockTerminator", String.class);
    @Rule
    public Rule_MatsEndpoints.Endpoint<String, String> _endpoint =
            Rule_MatsEndpoints.createEndpoint("MockEndpoint", String.class, String.class);

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
        Assert.assertEquals("World!", requestMessage);

        // Wait for the reply
        Reply<String> reply = future.get(10, TimeUnit.SECONDS);

        // Assert that the endpoint replied
        Assert.assertEquals("Hello World!", reply.get());
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
        Assert.assertEquals("Hello Beautiful World!", result.getData());
    }

    /**
     * Primarily testing that the autowiring/injection of {@link MatsFactory} functions properly.
     */
    @Test
    public void verifyAutowiring_EndpointsTerminator() {
        // :: Setup
        String world = "World";

        // :: Act
        _matsInitiator.initiateUnchecked(init -> init.traceId("Test")
                .from(getClass().getSimpleName())
                .to("MockTerminator")
                .send(world));

        // Verify
        AbstractMatsTestEndpoint.Result<Void, String> result = _terminator.waitForResult();
        Assert.assertEquals(world, result.getData());
    }

    @Test
    public void verifyAutowiring_EndpointsEndpoint() throws ExecutionException, InterruptedException, TimeoutException {
        // :: Setup
        String world = "World";
        _endpoint.setProcessLambda((ctx, msg) -> "Hello " + world);

        // :: Act
        String result = _matsFuturizer
                .futurizeNonessential("Test", getClass().getSimpleName(), "MockEndpoint", String.class, world)
                .thenApply(Reply::get)
                .get(1, TimeUnit.SECONDS);
        // :: Verify
        Assert.assertEquals("Hello " + world, result);

        AbstractMatsTestEndpoint.Result<Void, String> epResult = _endpoint.waitForResult();

        Assert.assertEquals(world, epResult.getData());
    }
}
