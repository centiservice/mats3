package io.mats3.util.compression;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;

import org.junit.Assert;
import org.junit.Test;

public class Test_InflaterInputStreamWithStats {
    private static final byte[] _dataUncompressed = Test_DeflaterOutputStreamWithStats._dataUncompressed;
    private static final byte[] _dataCompressed = Test_DeflaterOutputStreamWithStats._dataCompressed;

    @Test
    public void simpleUseBaos() throws Exception {
        // :: Use the new variant where we use the InflaterInputStreamWithStats
        InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(new ByteArrayInputStream(_dataCompressed),
                1536);
        byte[] uncompressed = in.readAllBytes();
        in.close();

        Assert.assertArrayEquals(_dataUncompressed, uncompressed);

        System.out.println("ReadTimeNanos: " + in.getReadTimeNanos());
        System.out.println("InflateTimeNanos: " + in.getInflateTimeNanos());
        System.out.println("ReadAndInflateTimeNanos: " + in.getReadAndInflateTimeNanos());
        assertStatsBaosVariant(in, uncompressed);
    }

    @Test
    public void simpleUseArray() throws Exception {
        // :: Use the new variant where we use the InflaterInputStreamWithStats
        InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(_dataCompressed);
        byte[] uncompressed = in.readAllBytes();
        in.close();

        Assert.assertArrayEquals(_dataUncompressed, uncompressed);

        System.out.println("ReadTimeNanos: " + in.getReadTimeNanos());
        System.out.println("InflateTimeNanos: " + in.getInflateTimeNanos());
        System.out.println("ReadAndInflateTimeNanos: " + in.getReadAndInflateTimeNanos());
        assertStatsArrayVariant(in, uncompressed);
    }

    private static void assertStatsBaosVariant(InflaterInputStreamWithStats in, byte[] uncompressed) {
        Assert.assertTrue("Read time should be > 0", in.getReadTimeNanos() > 0);
        Assert.assertTrue("Inflate time should be > 0", in.getInflateTimeNanos() > 0);
        assertStatsCommon(in, uncompressed);
    }

    private static void assertStatsArrayVariant(InflaterInputStreamWithStats in, byte[] uncompressed) {
        Assert.assertEquals("Read time should be == 0, not " + in.getReadTimeNanos(), 0, in.getReadTimeNanos());
        Assert.assertTrue("Inflate time should be > 0", in.getInflateTimeNanos() > 0);
        Assert.assertEquals("Read+Inflate time should be same as Inflate time",
                in.getReadAndInflateTimeNanos(), in.getInflateTimeNanos());
        assertStatsCommon(in, uncompressed);
    }

    private static void assertStatsCommon(InflaterInputStreamWithStats in, byte[] uncompressed) {
        Assert.assertEquals(_dataUncompressed.length, uncompressed.length);
        Assert.assertEquals(_dataCompressed.length, in.getCompressedBytesInput());
        Assert.assertEquals(_dataUncompressed.length, in.getUncompressedBytesOutput());
        Assert.assertTrue("Read+Inflate time should be > 0", in.getReadAndInflateTimeNanos() > 0);
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
    public void performanceTest_BAOS_vs_ByteArray() throws Exception {
        int warmpupIterations = 1; // Use 250 for a good warmup
        int performanceIterations = 5; // Use 1500 for a good performance test

        // Warmup
        for (int i = 0; i < warmpupIterations; i++) {
            multipleThreadsUseBaos();
            multipleThreadsUseArray();
        }

        System.gc();
        System.gc();
        System.gc();

        double totalMillisBaos = 0;
        double totalMillisArray = 0;
        for (int i = 0; i < performanceIterations; i++) {
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

                    if (useBaos) {
                        assertStatsBaosVariant(in, uncompressed);
                    }
                    else {
                        assertStatsArrayVariant(in, uncompressed);
                    }
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
