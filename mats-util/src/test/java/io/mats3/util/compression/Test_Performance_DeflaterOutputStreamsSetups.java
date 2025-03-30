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

import static io.mats3.util.Tools.fd2;
import static io.mats3.util.Tools.ft;
import static io.mats3.util.Tools.ms2;
import static io.mats3.util.Tools.ms4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.DeflaterOutputStream;

import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;

/**
 * Performance tests between a few different setups:
 * <ul>
 * <li>{@link DeflaterOutputStream} + BAOS + each time new Deflater
 * <li>{@link DeflaterOutputStream} + BAOS + reused Deflater
 * <li>{@link DeflaterOutputStreamWithStats} + BOAS
 * <li>{@link ByteArrayDeflaterOutputStreamWithStats}.
 * </ul>
 * <p>
 * <h3>Results</h3>
 * <p>
 * For larger sizes (e.g. 20k "customers"), there are no discernible difference between the different setups! The
 * variability between runs is so much larger than the difference between the different setups that it is impossible to
 * say that one is better than the other.
 * <p>
 * For many small sizes (e.g. 10 "customers"), there seems to be a stable result: the reuse of the Deflater is slightly
 * faster than creating a new one each time: E.g. 0.26 ms vs. 0.31 ms per compression - a stable 0.05 ms improvement.
 * The statistics adds an extra &lt; 0.01 ms per compression.
 * <p>
 * <b>Net result is, IMHO:</b> These variants with stats, and the one with the built-in byte array, are just as fast as
 * the standard Java, while providing statistics and convenience. To shave off 0.05 ms per compression, one could go for
 * reusing Deflaters - which I have chosen to <b>not</b> provide constructors for in these classes - but the overhead of
 * managing the reuse with pooling, with the potential for bugs and memory bloat, could easily outweigh the minuscule
 * gain. <i>(Note that there is a version of this in the git history a few commits back - where there was two static
 * pools of Deflaters and Inflaters which was removed due to the above reasoning).</i>
 * 
 * @see Test_Performance_InflaterInputStreamSetups
 */
public class Test_Performance_DeflaterOutputStreamsSetups {

    @Test
    public void test() throws IOException {
        checkedInFast();
    }

    private void checkedInFast() throws IOException {
        int customers = 10;
        int warmupCount = 10;
        int perfCount = 50;
        boolean useDumpStream = true;

        run(customers, warmupCount, perfCount, useDumpStream);
    }

    public void smaller() throws IOException {
        int customers = 10;
        int warmupCount = 200;
        int perfCount = 1000;
        boolean useDumpStream = true;

        run(customers, warmupCount, perfCount, useDumpStream);
    }

    public void larger() throws IOException {
        int customers = 20_000;
        int warmupCount = 2;
        int perfCount = 5;
        boolean useDumpStream = true;

        run(customers, warmupCount, perfCount, useDumpStream);
    }

    public void run(int customers, int warmupCount, int perfCount, boolean useDumpStream) throws IOException {
        Holder pristine = createHolder(customers);

        Holder holder;

        // :: Warm up the entire JVM

        holder = pristine.fork();
        holder.useDumpStream = useDumpStream;
        runPerf("Warmup run", holder, warmupCount, perfCount,
                this::compressDeflaterOutputStream);

        // :: Performance runs

        holder = pristine.fork();
        holder.useDumpStream = useDumpStream;
        runPerf("DeflaterOutputStream with own Deflater", holder, warmupCount, perfCount,
                this::compressDeflaterOutputStream);

        holder = pristine.fork();
        holder.deflater = new Deflater(DeflaterOutputStreamWithStats.DEFAULT_COMPRESSION_LEVEL);
        holder.useDumpStream = useDumpStream;
        runPerf("DeflaterOutputStream with reused Deflater", holder, warmupCount, perfCount,
                this::compressDeflaterOutputStream);
        holder.deflater.end();

        holder = pristine.fork();
        holder.useDumpStream = useDumpStream;
        runPerf("DeflaterOutputStreamWithStats", holder, warmupCount, perfCount,
                this::compressDeflaterOutputStreamWithStats);

        holder = pristine.fork();
        runPerf("ByteArrayDeflaterOutputStreamWithStats", holder, warmupCount, perfCount,
                this::compressByteArrayDeflaterOutputStreamWithStats);
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }

