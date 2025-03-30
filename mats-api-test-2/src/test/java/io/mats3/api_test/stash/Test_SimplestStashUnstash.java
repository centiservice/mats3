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

package io.mats3.api_test.stash;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsInitiator.MatsBackendException;
import io.mats3.MatsInitiator.MatsMessageSendException;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Simplest example of stash/unstash: "Single-stage" that employs stash.
 *
 * @author Endre Stølsvik - 2018-10-23 - http://endre.stolsvik.com
 */
public class Test_SimplestStashUnstash {

    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static byte[] _stash;
    private static CountDownLatch _stashLatch = new CountDownLatch(1);

    @BeforeClass
    public static void setupService() {
        MatsEndpoint<DataTO, StateTO> staged = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class);
        // Cannot employ a single-stage, since that requires a reply (by returning something, even null).
        // Thus, employing multistage, with only one stage, where we do not invoke context.reply(..)
        staged.stage(DataTO.class, ((context, state, incomingDto) -> {
            _stash = context.stash();
            _stashLatch.countDown();
        }));
        staged.finishSetup();
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void doTest() throws InterruptedException, MatsMessageSendException, MatsBackendException {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for stash to appear
        _stashLatch.await(1, TimeUnit.SECONDS);

        // Unstash!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(_stash,
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    context.reply(new DataTO(dto.number * 2, dto.string + ":FromService"));
                }));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }
}