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

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

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
import io.mats3.spring.EnableMats;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.spring.test.MatsTestProfile;
import io.mats3.spring.test.TestSpringMatsFactoryProvider;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestHelp;

/**
 * This sets up a small Mats infrastructure WITHOUT employing the {@link MatsTestContext @MatsTestContext} nor the
 * {@link EnableMats @EnableMats} annotation, but still marking with the {@link MatsTestProfile} annotation. This is
 * thus a pretty manual setup of a Mats test, but where the mats infrastructure still knows that it is a test.
 * <p />
 * An additional reason is to see (visually, in logs) that the .close() method on MatsFactory interface kicks in by
 * Spring as a destroy method - which is employed by the wrapped inside {@link TestSpringMatsFactoryProvider} for taking
 * down ActiveMQ.
 */
@RunWith(SpringRunner.class)
@MatsTestProfile
public class Test_1_NoMatsTestContext_NoEnableMats_WithMatsTestProfile {

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @Configuration
    static class TestConfiguration {
        @Bean
        MatsFactory manualMatsFactory() {
            return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(MatsSerializerJson.create());
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
