package io.mats3.util;

import java.io.IOException;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.util.DeflateTools.ByteArrayDeflaterOutputStreamWithStats;

/**
 * Tests the {@link ByteArrayDeflaterOutputStreamWithStats} with a set of scenarios.
 */
public class Test_DeflateTools_ByteArrayDeflaterOutputStreamWithStats {

    private static final byte[] _dataUncompressed = Test_DeflateTools_DeflaterOutputStreamWithStats._dataUncompressed;
    private static final byte[] _dataCompressed = Test_DeflateTools_DeflaterOutputStreamWithStats._dataCompressed;

    private final Consumer<ByteArrayDeflaterOutputStreamWithStats> full = out -> out.write(_dataUncompressed);

    private final Consumer<ByteArrayDeflaterOutputStreamWithStats> single = out -> {
        for (byte b : _dataUncompressed) {
            out.write(b);
        }
    };

    private final Consumer<ByteArrayDeflaterOutputStreamWithStats> random = out -> {
        int pos = 0;
        while (pos < _dataUncompressed.length) {
            int len = (int) (Math.random() * 1000);
            len = Math.min(len, _dataUncompressed.length - pos);
            out.write(_dataUncompressed, pos, len);
            pos += len;
        }
    };

    @Test
    public void simpleUse_full() {
        _simpleUse(full);
    }

    @Test
    public void simpleUse_singleBytes() {
        _simpleUse(single);
    }

    @Test
    public void simpleUse_randomSizedWrites() {
        _simpleUse(random);
    }

    @Test
    public void exactSize_full() {
        _exactSize(full);
    }

    @Test
    public void exactSize_singleBytes() {
        _exactSize(single);
    }

    @Test
    public void exactSize_randomSizedWrites() {
        _exactSize(random);
    }

    @Test
    public void tooBigSize_full() throws IOException {
        _tooBigSize(full);
    }

    @Test
    public void tooBigSize_singleBytes() throws IOException {
        _tooBigSize(single);
    }

    @Test
    public void tooBigSize_randomSizedWrites() throws IOException {
        _tooBigSize(random);
    }

    public void _simpleUse(Consumer<ByteArrayDeflaterOutputStreamWithStats> r) {
        // :: Use the new variant where we use the ByteArrayDeflaterOutStreamWithStats
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
        r.accept(out);
        out.close();

        byte[] compressed = out.toByteArray();

        commonAsserts(compressed, out);
    }

    public void _exactSize(Consumer<ByteArrayDeflaterOutputStreamWithStats> r) {
        // :: Use the new variant where we use the ByteArrayDeflaterOutStreamWithStats
        byte[] target = new byte[_dataCompressed.length];
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats(target, 0);
        r.accept(out);
        out.close();

        // Since we provided the exact size, the target array should be the same as the internal array
        Assert.assertArrayEquals(target, out.getUncroppedInternalArray());

        byte[] compressed = out.toByteArray();

        // .. and the compressed array should be the same as the target array
        Assert.assertSame(target, compressed);

        // Assert the compressed data
        commonAsserts(compressed, out);
    }

    public void _tooBigSize(Consumer<ByteArrayDeflaterOutputStreamWithStats> r) {
        // :: Use the new variant where we use the ByteArrayDeflaterOutStreamWithStats
        byte[] target = new byte[_dataCompressed.length + 1];
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats(target, 0);
        r.accept(out);
        out.close();

        // Since we provided a too big target, the target array should be the same as the internal array (it should
        // not have been neither cropped nor grown)
        Assert.assertArrayEquals(target, out.getUncroppedInternalArray());

        byte[] compressed = out.toByteArray();

        // .. but the compressed array should be a new array of exact size.
        Assert.assertNotSame(target, compressed);

        // Assert the compressed data
        commonAsserts(compressed, out);
    }

    private static void commonAsserts(byte[] compressed, ByteArrayDeflaterOutputStreamWithStats out) {
        // Assert the compressed data
        Assert.assertArrayEquals(_dataCompressed, compressed);

        // Assert the stats
        Assert.assertEquals(_dataUncompressed.length, out.getUncompressedBytesInput());
        Assert.assertEquals(_dataCompressed.length, out.getCompressedBytesOutput());
        Assert.assertTrue(out.getDeflateTimeNanos() > 0);
    }

    @Test
    public void compressTwiceIntoSameArray_ExactRoomForTwo_full() {
        _compressTwiceIntoSameArray_ExactRoomForTwo(full);
    }

    @Test
    public void compressTwiceIntoSameArray_ExactRoomForTwo_singleBytes() {
        _compressTwiceIntoSameArray_ExactRoomForTwo(single);
    }

    @Test
    public void compressTwiceIntoSameArray_ExactRoomForTwo_randomSizedWrites() {
        _compressTwiceIntoSameArray_ExactRoomForTwo(random);
    }