    private void runPerf(String what, Holder pristine, int warmupCount, int perfCount,
            ThrowingConsumer<Holder> consumer) throws IOException {
        // :: Warmup
        var holder = pristine.fork();
        for (int i = 0; i < warmupCount; i++) {
            consumer.accept(holder);
        }

        Function<Holder, String> msg = (h) -> {
            String m = what + " " + (h.useDumpStream ? " (DumpStream)" : "") +
                    ": " + ms4(h.compressedNanos / h.perfCount) + " ms";
            if (h.deflateAndWriteNanos != 0) {
                m += ", Deflate&Write: " + ms4(h.deflateAndWriteNanos / h.perfCount) + " ms";
            }
            if (h.growNanos != 0) {
                m += ", Grow: " + ms4(h.growNanos / h.perfCount) + " ms";
            }
            return m;
        };

        // :: Performance
        Holder adder = pristine.fork();
        for (int round = 0; round < 5; round++) {
            holder.resetStats(perfCount);
            for (int i = 0; i < perfCount; i++) {
                consumer.accept(holder);
            }
            System.out.println(msg.apply(holder));
            adder.addIn(holder);
        }
        System.out.println("\\-AVERAGE: " + msg.apply(adder));
    }

    private void compressDeflaterOutputStream(Holder holder) throws IOException {
        long nanosAtStart_Compressing = System.nanoTime();
        var dumpOutput = holder.getOutputStream();

        // Oops, since we're now using level=1 as default, we can't use the standard set of constructors, as
        // they will create a new "standard deflater" with level=6. So we need to create the Deflater ourselves.
        boolean singleUseDeflater = false;
        // ?: Do we need to create the Deflater ourselves?
        if (holder.deflater == null) {
            // -> Yes, create it, and remember that we need to end and clean up after ourselves.
            holder.deflater = new Deflater(DeflaterOutputStreamWithStats.DEFAULT_COMPRESSION_LEVEL);
            singleUseDeflater = true;
        }

        var deflaterOutputStream = new DeflaterOutputStream(dumpOutput, holder.deflater);
        deflaterOutputStream.write(holder.uncompressed);
        deflaterOutputStream.close();
        // ?: Did we create the Deflater ourselves?
        if (singleUseDeflater) {
            // -> Yes, we need to end it, and then null it out to leave it intact for the next run.
            holder.deflater.end();
            holder.deflater = null;
        }
        else {
            // -> No, we need to reset it.
            holder.deflater.reset();
        }

        holder.compressedNanos += System.nanoTime() - nanosAtStart_Compressing;

        Assert.assertEquals(holder.compressed.length, dumpOutput.size());
    }

    private void compressDeflaterOutputStreamWithStats(Holder holder) throws IOException {
        long nanosAtStart_Compressing = System.nanoTime();
        var dumpOutput = holder.getOutputStream();

        var deflaterOutputStream = new DeflaterOutputStreamWithStats(dumpOutput);
        deflaterOutputStream.write(holder.uncompressed);
        deflaterOutputStream.close();

        holder.compressedNanos += System.nanoTime() - nanosAtStart_Compressing;

        Assert.assertEquals(holder.compressed.length, dumpOutput.size());
    }

    private void compressByteArrayDeflaterOutputStreamWithStats(Holder holder) {
        long nanosAtStart_Compressing = System.nanoTime();
        var deflaterOutputStream = new ByteArrayDeflaterOutputStreamWithStats();
        deflaterOutputStream.write(holder.uncompressed);
        deflaterOutputStream.close();

        holder.compressedNanos += System.nanoTime() - nanosAtStart_Compressing;
        holder.deflateAndWriteNanos += deflaterOutputStream.getDeflateAndWriteTimeNanos();
        holder.growNanos += deflaterOutputStream.getGrowTimeNanos();

        Assert.assertEquals(holder.compressed.length, deflaterOutputStream.getCurrentPosition());
    }

    // ============= Internals =============

    static class Holder {
        // :: Configuration
        byte[] compressed;
        byte[] uncompressed;
        Deflater deflater;
        boolean useDumpStream;

        // :: Stats
        long compressedNanos;
        long deflateAndWriteNanos;
        long growNanos;

        int perfCount;

