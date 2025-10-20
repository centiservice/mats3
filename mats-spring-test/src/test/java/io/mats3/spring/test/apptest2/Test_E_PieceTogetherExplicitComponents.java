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

package io.mats3.spring.test.apptest2;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.ConfigurationForTest;
import io.mats3.spring.Dto;
import io.mats3.spring.EnableMats;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.Sto;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories.TestCustomQualifier;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.RandomString;

/**
 * A test that points to only a specific @Configuration bean of an application, thus not taking up the entire
 * application - we have to provide the infrastructure (i.e. MatsFactories) in the test.
 *
 * @author Endre Stølsvik 2019-06-06 21:53 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@DirtiesContext
public class Test_E_PieceTogetherExplicitComponents {
    private static final String TERMINATOR = "Test_UseOnlyMatsConfigurationFromApplication.TERMINATOR";

    @ConfigurationForTest
    // This is where we import the application's endpoint configurations:
    @Import(AppEndpoints.class)
    // Enable SpringConfig of Mats, as nobody else is doing it now (done by AppMain_TwoMatsFactories normally):
    @EnableMats
    public static class TestConfig {
        /**
         * Create the "brokerX" for "matsFactoryX".
         */
        @Bean
        @Qualifier("brokerX")
        protected MatsTestBroker brokerX() {
            return MatsTestBroker.create();
        }

        /**
         * Create the "matsFactoryX" needed by endpoints defined in class Mats_Endpoint.
         */
        @Bean
        @TestCustomQualifier(region = "SouthWest")
        @Qualifier("matsFactoryX")
        protected MatsFactory matsFactoryX(@Qualifier("brokerX") MatsTestBroker broker) {
            return JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                    Test_E_PieceTogetherExplicitComponents.class.getSimpleName(), "#testing#",
                    JmsMatsJmsSessionHandler_Pooling.create(broker.getConnectionFactory()),
                    MatsSerializerJson.create());
        }

        /**
         * Create the "brokerX" for "matsFactoryX". This is forced to be a unique, in-vm broker, as opposed to the above
         * one, which can be directed to use an external broker by use of system properties, read JavaDoc of
         * {@link MatsTestBroker}.
         */
        @Bean
        @Qualifier("brokerY")
        protected MatsTestBroker brokerY() {
            return MatsTestBroker.createUniqueInVmActiveMq();
        }

        /**
         * Create the "matsFactoryY" needed by endpoints defined in class Mats_Endpoint.
         */
        @Bean
        @Qualifier("matsFactoryY")
        protected MatsFactory matsFactoryY(@Qualifier("brokerY") MatsTestBroker broker) {
            return JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                    Test_E_PieceTogetherExplicitComponents.class.getSimpleName(), "#testing#",
                    JmsMatsJmsSessionHandler_Pooling.create(broker.getConnectionFactory()),
                    MatsSerializerJson.create());
        }

        @Bean
        public MatsTestLatch latch() {
            return new MatsTestLatch();
        }

        @Inject
        private MatsTestLatch _latch;

        @Bean
        public AtomicInteger getAtomicInteger() {
            return new AtomicInteger();
        }

        /**
         * Test "Terminator" endpoint where we send the result of testing the endpoint in the application.
         * <p />
         * <b>Notice how we register this on both the MatsFactories.</b>
         */
        @MatsMapping(endpointId = TERMINATOR, matsFactoryQualifierValue = "matsFactoryX")
        @MatsMapping(endpointId = TERMINATOR, matsFactoryQualifierValue = "matsFactoryY")
        public void testTerminatorEndpoint(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            _latch.resolve(state, msg);
        }
    }

    @Inject
    @Qualifier("matsFactoryX")
    private MatsFactory _matsFactoryX;

    @Inject
    @Qualifier("matsFactoryY")
    private MatsFactory _matsFactoryY;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void testOnMatsFactoryX() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        _matsFactoryX.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(TERMINATOR, null)
                    .request(dto);
        });
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ":single"), result.getData());
    }

    @Test
    public void testOnMatsFactoryY() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        _matsFactoryY.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(TERMINATOR, null)
                    .request(dto);
        });
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 3, dto.string + ":single_on_class"), result.getData());
    }

    @Test
    public void testOnBothInSequence() {
        testOnMatsFactoryX();
        testOnMatsFactoryY();
    }

}
