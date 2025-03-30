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

package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the Publish/Subscribe functionality: Sets up <i>two</i> instances of a SubscriptionTerminator to the same
 * endpointId, and then an initiator publishes a message to that endpointId. Due to the Pub/Sub nature, both of the
 * SubscriptionTerminators will get the message.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 *                     [Initiator]  - init publish
 * [SubscriptionTerminator_1][SubscriptionTerminator_2] <i>(both receives the message)</i>
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimplePublishSubscribe {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final MatsTestLatch matsTestLatch = new MatsTestLatch();
    private static final MatsTestLatch matsTestLatch2 = new MatsTestLatch();

    @BeforeClass
    public static void setupTerminator() {
        /*
         * :: Register TWO subscriptionTerminators to the same endpoint, to ensure that such a terminator works as
         * intended.
         *
         * NOTE: Due to a MatsFactory denying two registered endpoints with the same EndpointId, we need to trick this a
         * bit two make it happen: Create two MatsFactories with the same JMS ConnectionFactory.
         */

        // Creating TWO extra MatsFactories. These will be lifecycled by the Rule_Mats.
        MatsFactory firstMatsFactory = MATS.createMatsFactory();
        MatsFactory secondMatsFactory = MATS.createMatsFactory();

        MatsEndpoint<Void, StateTO> sub1 = firstMatsFactory.subscriptionTerminator(TERMINATOR,
                StateTO.class, DataTO.class, (context, sto, dto) -> {
                    log.debug("SUBSCRIPTION TERMINATOR 1 MatsTrace:\n" + context.toString());
                    matsTestLatch.resolve(sto, dto);
                });
        MatsEndpoint<Void, StateTO> sub2 = secondMatsFactory.subscriptionTerminator(TERMINATOR,
                StateTO.class, DataTO.class, (context, sto, dto) -> {
                    log.debug("SUBSCRIPTION TERMINATOR 2 MatsTrace:\n" + context.toString());
                    matsTestLatch2.resolve(sto, dto);
                });

        // Ensure that the SubscriptionTerminators have started their receive-loops before starting tests.
        sub1.waitForReceiving(10_000);
        sub2.waitForReceiving(10_000);
    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);

        // Initiate on the standard MatsFactory of Rule_Mats.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .publish(dto, sto));

        // Wait synchronously for both terminators to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());

        Result<StateTO, DataTO> result2 = matsTestLatch2.waitForResult();
        Assert.assertEquals(dto, result2.getData());
        Assert.assertEquals(sto, result2.getState());
    }
}