        ByteArrayOutputStream getOutputStream() {
            return useDumpStream ? new ByteArrayOutputStream(1) {
                private int _size = 0;

                @Override
                public void write(int b) {
                    _size += 1;
                }

                public void write(byte[] b, int off, int len) {
                    _size += len;
                }

                public int size() {
                    return _size;
                }
            } : new ByteArrayOutputStream(1024);
        }

        void resetStats(int perfCount) {
            compressedNanos = 0;
            deflateAndWriteNanos = 0;
            growNanos = 0;
            this.perfCount = perfCount;
        }

        void addIn(Holder other) {
            compressedNanos += other.compressedNanos;
            deflateAndWriteNanos += other.deflateAndWriteNanos;
            growNanos += other.growNanos;
            perfCount += other.perfCount;
        }

        public Holder fork() {
            Holder ret = new Holder();
            ret.compressed = compressed;
            ret.uncompressed = uncompressed;
            ret.deflater = deflater;
            ret.useDumpStream = useDumpStream;
            return ret;
        }
    }

    private static Holder createHolder(int customers) {
        CustomerData randomCustomerData = DummyFinancialService.createRandomReplyDTO(1234L, customers);
        try {

            long nanosAtStart_Total = System.nanoTime();
            ObjectWriter replyDtoWriter = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper()
                    .writerFor(CustomerData.class);

            byte[] serialized = replyDtoWriter.writeValueAsBytes(randomCustomerData);

            var bais = new ByteArrayInputStream(serialized);
            Deflater deflater = new Deflater(ByteArrayDeflaterOutputStreamWithStats.DEFAULT_COMPRESSION_LEVEL);
            var deflaterInputStream = new DeflaterInputStream(bais, deflater);
            byte[] compressed = deflaterInputStream.readAllBytes();
            deflaterInputStream.close();
            deflater.end();

            System.out.println("Uncompressed: " + ft(serialized.length) + "B, Compressed: "
                    + ft(compressed.length) + " B, Difference: " + ft(compressed.length - serialized.length)
                    + " B -> " + fd2(100.0 * (serialized.length - compressed.length) / serialized.length)
                    + "% saved, time: " + ms2(System.nanoTime() - nanosAtStart_Total) + " ms");
            Holder holder = new Holder();
            holder.compressed = compressed;
            holder.uncompressed = serialized;
            return holder;
        }
        catch (IOException e) {
            throw new RuntimeException("Ain't happening!", e);
        }
    }

