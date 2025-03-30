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

package io.mats3.util.futurizer;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Tests both attaching of bytes (and then getting them from the Reply object), and also the {@link InitiateLambda}
 * interface for {@link MatsFuturizer}, where it is made available via the method
 * {@link MatsFuturizer#futurizeGeneric(String, String, String, int, TimeUnit, Class, Object, InitiateLambda)}
 *
 * @author Endre Stølsvik, 2020 - http://stolsvik.com/, endre@stolsvik.com
 * @author Kevin Mc Tiernan, 2020 - kmctiernan@gmail.com
 */
public class Test_MatsFuturizer_ContextPayloadsAndTraceProperties {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    private static final byte[] BYTE_ARRAY = new byte[1024];

    static {
        new Random(-3).nextBytes(BYTE_ARRAY);
    }

    private static final String ENDPOINT_MESSAGE_APPEND = ":appendedAtService";
    private static final String KEY_ATTACHED_STRING = "attachedString";
    private static final String KEY_ATTACHED_BYTES = "attachedBytes";
    private static final String KEY_TRACE_PROPERTY = "traceProperty";
    private static final String KEY_TRACE_PROPERTY_FROM_ENDPOINT = "tracePropertyFromService";

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, String.class, String.class, (context, incomingMessage) -> {
            // Pass the attached string and bytes back to the invoker.
            context.addString(KEY_ATTACHED_STRING, context.getString(KEY_ATTACHED_STRING) + ":xyz");
            context.addBytes(KEY_ATTACHED_BYTES, context.getBytes(KEY_ATTACHED_BYTES));

            // Add a trace property
            context.setTraceProperty(KEY_TRACE_PROPERTY_FROM_ENDPOINT, new TestDto("XYZ", Math.E));
            return incomingMessage + ENDPOINT_MESSAGE_APPEND;
        });
    }

    @Test
    public void futureGet() throws InterruptedException, ExecutionException, TimeoutException {
        String traceId = UUID.randomUUID().toString();
        String request = UUID.randomUUID().toString();

        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        CompletableFuture<Reply<String>> future = futurizer.futurize(
                traceId, "futureGet", ENDPOINT, 1000, TimeUnit.MILLISECONDS, String.class, request,
                msg -> {
                    msg.addString(KEY_ATTACHED_STRING, "attached_String");
                    msg.addBytes(KEY_ATTACHED_BYTES, BYTE_ARRAY);
                    msg.setTraceProperty(KEY_TRACE_PROPERTY, new TestDto("DTO", Math.PI));
                });
        Reply<String> reply = future.get(1, TimeUnit.SECONDS);

        // Assert that we got the expected reply.
        Assert.assertEquals(request + ENDPOINT_MESSAGE_APPEND, reply.get());

        // :: Assert that the attached String and Byte Array, and 2 x Trace Properties, are present on the context.
        DetachedProcessContext context = reply.getContext();
        Assert.assertEquals("attached_String:xyz", context.getString(KEY_ATTACHED_STRING));
        Assert.assertArrayEquals(BYTE_ARRAY, context.getBytes(KEY_ATTACHED_BYTES));
        Assert.assertEquals(new TestDto("DTO", Math.PI),
                context.getTraceProperty(KEY_TRACE_PROPERTY, TestDto.class));
        Assert.assertEquals(new TestDto("XYZ", Math.E),
                context.getTraceProperty(KEY_TRACE_PROPERTY_FROM_ENDPOINT, TestDto.class));
    }

    private static class TestDto {
        String string;
        double number;

        public TestDto() {
        }

        TestDto(String string, double number) {
            this.string = string;
            this.number = number;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TestDto)) {
                return false;
            }
            return Double.compare(((TestDto) o).number, number) == 0
                    && Objects.equals(((TestDto) o).string, string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(string, number);
        }
    }
}
