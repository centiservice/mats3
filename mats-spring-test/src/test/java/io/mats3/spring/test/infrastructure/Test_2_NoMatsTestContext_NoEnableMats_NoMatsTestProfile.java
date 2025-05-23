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

package io.mats3.spring.test.infrastructure;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.test.MatsTestProfile;
import io.mats3.spring.test.TestSpringMatsFactoryProvider;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;

/**
 * Identical to {@link Test_1_NoMatsTestContext_NoEnableMats_WithMatsTestProfile}, only here not even marking with the
 * {@link MatsTestProfile} annotation. This is thus a very manual setup of a Mats test, where the mats infrastructure
 * does not know that this is a test. However, since we make the MatsFactory using
 * {@link TestSpringMatsFactoryProvider}, we will still get an "embedded ActiveMQ" solution, which will be properly
 * lifecycled and also the concurrency is then directly set (default 1, here set to 3).
 */
@RunWith(SpringRunner.class)
public class Test_2_NoMatsTestContext_NoEnableMats_NoMatsTestProfile {

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @Configuration
    static class TestConfiguration {
        @Bean
        MatsFactory manualMatsFactory() {
            return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(3, MatsSerializerJson.create());
        }

        @Bean
        MatsInitiator manualMatsInitator(MatsFactory matsFactory) {
            return matsFactory.getDefaultInitiator();
        }

        @Bean
        MatsTestBrokerInterface manualMatsTestMqInterface() {
            // Notice how this "magically" is populated with necessary features by SpringJmsMatsFactoryWrapper,
            // which TestSpringMatsFactoryProvider wraps the MatsFactory with.
            return MatsTestBrokerInterface.createForLaterPopulation();
        }
    }

    @Configuration
    static class SetupEndpoint {
        @Inject
        private MatsFactory _matsFactory;

        @PostConstruct
        void setupSingleEndpoint() {
            _matsFactory.terminator(TERMINATOR, String.class, String.class, (ctx, state, msg) -> {
                throw new MatsRefuseMessageException("Throw this on the DLQ!");
            });
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestBrokerInterface _matsTestBrokerInterface;

    @Test
    public void doTest() {
        Assert.assertNotNull(_matsInitiator);
        Assert.assertNotNull(_matsTestBrokerInterface);

        String msg = "Incoming 1, 2, 3";
        String state = "State 1, 2, 3";

        String from = MatsTestHelp.from("test");
        String traceId = MatsTestHelp.traceId();

        MessageReference[] reference = new MessageReference[1];
        _matsInitiator.initiateUnchecked(init -> {
            reference[0] = init
                    .traceId(traceId)
                    .from(from)
                    .to(TERMINATOR)
                    .send(msg, state);
        });

        MatsMessageRepresentation dlqMessage = _matsTestBrokerInterface.getDlqMessage(TERMINATOR);

        Assert.assertEquals(msg, dlqMessage.getIncomingMessage(String.class));
        Assert.assertEquals(state, dlqMessage.getIncomingState(String.class));
        Assert.assertEquals(from, dlqMessage.getFrom());
        Assert.assertEquals(TERMINATOR, dlqMessage.getTo());
        Assert.assertEquals(reference[0].getMatsMessageId(), dlqMessage.getMatsMessageId());
    }
}
