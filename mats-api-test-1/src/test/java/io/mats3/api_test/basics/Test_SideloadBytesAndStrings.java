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
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests the send-along bytes and Strings sideloads with the message - close to copy of the {@link Test_MutilStageNext}
 * except testing the properties too. Test initiation, next, and reply.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]             - init request (adds sideloads)
 *     [Service S0 - init] - next    (retrieves sideloads, modifies them and adds to next)
 *     [Service S1 - last] - reply   (retrieves sideloads, modifies them and adds to next)
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SideloadBytesAndStrings {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupMultiStageService() {
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;

            Assert.assertNotNull(context.getSystemMessageId());

            byte[] bytes = context.getBytes("bytes");
            String string = context.getString("string");
            bytes[5] = (byte) (bytes[5] * 2);
            context.addBytes("bytes", bytes);
            context.addString("string", string + ":InitialStage");

            context.next(new DataTO(dto.number * 2, dto.string + ":InitialStage"));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);

            Assert.assertNotNull(context.getSystemMessageId());

            byte[] bytes = context.getBytes("bytes");
            String string = context.getString("string");
            bytes[5] = (byte) (bytes[5] * 3);
            context.addBytes("bytes", bytes);
            context.addString("string", string + ":ReplyStage");

            return new DataTO(dto.number * 3, dto.string + ":ReplyStage");
        });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _bytes = context.getBytes("bytes");
                    _string = context.getString("string");

                    Assert.assertNotNull(context.getSystemMessageId());

                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    private static byte[] _bytes;
    private static String _string;

    @Test
    public void doTest() {
        StateTO sto = new StateTO(420, 420.024);
        DataTO dto = new DataTO(42, "TheAnswer");
        byte[] bytes = new byte[] { 0, 1, -1, 127, -128, 11 };
        String string = "TestString";
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, sto)
                        .addBytes("bytes", bytes)
                        .addString("string", string)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3, dto.string + ":InitialStage" + ":ReplyStage"),
                result.getData());

        bytes[5] = (byte) (bytes[5] * 2 * 3);
        Assert.assertArrayEquals(bytes, _bytes);
        Assert.assertEquals(string + ":InitialStage" + ":ReplyStage", _string);
    }
}
