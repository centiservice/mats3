package io.mats3.util;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.util.DeflateTools.InflaterInputStreamWithStats;

public class Test_DeflateTools_InflaterInputStreamWithStats {
    private static final byte[] _dataUncompressed = Test_DeflateTools_DeflaterOutputStreamWithStats._dataUncompressed;
    private static final byte[] _dataCompressed = Test_DeflateTools_DeflaterOutputStreamWithStats._dataCompressed;

    @Test
    public void simpleOld() throws Exception {
        // :: Use the older variant where we do not use the InflaterInputStreamWithStats
        long nanos_Start = System.nanoTime();
        byte[] uncompressedOld = DeflateTools.decompress(_dataCompressed, 0, _dataCompressed.length,
                _dataUncompressed.length);
        double millis = (System.nanoTime() - nanos_Start) / 1_000_000d;
        System.out.println("Old-style Inflate time:    " + millis + " ms");
        Assert.assertArrayEquals(_dataUncompressed, uncompressedOld);
    }

    @Test
    public void simpleUseBaos() throws Exception {
        // :: Use the new variant where we use the InflaterInputStreamWithStats
        InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(new ByteArrayInputStream(_dataCompressed),
                1536);
        byte[] uncompressed = in.readAllBytes();
        in.close();
        System.out.println("Stream Baos Inflate time:  " + (in.getInflateTimeNanos() / 1_000_000d) + " ms");

        Assert.assertEquals(_dataUncompressed.length, uncompressed.length);
        Assert.assertArrayEquals(_dataUncompressed, uncompressed);

        // Assert the stats
        Assert.assertEquals(_dataCompressed.length, in.getCompressedBytesInput());
        Assert.assertEquals(_dataUncompressed.length, in.getUncompressedBytesOutput());
        Assert.assertTrue(in.getInflateTimeNanos() > 0);
    }

    @Test
    public void simpleUseArray() throws Exception {
        // :: Use the new variant where we use the InflaterInputStreamWithStats
        InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(_dataCompressed);
        byte[] uncompressed = in.readAllBytes();
        in.close();
        System.out.println("Stream Array Inflate time: " + (in.getInflateTimeNanos() / 1_000_000d) + " ms");

        Assert.assertEquals(_dataUncompressed.length, uncompressed.length);
        Assert.assertArrayEquals(_dataUncompressed, uncompressed);

        // Assert the stats
        Assert.assertEquals(_dataCompressed.length, in.getCompressedBytesInput());
        Assert.assertEquals(_dataUncompressed.length, in.getUncompressedBytesOutput());
        Assert.assertTrue(in.getInflateTimeNanos() > 0);
    }

    @Test
    public void multipleThreadsUseBaos() throws Exception {
        multipleThreadsUseBaosOrArray(true);
    }

    @Test
    public void multipleThreadsUseArray() throws Exception {
        multipleThreadsUseBaosOrArray(false);
    }

    @Test
    public void performanceTest() throws Exception {
        // Warmup
        for (int i = 0; i < 100; i++) {
            multipleThreadsUseBaos();
            multipleThreadsUseArray();
        }

        System.gc();
        System.gc();
        System.gc();

        double totalMillisBaos = 0;
        double totalMillisArray = 0;
        for (int i = 0; i < 1500; i++) {
            long nanos_Start = System.nanoTime();
            multipleThreadsUseBaos();
            double millisBaos = (System.nanoTime() - nanos_Start) / 1_000_000d;
            totalMillisBaos += millisBaos;
            System.out.println("Baos:  " + millisBaos + " ms");

            nanos_Start = System.nanoTime();
            multipleThreadsUseArray();
            double millisArray = (System.nanoTime() - nanos_Start) / 1_000_000d;
            totalMillisArray += millisArray;
            System.out.println("Array: " + millisArray + " ms");
            System.out.println("-------------------");
        }
        System.out.println("Total Baos Inflate time:  " + totalMillisBaos + " ms");
        System.out.println("Total Array Inflate time: " + totalMillisArray + " ms");
        // Percent difference
        System.out.println("Difference (/baos): " + (100.0 * (totalMillisBaos - totalMillisArray) / totalMillisBaos)
                + "%");

        System.out.println("Number of inflater reuses: " + DeflateTools.getInflaterReuses());
        System.out.println("Number of inflater pool empty: " + DeflateTools.getInflaterPoolEmpty());
    }

    public void multipleThreadsUseBaosOrArray(boolean useBaos) throws Exception {
        int count = 16;

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
                    // :: Use the new variant where we use the InflaterOutputStreamWithStats
                    InflaterInputStreamWithStats in = useBaos
                            ? new InflaterInputStreamWithStats(new ByteArrayInputStream(_dataCompressed), 1536)
                            : new InflaterInputStreamWithStats(_dataCompressed);
                    byte[] uncompressed = in.readAllBytes();
                    in.close();

                    // Assert that the two compressed arrays are equal
                    Assert.assertArrayEquals(_dataUncompressed, uncompressed);
                    // Assert the stats
                    Assert.assertEquals(_dataUncompressed.length, in.getUncompressedBytesOutput());
                    Assert.assertEquals(_dataCompressed.length, in.getCompressedBytesInput());
                    Assert.assertTrue(in.getInflateTimeNanos() > 0);
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

        // Check that we have a reasonable amount of Inflater instances in the pool.
        // It should basically be the maximum. However, upon return the Inflater instance to the pool, the evalation of
        // whether the pool is full is done right before the instance is returned. This is however not atomic with the
        // return, so due to races, we might conceivably be off by a few.
        String msg = "Pool size: " + DeflateTools.getInflaterPoolSize() + ", MAX_POOLED: " + DeflateTools.MAX_POOLED;
        Assert.assertTrue(msg, DeflateTools.getInflaterPoolSize() >= (DeflateTools.MAX_POOLED - 2));
        Assert.assertTrue(msg, DeflateTools.getInflaterPoolSize() <= (DeflateTools.MAX_POOLED + 4));
    }
}