    // Some runs:
    // 2024-09-18 21:56
    //
    // customers = 20_000
    // warmupCount = 8
    // int perfCount = 20
    // useDumpStream = false
    //
    // DeflaterOutputStream with reused Deflater : 500.7877 ms
    // DeflaterOutputStream with reused Deflater : 531.8954 ms
    // DeflaterOutputStream with reused Deflater : 518.9442 ms
    // DeflaterOutputStream with reused Deflater : 516.6008 ms
    // DeflaterOutputStream with reused Deflater : 509.9135 ms
    // DeflaterOutputStream with reused Deflater AVERAGE: 515.6283 ms
    // DeflaterOutputStream with own Deflater : 489.7545 ms
    // DeflaterOutputStream with own Deflater : 495.5560 ms
    // DeflaterOutputStream with own Deflater : 485.0863 ms
    // DeflaterOutputStream with own Deflater : 480.2063 ms
    // DeflaterOutputStream with own Deflater : 489.3996 ms
    // DeflaterOutputStream with own Deflater AVERAGE: 488.0005 ms
    // DeflaterOutputStreamWithStats : 508.6380 ms
    // DeflaterOutputStreamWithStats : 521.9401 ms
    // DeflaterOutputStreamWithStats : 513.0715 ms
    // DeflaterOutputStreamWithStats : 510.6630 ms
    // DeflaterOutputStreamWithStats : 504.9693 ms
    // DeflaterOutputStreamWithStats AVERAGE: 511.8564 ms
    // ByteArrayDeflaterOutputStreamWithStats : 512.8944 ms
    // ByteArrayDeflaterOutputStreamWithStats : 512.5650 ms
    // ByteArrayDeflaterOutputStreamWithStats : 503.3252 ms
    // ByteArrayDeflaterOutputStreamWithStats : 507.4501 ms
    // ByteArrayDeflaterOutputStreamWithStats : 500.1940 ms
    // ByteArrayDeflaterOutputStreamWithStats AVERAGE: 507.2858 ms
    //
    // DeflaterOutputStream with reused Deflater : 490.7705 ms
    // DeflaterOutputStream with reused Deflater : 487.0252 ms
    // DeflaterOutputStream with reused Deflater : 478.3303 ms
    // DeflaterOutputStream with reused Deflater : 479.2257 ms
    // DeflaterOutputStream with reused Deflater : 482.2318 ms
    // DeflaterOutputStream with reused Deflater AVERAGE: 483.5167 ms
    // DeflaterOutputStream with own Deflater : 486.4083 ms
    // DeflaterOutputStream with own Deflater : 488.0213 ms
    // DeflaterOutputStream with own Deflater : 492.5745 ms
    // DeflaterOutputStream with own Deflater : 491.3247 ms
    // DeflaterOutputStream with own Deflater : 506.2998 ms
    // DeflaterOutputStream with own Deflater AVERAGE: 492.9257 ms
    // DeflaterOutputStreamWithStats : 494.9484 ms
    // DeflaterOutputStreamWithStats : 491.7735 ms
    // DeflaterOutputStreamWithStats : 512.8526 ms
    // DeflaterOutputStreamWithStats : 514.0379 ms
    // DeflaterOutputStreamWithStats : 513.0000 ms
    // DeflaterOutputStreamWithStats AVERAGE: 505.3225 ms
    // ByteArrayDeflaterOutputStreamWithStats : 509.3592 ms
    // ByteArrayDeflaterOutputStreamWithStats : 488.1331 ms
    // ByteArrayDeflaterOutputStreamWithStats : 513.3570 ms
    // ByteArrayDeflaterOutputStreamWithStats : 495.4872 ms
    // ByteArrayDeflaterOutputStreamWithStats : 489.5858 ms
    // ByteArrayDeflaterOutputStreamWithStats AVERAGE: 499.1845 ms
    //
    // Java 21.0.2+13
    // DeflaterOutputStream with reused Deflater : 483.5750 ms
    // DeflaterOutputStream with reused Deflater : 520.0846 ms
    // DeflaterOutputStream with reused Deflater : 494.3642 ms
    // DeflaterOutputStream with reused Deflater : 503.4899 ms
    // DeflaterOutputStream with reused Deflater : 502.8281 ms
    // DeflaterOutputStream with reused Deflater AVERAGE: 500.8684 ms
    // DeflaterOutputStream with own Deflater : 493.2893 ms
    // DeflaterOutputStream with own Deflater : 491.4195 ms
    // DeflaterOutputStream with own Deflater : 503.2249 ms
    // DeflaterOutputStream with own Deflater : 502.0550 ms
    // DeflaterOutputStream with own Deflater : 490.0175 ms
    // DeflaterOutputStream with own Deflater AVERAGE: 496.0012 ms
    // DeflaterOutputStreamWithStats : 504.3137 ms
    // DeflaterOutputStreamWithStats : 498.1726 ms
    // DeflaterOutputStreamWithStats : 505.6524 ms
    // DeflaterOutputStreamWithStats : 505.0097 ms
    // DeflaterOutputStreamWithStats : 501.4374 ms
    // DeflaterOutputStreamWithStats AVERAGE: 502.9172 ms
    // ByteArrayDeflaterOutputStreamWithStats : 505.5831 ms
    // ByteArrayDeflaterOutputStreamWithStats : 486.1192 ms
    // ByteArrayDeflaterOutputStreamWithStats : 499.6392 ms
    // ByteArrayDeflaterOutputStreamWithStats : 508.8613 ms
    // ByteArrayDeflaterOutputStreamWithStats : 500.7830 ms
    // ByteArrayDeflaterOutputStreamWithStats AVERAGE: 500.1972 ms
    //
    // DeflaterOutputStream with reused Deflater : 508.1108 ms
    // DeflaterOutputStream with reused Deflater : 507.1191 ms
    // DeflaterOutputStream with reused Deflater : 507.4854 ms
    // DeflaterOutputStream with reused Deflater : 510.0219 ms
    // DeflaterOutputStream with reused Deflater : 509.2948 ms
    // \-AVERAGE: DeflaterOutputStream with reused Deflater : 508.4064 ms
    // DeflaterOutputStream with own Deflater : 504.8887 ms
    // DeflaterOutputStream with own Deflater : 507.3425 ms
    // DeflaterOutputStream with own Deflater : 509.5844 ms
    // DeflaterOutputStream with own Deflater : 508.4860 ms
    // DeflaterOutputStream with own Deflater : 506.2557 ms
    // \-AVERAGE: DeflaterOutputStream with own Deflater : 507.3115 ms
    // DeflaterOutputStreamWithStats : 510.9465 ms
    // DeflaterOutputStreamWithStats : 512.3535 ms
    // DeflaterOutputStreamWithStats : 512.1917 ms
    // DeflaterOutputStreamWithStats : 509.0912 ms
    // DeflaterOutputStreamWithStats : 509.6138 ms
    // \-AVERAGE: DeflaterOutputStreamWithStats : 510.8393 ms
    // ByteArrayDeflaterOutputStreamWithStats : 507.5529 ms, Deflate&Write: 507.4807 ms, Grow: 4.7166 ms
    // ByteArrayDeflaterOutputStreamWithStats : 507.3857 ms, Deflate&Write: 507.3237 ms, Grow: 5.7577 ms
    // ByteArrayDeflaterOutputStreamWithStats : 495.2944 ms, Deflate&Write: 495.2401 ms, Grow: 4.7204 ms
    // ByteArrayDeflaterOutputStreamWithStats : 499.1678 ms, Deflate&Write: 499.1138 ms, Grow: 4.6269 ms
    // ByteArrayDeflaterOutputStreamWithStats : 502.7014 ms, Deflate&Write: 502.6483 ms, Grow: 4.5723 ms
    // \-AVERAGE: ByteArrayDeflaterOutputStreamWithStats : 502.4204 ms, Deflate&Write: 502.3613 ms, Grow: 4.8788 ms
    //
    // STRANGE stuff with the Grow here - maybe because now heavy array copying had been done before?
    // DeflaterOutputStream with reused Deflater (DumpStream): 500.4203 ms
    // DeflaterOutputStream with reused Deflater (DumpStream): 498.6852 ms
    // DeflaterOutputStream with reused Deflater (DumpStream): 496.5783 ms
    // DeflaterOutputStream with reused Deflater (DumpStream): 493.4321 ms
    // DeflaterOutputStream with reused Deflater (DumpStream): 498.0686 ms
    // \-AVERAGE: DeflaterOutputStream with reused Deflater (DumpStream): 497.4369 ms
    // DeflaterOutputStream with own Deflater (DumpStream): 502.3009 ms
    // DeflaterOutputStream with own Deflater (DumpStream): 497.8735 ms
    // DeflaterOutputStream with own Deflater (DumpStream): 491.8119 ms
    // DeflaterOutputStream with own Deflater (DumpStream): 498.0129 ms
    // DeflaterOutputStream with own Deflater (DumpStream): 500.9037 ms
    // \-AVERAGE: DeflaterOutputStream with own Deflater (DumpStream): 498.1806 ms
    // DeflaterOutputStreamWithStats (DumpStream): 491.4779 ms
    // DeflaterOutputStreamWithStats (DumpStream): 488.8230 ms
    // DeflaterOutputStreamWithStats (DumpStream): 493.9865 ms
    // DeflaterOutputStreamWithStats (DumpStream): 495.0969 ms
    // DeflaterOutputStreamWithStats (DumpStream): 490.9242 ms
    // \-AVERAGE: DeflaterOutputStreamWithStats (DumpStream): 492.0617 ms
    // ByteArrayDeflaterOutputStreamWithStats : 503.1708 ms, Deflate&Write: 503.0519 ms, Grow: 12.2496 ms
    // ByteArrayDeflaterOutputStreamWithStats : 496.8440 ms, Deflate&Write: 496.7441 ms, Grow: 12.2853 ms
    // ByteArrayDeflaterOutputStreamWithStats : 502.7135 ms, Deflate&Write: 502.6149 ms, Grow: 12.2510 ms
    // ByteArrayDeflaterOutputStreamWithStats : 504.9506 ms, Deflate&Write: 504.8509 ms, Grow: 12.2734 ms
    // ByteArrayDeflaterOutputStreamWithStats : 499.2844 ms, Deflate&Write: 499.2017 ms, Grow: 8.8347 ms
    // \-AVERAGE: ByteArrayDeflaterOutputStreamWithStats : 501.3927 ms, Deflate&Write: 501.2927 ms, Grow: 11.5788 ms

}