    public void _compressTwiceIntoSameArray_ExactRoomForTwo(Consumer<ByteArrayDeflaterOutputStreamWithStats> r) {
        byte[] target = new byte[_dataCompressed.length * 2];
        ByteArrayDeflaterOutputStreamWithStats out1 = new ByteArrayDeflaterOutputStreamWithStats(target, 0);
        random.accept(out1);
        out1.close();

        // Since it was too big, the target array should be the same as the internal array (no expansion)
        Assert.assertSame(target, out1.getUncroppedInternalArray());
        // Copy out the compressed data
        byte[] resultArrayCompressed = new byte[_dataCompressed.length];
        System.arraycopy(target, 0, resultArrayCompressed, 0, _dataCompressed.length);
        // Assert that the compressed data is the same as the target array
        Assert.assertArrayEquals(_dataCompressed, resultArrayCompressed);

        // The cropped array should NOT be the same as the target array (since it was too big)
        byte[] resultArray1 = out1.toByteArray();
        Assert.assertNotSame(target, resultArray1);
        Assert.assertArrayEquals(_dataCompressed, resultArray1);

        // Write it again
        ByteArrayDeflaterOutputStreamWithStats out2 = new ByteArrayDeflaterOutputStreamWithStats(target, out1
                .getCurrentPosition());
        r.accept(out2);
        out2.close();

        // Since it was exactly big enough for twice, the target array should still be the same as the internal array
        Assert.assertSame(target, out2.getUncroppedInternalArray());
        // .. and when getting the finished array, it should be the same as the target array (since it was exactly big
        // enough)
        byte[] resultArray2 = out2.toByteArray();
        Assert.assertSame(target, resultArray2);

        // We should now have two copies of the compressed data in the target array
        // Copy out the first
        byte[] resultArrayCopyA = new byte[_dataCompressed.length];
        System.arraycopy(resultArray2, 0, resultArrayCopyA, 0, _dataCompressed.length);
        // Copy out the second
        byte[] resultArrayCopyB = new byte[_dataCompressed.length];
        System.arraycopy(resultArray2, _dataCompressed.length, resultArrayCopyB, 0, _dataCompressed.length);
        // Assert that both pieces are the same as the compressed data
        Assert.assertArrayEquals(_dataCompressed, resultArrayCopyA);
        Assert.assertArrayEquals(_dataCompressed, resultArrayCopyB);
    }

    @Test
    public void compressTwiceIntoSameArray_OnlyRoomForOne_full() {
        _compressTwiceIntoSameArray_OnlyRoomForOne(full);
    }

    @Test
    public void compressTwiceIntoSameArray_OnlyRoomForOne_singleBytes() {
        _compressTwiceIntoSameArray_OnlyRoomForOne(single);
    }

    @Test
    public void compressTwiceIntoSameArray_OnlyRoomForOne_randomSizedWrites() {
        _compressTwiceIntoSameArray_OnlyRoomForOne(random);
    }

    public void _compressTwiceIntoSameArray_OnlyRoomForOne(Consumer<ByteArrayDeflaterOutputStreamWithStats> r) {
        byte[] target = new byte[_dataCompressed.length];
        ByteArrayDeflaterOutputStreamWithStats out1 = new ByteArrayDeflaterOutputStreamWithStats(target, 0);
        random.accept(out1);
        out1.close();

        // Since it was exactly big enough for one, the target array should still be the same as the internal array
        Assert.assertSame(target, out1.getUncroppedInternalArray());
        Assert.assertArrayEquals(_dataCompressed, target);

        // The cropped array should ALSO be the same as the target array (since it was exactly big enough)
        byte[] resultArray1 = out1.toByteArray();
        Assert.assertSame(target, resultArray1);

        // Write it again
        ByteArrayDeflaterOutputStreamWithStats out2 = new ByteArrayDeflaterOutputStreamWithStats(target, out1
                .getCurrentPosition());
        r.accept(out2);
        out2.close();

        // Since it was too small for twice, the target array should now NOT be the same as the internal array (since it
        // was expanded)
        Assert.assertNotSame(target, out2.getUncroppedInternalArray());
        // Getting the cropped array should NOT be the same
        byte[] resultArray2 = out2.toByteArray();
        Assert.assertNotSame(target, resultArray2);

        // We should now have two copies of the compressed data in the target array
        // Copy out the first
        byte[] resultArrayCopyA = new byte[_dataCompressed.length];
        System.arraycopy(resultArray2, 0, resultArrayCopyA, 0, _dataCompressed.length);
        // Copy out the second
        byte[] resultArrayCopyB = new byte[_dataCompressed.length];
        System.arraycopy(resultArray2, _dataCompressed.length, resultArrayCopyB, 0, _dataCompressed.length);
        // Assert that both pieces are the same as the compressed data
        Assert.assertArrayEquals(_dataCompressed, resultArrayCopyA);
        Assert.assertArrayEquals(_dataCompressed, resultArrayCopyB);
    }

}
