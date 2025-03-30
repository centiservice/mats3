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

package io.mats3.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author Endre Stølsvik 2022-02-23 13:52 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_TraceId {

    @Test
    public void exampleFromJavaDoc() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321).toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void withBatch() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321)
                .batchId("abcde").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[batch=abcde][cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void preventPrepend() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321)
                .preventPrepend().toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("!Order.placeOrder:calcShipping[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void modifyReason() {
        TraceId traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321);
        String nonRandom = traceId.subSequence(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[cid=123456][oid=654321]", nonRandom);

        traceId.reason("anotherReason");
        nonRandom = traceId.subSequence(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:anotherReason[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void modifyKey() {
        TraceId traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321);
        String nonRandom = traceId.subSequence(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[cid=123456][oid=654321]", nonRandom);

        traceId.add("cid", 654321);
        nonRandom = traceId.subSequence(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[cid=654321][oid=654321]", nonRandom);
    }

    @Test
    public void noKeys() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping_", nonRandom);
    }

    @Test
    public void noKeys_noReason() {
        String traceId = TraceId.create("Order", "placeOrder").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder_", nonRandom);
    }

    @Test
    public void noReason() {
        String traceId = TraceId.create("Order", "placeOrder")
                .add("cid", 123456).add("oid", 654321).toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void noInit() {
        String traceId = TraceId.create("Order", null, "calcShipping")
                .add("cid", 123456).add("oid", 654321).toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order:calcShipping[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void addStrings() {
        String traceId = TraceId.create("Order", null, "calcShipping")
                .add("cid", "123456").add("oid", "654321")
                .batchId("abcde").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order:calcShipping[batch=abcde][cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void concat() {
        String traceId = TraceId.concat("Prefix", "Order", "placeOrder", "calcShipping")
                .add("cid", "123456").add("oid", "654321")
                .batchId("abcde").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Prefix+Order.placeOrder:calcShipping[batch=abcde][cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void fork() {
        String traceId = TraceId.fork("Prefix", "Order", "placeOrder", "calcShipping")
                .add("cid", "123456").add("oid", "654321")
                .batchId("abcde").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Prefix|Order.placeOrder:calcShipping[batch=abcde][cid=123456][oid=654321]", nonRandom);
    }

}
