package io.mats3.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author Endre Stølsvik 2022-02-23 13:52 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_TraceId {

    @Test
    public void exampleJavaDoc() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321).toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void exampleJavaDoc_withBatch() {
        String traceId = TraceId.create("Order", "placeOrder", "calcShipping")
                .add("cid", 123456).add("oid", 654321)
                .batchId("abcde").toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order.placeOrder:calcShipping[batch=abcde][cid=123456][oid=654321]", nonRandom);
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
