package io.mats3.util.compression;

import static io.mats3.util.Tools.fd2;
import static io.mats3.util.Tools.ft;
import static io.mats3.util.Tools.ms2;
import static io.mats3.util.Tools.ms4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.junit.Assert;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.ReplyDTO;
import io.mats3.util.FieldBasedJacksonMapper;

/**
 * Performance tests between different setups of {@link InflaterInputStream}.
 * <ul>
 * <li>{@link InflaterInputStream} with own Inflater</li>
 * <li>{@link InflaterInputStream} with reused Inflater</li>
 * <li>{@link InflaterInputStreamWithStats} using BAOS</li>
 * <li>{@link InflaterInputStreamWithStats} using ArrayInput</li>
 * </ul>
 * <p>
 * <h3>Results</h3>
 * <p>
 * For larger sizes (e.g. 20k customers), the difference between the setups is negligible. With or without reused
 * Inflater is not measurable. The {@link InflaterInputStreamWithStats} is a fraction slower, (176.96 ms to 179.21 ms,
 * ~1.2%), but when using the ArrayInput, it is a small tad faster (175.46 ms).
 * <p>
 * For smaller sizes (e.g. 10 customers), the differences of reusing are minuscule in absolute terms (0.0951 ms vs.
 * 0.0905 ms, 0.05 ms) . The {@link InflaterInputStreamWithStats} is a tad slower (0.0905 ms vs. 0.1093 ms, %lt;0.02 ms,
 * ~2%). For such small sizes, the ArrayInput variant's improvement is also negligible, &lt;0.01 ms).
 * <p>
 * <b>Net result is, IMHO:</b> These performance difference between these variants with stats and ArrayInput is so small
 * that it makes no difference, while they do provide statistics and the convenience of using ArrayInput. As with the
 * DeflaterOutputStream testing, the performance difference of reusing Inflater is about 0.05 ms, which is not worth the
 * trouble of pooling or similar. It could make sense if you <i>in a particular process</i> need to decompress multiple
 * chunks (e.g. in a loop): You could create and reuse an Inflater for that process, and end() it when done.
 *
 * @see Test_Performance_DeflaterOutputStreamsSetups
 */
public class Test_Performance_InflaterInputStreamSetups {

    @Test
    public void test() throws IOException {
        checkedinFast();
    }

    public void checkedinFast() throws IOException {
        int customers = 10;
        int warmupCount = 10;
        int perfCount = 50;

        run(customers, warmupCount, perfCount);
    }

    public void smaller() throws IOException {
        int customers = 10;
        int warmupCount = 500;
        int perfCount = 5000;

        run(customers, warmupCount, perfCount);
    }

    public void larger() throws IOException {
        int customers = 20_000;
        int warmupCount = 2;
        int perfCount = 7;

        run(customers, warmupCount, perfCount);
    }

    public void run(int customers, int warmupCount, int perfCount) throws IOException {
        Holder pristine = createHolder(customers);

        Holder holder;

        // :: Warm up the entire JVM

        holder = pristine.fork();
        runPerf("Warmup run", holder, warmupCount, perfCount,
                this::decompressInflaterInputStream);

        // :: Performance runs

        holder = pristine.fork();
        runPerf("InflaterInputStream with own Inflater", holder, warmupCount, perfCount,
                this::decompressInflaterInputStream);

        holder = pristine.fork();
        holder.inflater = new Inflater();
        runPerf("InflaterInputStream with reused Inflater", holder, warmupCount, perfCount,
                this::decompressInflaterInputStream);
        holder.inflater.end();

        holder = pristine.fork();
        runPerf("InflaterInputStreamWithStats using BAOS", holder, warmupCount, perfCount,
                this::decompressInflaterInputStreamWithStats);

        holder = pristine.fork();
        runPerf("InflaterInputStreamWithStats using ArrayInput", holder, warmupCount, perfCount,
                this::decompressInflaterInputStreamWithStats_ArrayInput);
    }

