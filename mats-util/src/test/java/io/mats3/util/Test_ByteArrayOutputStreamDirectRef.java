package io.mats3.util;

import io.mats3.util.DeflateTools.ByteArrayOutputStreamDirectRef;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

/**
 * Tests assumptions behind {@link ByteArrayOutputStreamDirectRef}: If we correctly size the BAOS upon creation compared
 * to the size of the data written to it, we should be able to get the internal byte array directly from the BAOS
 * without copying.
 */
public class Test_ByteArrayOutputStreamDirectRef {

    private static final byte[] _data = new byte[1024 * 1024 + 53789];
    static {
        // fill with random bytes
        for (int i = 0; i < _data.length; i++) {
            _data[i] = (byte) (Math.random() * 256);
        }
    }

    @Test
    public void simpleOneShotWrite() throws IOException {
        // Properly size wrt. target data to be written
        ByteArrayOutputStreamDirectRef baos = new ByteArrayOutputStreamDirectRef(_data.length);

        // Write the data in one go
        baos.write(_data);
        byte[] result = baos.toByteArray();

        Assert.assertArrayEquals(_data, result);

        // Assert the assumption that the internal buffer currently is the same as the result, i.e. the BAOS didn't
        // copy the data to a new array since it was exactly the right size.
        Assert.assertSame(result, baos.getInternalBuffer());
    }

    @Test
    public void writeInMultipleRandomWrites() throws IOException {
        // Properly size wrt. target data to be written
        ByteArrayOutputStreamDirectRef baos = new ByteArrayOutputStreamDirectRef(_data.length);

        // Write the data in random chunks
        int pos = 0;
        while (pos < _data.length) {
            int chunkSize = (int) (Math.random() * 2048);
            if (pos + chunkSize > _data.length) {
                chunkSize = _data.length - pos;
            }
            baos.write(_data, pos, chunkSize);
            pos += chunkSize;
        }

        byte[] result = baos.toByteArray();

        Assert.assertArrayEquals(_data, result);

        // Assert the assumption that the internal buffer currently is the same as the result, i.e. the BAOS didn't
        // copy the data to a new array since it was exactly the right size.
        Assert.assertSame(result, baos.getInternalBuffer());
    }

    @Test
    public void writeInMultipleRandomWrites_alternative() throws IOException {
        // Properly size wrt. target data to be written
        ByteArrayOutputStreamDirectRef baos = new ByteArrayOutputStreamDirectRef(_data.length);

        // Write the data in random chunks
        int pos = 0;
        while (pos < _data.length) {
            int chunkSize = (int) (Math.random() * 512);
            if (pos + chunkSize > _data.length) {
                chunkSize = _data.length - pos;
            }

            // Randomly choose whether to write the chunk in a copy, or as single bytes
            if (Math.random() < 0.5) {
                // Write the chunk in a copy
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(_data, pos, chunk, 0, chunkSize);
                baos.write(chunk);
            }
            else {
                // Write the chunk as single bytes
                for (int i = 0; i < chunkSize; i++) {
                    baos.write(_data[pos + i]);
                }
            }

            pos += chunkSize;
        }

        byte[] result = baos.toByteArray();

        Assert.assertArrayEquals(_data, result);

        // Assert the assumption that the internal buffer currently is the same as the result, i.e. the BAOS didn't
        // copy the data to a new array since it was exactly the right size.
        Assert.assertSame(result, baos.getInternalBuffer());
    }

}
