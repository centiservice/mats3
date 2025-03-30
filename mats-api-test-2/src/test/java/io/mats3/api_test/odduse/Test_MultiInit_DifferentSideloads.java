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

package io.mats3.api_test.odduse;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that sideloads (attached strings and bytes) works as expected in face of multiple sent messages, both from
 * init and stage.
 *
 * @author Endre Stølsvik 2022-05-11 08:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class  Test_MultiInit_DifferentSideloads {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR1 = MatsTestHelp.endpoint("Term1");
    private static final String TERMINATOR2 = MatsTestHelp.endpoint("Term2");

    private static final String ENDPOINT_SENDING_MSGS = MatsTestHelp.endpoint("ServiceSendingMsgs");

    private static final MatsTestLatch _matsTestLatch1 = new MatsTestLatch();
    private static final MatsTestLatch _matsTestLatch2 = new MatsTestLatch();
    private static final MatsTestLatch _serviceTestLatch2 = new MatsTestLatch();

    private static volatile byte[] __byteSideload_1;
    private static volatile String __stringSideload_1;
    private static volatile byte[] __byteSideload_2;
    private static volatile String __stringSideload_2;

    @BeforeClass
    public static void setupReceiverTerminators() {
        MATS.getMatsFactory().terminator(TERMINATOR1, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    __byteSideload_1 = context.getBytes("bytes");
                    __stringSideload_1 = context.getString("string");
                    _matsTestLatch1.resolve(context, sto, dto);
                });
        MATS.getMatsFactory().terminator(TERMINATOR2, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    __byteSideload_2 = context.getBytes("bytes");
                    __stringSideload_2 = context.getString("string");
                    _matsTestLatch2.resolve(context, sto, dto);
                });
    }

    @Test
    public void two_messages_from_init() {
        // :: ARRANGE
        DataTO dto1 = new DataTO(42, "TheAnswer");
        DataTO dto2 = new DataTO(84, "DoubleTheAnswer");

        // :: ACT
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("two_messages_from_init_1"))
                            .to(TERMINATOR1)
                            .addBytes("bytes", new byte[] { 1, 3, 3, 7 })
                            .addString("string", "Test1")
                            .send(dto1);

                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("two_messages_from_init_2"))
                            .to(TERMINATOR2)
                            .addBytes("bytes", new byte[] { 3, 14 })
                            .addString("string", "Test2")
                            .send(dto2);
                });

        // Wait synchronously for both flows!
        Result<StateTO, DataTO> result1 = _matsTestLatch1.waitForResult();
        Result<StateTO, DataTO> result2 = _matsTestLatch2.waitForResult();

        // :: ASSERT
        // Standard fare
        Assert.assertEquals(dto1, result1.getData());
        Assert.assertEquals(dto2, result2.getData());

        // Now check the sideloads
        Assert.assertArrayEquals(new byte[] { 1, 3, 3, 7 }, __byteSideload_1);
        Assert.assertEquals("Test1", __stringSideload_1);
        Assert.assertArrayEquals(new byte[] { 1, 3, 3, 7 }, result1.getContext().getBytes("bytes"));
        Assert.assertEquals("Test1", result1.getContext().getString("string"));

        Assert.assertArrayEquals(new byte[] { 3, 14 }, __byteSideload_2);
        Assert.assertEquals("Test2", __stringSideload_2);
        Assert.assertArrayEquals(new byte[] { 3, 14 }, result2.getContext().getBytes("bytes"));
        Assert.assertEquals("Test2", result2.getContext().getString("string"));
    }

    @BeforeClass
    public static void setupServiceWhichInitiatesTwoMessages() {
        MATS.getMatsFactory().terminator(ENDPOINT_SENDING_MSGS, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    context.initiate((init) -> {
                        init.traceId(MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("test_terminator1"))
                                .to(TERMINATOR1)
                                .addBytes("bytes", new byte[] { 1, 2, 3, 4 })
                                .addString("string", "String1")
                                .send(null);

                        init.traceId(MatsTestHelp.traceId())
                                .from(MatsTestHelp.from("test_terminator2"))
                                .to(TERMINATOR2)
                                .addBytes("bytes", new byte[] { 6, 28 })
                                .addString("string", "String2")
                                .send(null);
                    });

                    _serviceTestLatch2.resolve(context, sto, dto);
                });
    }

    @Test
    public void two_messages_from_stage() {
        // :: ARRANGE & ACT
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("two_messages_from_stage"))
                            .to(ENDPOINT_SENDING_MSGS)
                            .send(null);
                });

        // Wait synchronously for ENDPOINT_SENDING_MSGS to having processed and sent its two messages
        _serviceTestLatch2.waitForResult();

        // Wait synchronously for both flows!
        Result<StateTO, DataTO> result1 = _matsTestLatch1.waitForResult();
        Result<StateTO, DataTO> result2 = _matsTestLatch2.waitForResult();

        // :: ASSERT
        // Sideloads
        Assert.assertArrayEquals(new byte[] { 1, 2, 3, 4 }, __byteSideload_1);
        Assert.assertEquals("String1", __stringSideload_1);
        Assert.assertArrayEquals(new byte[] { 1, 2, 3, 4 }, result1.getContext().getBytes("bytes"));
        Assert.assertEquals("String1", result1.getContext().getString("string"));

        Assert.assertArrayEquals(new byte[] { 6, 28 }, __byteSideload_2);
        Assert.assertEquals("String2", __stringSideload_2);
        Assert.assertArrayEquals(new byte[] { 6, 28 }, result2.getContext().getBytes("bytes"));
        Assert.assertEquals("String2", result2.getContext().getString("string"));
    }
}