    private void runPerf(String what, Holder pristine, int warmupCount, int perfCount,
            ThrowingConsumer<Holder> consumer) throws IOException {
        // :: Warmup
        var holder = pristine.fork();
        for (int i = 0; i < warmupCount; i++) {
            consumer.accept(holder);
        }

        Function<Holder, String> msg = (h) -> {
            String m = what +
                    ": " + ms4(h.decompressedNanos / h.perfCount) + " ms";
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

    private void decompressInflaterInputStream(Holder holder) throws IOException {
        long nanosAtStart_Compressing = System.nanoTime();

        var inflaterInputStream = holder.inflater != null
                ? new InflaterInputStream(new ByteArrayInputStream(holder.compressed), holder.inflater)
                : new InflaterInputStream(new ByteArrayInputStream(holder.compressed));
        int total = readFullyDevNull(holder, inflaterInputStream);
        inflaterInputStream.close();

        if (holder.inflater != null) {
            holder.inflater.reset();
        }

        holder.decompressedNanos += System.nanoTime() - nanosAtStart_Compressing;

        Assert.assertEquals(holder.uncompressed.length, total);
    }

    private void decompressInflaterInputStreamWithStats(Holder holder) throws IOException {
        long nanosAtStart_Compressing = System.nanoTime();

        var inflaterInputStream = new InflaterInputStreamWithStats(new ByteArrayInputStream(holder.compressed));
        int total = readFullyDevNull(holder, inflaterInputStream);
        inflaterInputStream.close();

        holder.decompressedNanos += System.nanoTime() - nanosAtStart_Compressing;

        Assert.assertEquals(holder.uncompressed.length, total);
        Assert.assertEquals(holder.compressed.length, inflaterInputStream.getCompressedBytesInput());
        Assert.assertEquals(holder.uncompressed.length, inflaterInputStream.getUncompressedBytesOutput());
    }

    private void decompressInflaterInputStreamWithStats_ArrayInput(Holder holder) throws IOException {
        long nanosAtStart_Compressing = System.nanoTime();

        var inflaterInputStream = new InflaterInputStreamWithStats(holder.compressed);
        int total = readFullyDevNull(holder, inflaterInputStream);
        inflaterInputStream.close();

        holder.decompressedNanos += System.nanoTime() - nanosAtStart_Compressing;

        Assert.assertEquals(holder.uncompressed.length, total);
        Assert.assertEquals(holder.compressed.length, inflaterInputStream.getCompressedBytesInput());
        Assert.assertEquals(holder.uncompressed.length, inflaterInputStream.getUncompressedBytesOutput());
    }

    private static int readFullyDevNull(Holder holder, InflaterInputStream inflaterInputStream) throws IOException {
        int len;
        int total = 0;
        while (true) {
            len = inflaterInputStream.read(holder.temp, 0, holder.temp.length);
            if (len == -1) {
                break;
            }
            total += len;
        }
        return total;
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T t) throws IOException;
    }
    // ============= Internals =============

    static class Holder {
        // :: Configuration
        byte[] compressed;
        byte[] uncompressed;
        Inflater inflater;
        byte[] temp = new byte[8000]; // Using the number which Jackson evidently uses, 8000.

        // :: Stats
        long decompressedNanos;

        int perfCount;

        void resetStats(int perfCount) {
            decompressedNanos = 0;
            this.perfCount = perfCount;
        }

        void addIn(Holder other) {
            decompressedNanos += other.decompressedNanos;
            perfCount += other.perfCount;
        }

        public Holder fork() {
            Holder ret = new Holder();
            ret.compressed = compressed;
            ret.uncompressed = uncompressed;
            ret.inflater = inflater;
            return ret;
        }
    }

    private static Holder createHolder(int customers) {
        ReplyDTO randomReplyDTO = DummyFinancialService.createRandomReplyDTO(1234L, customers);
        try {

            long nanosAtStart_Total = System.nanoTime();
            ObjectWriter replyDtoWriter = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper()
                    .writerFor(ReplyDTO.class);

            byte[] serialized = replyDtoWriter.writeValueAsBytes(randomReplyDTO);

            var bais = new ByteArrayInputStream(serialized);
            Deflater deflater = new Deflater(1);
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
    // 2024-09-19 12:07
    //
    // int customers = 20_000;
    // int warmupCount = 2;
    // int perfCount = 7;
    //
    // InflaterInputStream with own Inflater: 176.4615 ms
    // InflaterInputStream with own Inflater: 176.5285 ms
    // InflaterInputStream with own Inflater: 176.3247 ms
    // InflaterInputStream with own Inflater: 176.6467 ms
    // InflaterInputStream with own Inflater: 176.9366 ms
    // \-AVERAGE: InflaterInputStream with own Inflater: 176.5796 ms
    // InflaterInputStream with reused Inflater: 176.9880 ms
    // InflaterInputStream with reused Inflater: 176.5574 ms
    // InflaterInputStream with reused Inflater: 177.2579 ms
    // InflaterInputStream with reused Inflater: 177.2378 ms
    // InflaterInputStream with reused Inflater: 176.7653 ms
    // \-AVERAGE: InflaterInputStream with reused Inflater: 176.9613 ms
    // InflaterInputStreamWithStats using BAOS: 179.4269 ms
    // InflaterInputStreamWithStats using BAOS: 179.1126 ms
    // InflaterInputStreamWithStats using BAOS: 179.5401 ms
    // InflaterInputStreamWithStats using BAOS: 179.1841 ms
    // InflaterInputStreamWithStats using BAOS: 178.7953 ms
    // \-AVERAGE: InflaterInputStreamWithStats using BAOS: 179.2118 ms
    // InflaterInputStreamWithStats using ArrayInput: 175.2206 ms
    // InflaterInputStreamWithStats using ArrayInput: 175.3806 ms
    // InflaterInputStreamWithStats using ArrayInput: 175.7651 ms
    // InflaterInputStreamWithStats using ArrayInput: 175.2284 ms
    // InflaterInputStreamWithStats using ArrayInput: 175.7012 ms
    // \-AVERAGE: InflaterInputStreamWithStats using ArrayInput: 175.4592 ms
    //
    // int customers = 10;
    // int warmupCount = 500;
    // int perfCount = 5000;
    //
    // InflaterInputStream with own Inflater: 0.0926 ms
    // InflaterInputStream with own Inflater: 0.0926 ms
    // InflaterInputStream with own Inflater: 0.0966 ms
    // InflaterInputStream with own Inflater: 0.0975 ms
    // InflaterInputStream with own Inflater: 0.0962 ms
    // \-AVERAGE: InflaterInputStream with own Inflater: 0.0951 ms
    // InflaterInputStream with reused Inflater: 0.0906 ms
    // InflaterInputStream with reused Inflater: 0.0911 ms
    // InflaterInputStream with reused Inflater: 0.0899 ms
    // InflaterInputStream with reused Inflater: 0.0902 ms
    // InflaterInputStream with reused Inflater: 0.0905 ms
    // \-AVERAGE: InflaterInputStream with reused Inflater: 0.0905 ms
    // InflaterInputStreamWithStats using BAOS: 0.1089 ms
    // InflaterInputStreamWithStats using BAOS: 0.1113 ms
    // InflaterInputStreamWithStats using BAOS: 0.1109 ms
    // InflaterInputStreamWithStats using BAOS: 0.1094 ms
    // InflaterInputStreamWithStats using BAOS: 0.1059 ms
    // \-AVERAGE: InflaterInputStreamWithStats using BAOS: 0.1093 ms
    // InflaterInputStreamWithStats using ArrayInput: 0.0999 ms
    // InflaterInputStreamWithStats using ArrayInput: 0.0996 ms
    // InflaterInputStreamWithStats using ArrayInput: 0.0995 ms
    // InflaterInputStreamWithStats using ArrayInput: 0.1048 ms
    // InflaterInputStreamWithStats using ArrayInput: 0.1037 ms
    // \-AVERAGE: InflaterInputStreamWithStats using ArrayInput: 0.1015 ms

}
