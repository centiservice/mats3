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

    @Test
    public void removeBatchId() {
        String traceId = TraceId.create("Order", null, "calcShipping")
                .add("cid", "123456").add("oid", "654321")
                .batchId("abcde")
                .batchId(null).toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order:calcShipping[cid=123456][oid=654321]", nonRandom);
    }

    @Test
    public void updateKeyValue() {
        String traceId = TraceId.create("Order", null, "calcShipping")
                .add("cid", "123456")
                // Change the cid key
                .add("cid", "654321")
                .toString();
        String nonRandom = traceId.substring(0, traceId.length() - 6);
        Assert.assertEquals("Order:calcShipping[cid=654321]", nonRandom);
    }

    // Common TraceId used in all charSequence API tests.
    private final TraceId charSequnceTestTraceId = TraceId.create("Order", null, "calcShipping")
            .add("cid", "123456").add("oid", "654321")
            .batchId("abcde");

    @Test
    public void charSequnceCharAt() {
        String traceIdText = charSequnceTestTraceId.toString();
        for (int i = 0; i < traceIdText.length(); i++) {
            Assert.assertEquals(traceIdText.charAt(i), charSequnceTestTraceId.charAt(i));
        }
    }

    @Test
    public void charSequnceCharAtBoundsCheck() {
        Assert.assertThrows(IndexOutOfBoundsException.class,
                () -> charSequnceTestTraceId.charAt(1000));
        Assert.assertThrows(IndexOutOfBoundsException.class,
                () -> charSequnceTestTraceId.charAt(charSequnceTestTraceId.length()));
    }

    @Test
    public void charSequnceLength() {
        String traceIdText = charSequnceTestTraceId.toString();
        Assert.assertEquals(traceIdText.length(), charSequnceTestTraceId.length());
    }

    @Test
    public void charSequenceLengthIncludesAddedKeyValue() {
        int originalLength = charSequnceTestTraceId.length();
        charSequnceTestTraceId.add("pid", "123456");
        int lengthAfterAdd = charSequnceTestTraceId.length();
        Assert.assertEquals(originalLength + 12, lengthAfterAdd);
    }
    @Test
    public void charSequenceLenghtIncludesBatchId() {
        int originalLength = charSequnceTestTraceId.length();
        charSequnceTestTraceId.batchId(123456L); // 1 additional character
        int lengthAfterAdd = charSequnceTestTraceId.length();
        Assert.assertEquals(originalLength + 1, lengthAfterAdd);

        charSequnceTestTraceId.batchId(null); // Clear BatchId
        int lengthAfterRemove = charSequnceTestTraceId.length();
        // [batch= + ] is 8 characters, test batchId is 5 characters. Removing batchId, removes 13 characters.
        Assert.assertEquals(originalLength - 13, lengthAfterRemove);
    }

    @Test
    public void charSequenceSubSequence() {
        String traceIdText = charSequnceTestTraceId.toString();
        for (int i = 0; i < traceIdText.length() - 5; i++) {
            for (int j = i + 5; j < traceIdText.length(); j++) {
                Assert.assertEquals(traceIdText.subSequence(i, j), charSequnceTestTraceId.subSequence(i, j));
            }
        }
    }

    @Test
    public void charSequenceSubSequenceFullIdentity() {
        CharSequence subSequenceFull = charSequnceTestTraceId.subSequence(0, charSequnceTestTraceId.length());
        Assert.assertSame(charSequnceTestTraceId, subSequenceFull);
    }

    @Test
    public void charSequenceBoundsCheck() {
        Assert.assertThrows(IndexOutOfBoundsException.class, () -> charSequnceTestTraceId.subSequence(1000, 1001));
        Assert.assertThrows(IndexOutOfBoundsException.class, () -> charSequnceTestTraceId.subSequence(0, 1001));
        Assert.assertThrows(IllegalArgumentException.class, () -> charSequnceTestTraceId.subSequence(10, 0));
    }
}
