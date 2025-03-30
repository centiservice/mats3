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

package io.mats3.util.compression;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.junit.Assert;
import org.junit.Test;

public class Test_DeflaterOutputStreamWithStats {
    static final byte[] _dataUncompressed = new byte[1024 * 1024 - 18765];
    static final byte[] _dataCompressed;
    static {
        // fill with random bytes
        for (int i = 0; i < _dataUncompressed.length; i++) {
            _dataUncompressed[i] = (byte) (Math.random() * 256);
        }

        // Make a few regions with constant values
        for (int i = 0; i < 10; i++) {
            int start = (int) (Math.random() * _dataUncompressed.length);
            // Random length, but at least 1, max 10k - taking into consideration the end of the array
            int end = Math.min(_dataUncompressed.length, start + 1 + (int) (Math.random() * 10240));
            byte value = (byte) (Math.random() * 256);
            for (int j = start; j < end; j++) {
                _dataUncompressed[j] = value;
            }
        }

        // Compress the data to be used in the tests using standard Java
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream out = new DeflaterOutputStream(baos, new Deflater(DeflaterOutputStreamWithStats
                .DEFAULT_COMPRESSION_LEVEL));
        try {
            out.write(_dataUncompressed);
            out.close();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        _dataCompressed = baos.toByteArray();
    }

    @Test
    public void simpleUseBaos() throws Exception {
        // :: Use the new variant where we use the DeflaterOutputStreamWithStats
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStreamWithStats out = new DeflaterOutputStreamWithStats(baos);
        out.write(_dataUncompressed);
        out.close();

        byte[] compressed = baos.toByteArray();
        Assert.assertArrayEquals(_dataCompressed, compressed);

        System.out.println("S Original size:   " + _dataUncompressed.length);
        System.out.println("S Compressed size: " + compressed.length);
        // differences
        System.out.println("S Difference: .... " + (_dataUncompressed.length - compressed.length) + " bytes");
        System.out.println("S Compressed size: " + (100.0 * compressed.length / _dataUncompressed.length) + "%");
        System.out.println("S Deflate time:    " + (out.getDeflateTimeNanos() / 1_000_000d) + " ms");
        System.out.println("----------------------------------");

        // Assert the stats
        Assert.assertEquals(_dataUncompressed.length, out.getUncompressedBytesInput());
        Assert.assertEquals(_dataCompressed.length, out.getCompressedBytesOutput());
        Assert.assertTrue("Deflate time should be > 0", out.getDeflateTimeNanos() > 0);
        Assert.assertTrue("DeflateAndWrite time should be > 0", out.getDeflateAndWriteTimeNanos() > 0);
        Assert.assertTrue("DeflateAndWrite time should be >= Deflate time",
                out.getDeflateAndWriteTimeNanos() >= out.getDeflateTimeNanos());
    }

    @Test
    public void multipleThreads() throws Exception {
        int count = 50;

        CountDownLatch latch_StartThreads = new CountDownLatch(1);
        CountDownLatch latch_ThreadsDone = new CountDownLatch(count);

        // :: Do the compression in multiple threads
        Thread[] threads = new Thread[count];
        Throwable[] exceptions = new Throwable[count];
        for (int i = 0; i < count; i++) {
            final int threadNo = i;
            threads[threadNo] = new Thread(() -> {
                try {
                    latch_StartThreads.await();
                    // :: Use the new variant where we use the DeflaterOutputStreamWithStats
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DeflaterOutputStreamWithStats out = new DeflaterOutputStreamWithStats(baos);
                    out.write(_dataUncompressed);
                    out.close();
                    byte[] compressed = baos.toByteArray();
                    // Assert that the two compressed arrays are equal
                    Assert.assertArrayEquals(compressed, _dataCompressed);
                    // Assert the stats
                    Assert.assertEquals(_dataUncompressed.length, out.getUncompressedBytesInput());
                    Assert.assertEquals(_dataCompressed.length, out.getCompressedBytesOutput());
                    Assert.assertTrue(out.getDeflateTimeNanos() > 0);
                }
                catch (Throwable t) {
                    exceptions[threadNo] = t;
                }
                finally {
                    latch_ThreadsDone.countDown();
                }
            });
            threads[i].start();
        }
        latch_StartThreads.countDown();
        latch_ThreadsDone.await();

        // Check if any of the threads threw an exception
        for (int i = 0; i < count; i++) {
            if (exceptions[i] != null) {
                throw new AssertionError("Thread " + i + " threw exception", exceptions[i]);
            }
        }
    }

}
