package io.mats3.util.compression;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * An {@link OutputStream} that compresses data using the Deflate algorithm. It extends {@link DeflaterOutputStream} and
 * provides statistics about the compression process. It uses a {@link #DEFAULT_COMPRESSION_LEVEL default compression
 * level of 1} (instead of 6), as this class's main intended use case is for compressing data for sending many unique
 * packets over the network, where low time and CPU usage is much more important than additional unimpressive size
 * reductions.
 * <p>
 * Notice for usage within Mats3: It doesn't seem like the Jackson library uses single-byte output at all, but rather
 * internally buffers and performs its writes in chunks of close up to, and including, 8000 bytes. This alleviates the
 * need to use a BufferedOutputStream on top of this DeflaterOutputStreamWithStats.
 * <p>
 * Thread-safety: This class is not thread-safe.
 */
public class DeflaterOutputStreamWithStats extends DeflaterOutputStream {
    /**
     * The compression level to use when compressing data. Based on some performance tests (look in the
     * 'Test_SerializationPerformance' class), using synthetic JSON data, the size reductions diminishes very fast,
     * while the CPU and time usage increases substantially. Of note, there seems to be an inflection point at level 3:
     * At higher levels, the time used goes up rather fast, while the size reduction is minimal. But even then, the time
     * used at level 3 is quite a bit higher for larger data sets: 205MB "random JSON" down to 60MB in 2.2 sec for level
     * 1, compared to 5.4 seconds for 50MB at level 3. We thus set the default to 1, as time (and CPU use) is much more
     * important than size reduction.
     * <p>
     * <i>(Note that when using "DEFAULT_COMPRESSION = -1", the default level for Zlib is 6, and this checks out when
     * comparing the timings and sizes from the tests - they are the same as with explicit 6.)</i>
     * <p>
     * 
     * "mats.deflate.compressionLevel"
     */
    public static int DEFAULT_COMPRESSION_LEVEL = 1;

    private long _uncompressedBytesInput = -1;
    private long _compressedBytesOutput = -1;

    protected long _deflateTimeNanos;
    protected long _deflateAndWriteTimeNanos;

    /**
     * Constructor which takes an {@link OutputStream} as the destination for compressed data. The internal default
     * buffer size is 512 bytes - the same as the default in {@link DeflaterOutputStream} - and which seems to offer a
     * great balance between memory usage and performance: 1024, 2048 and 4096 didn't offer any significant performance
     * improvements in the performance testing.
     *
     * @param out
     *            the destination for the compressed data.
     */
    public DeflaterOutputStreamWithStats(OutputStream out) {
        super(out, new Deflater(DEFAULT_COMPRESSION_LEVEL), 512);
    }

    /**
     * Constructor which takes an {@link OutputStream} as the destination for compressed data, as well as a buffer size.
     * You should really check whether anything else than 512 (as the default) is beneficial, as the performance testing
     * didn't show any significant improvements for higher than 512.
     *
     * @param out
     *            the destination for the compressed data.
     * @param bufferSize
     *            the size of the buffer to use when compressing the data.
     */
    public DeflaterOutputStreamWithStats(OutputStream out, int bufferSize) {
        super(out, new Deflater(DEFAULT_COMPRESSION_LEVEL), bufferSize);
    }

    /**
     * @param level
     *            the compression level to use when compressing data. {@link #DEFAULT_COMPRESSION_LEVEL The default is
     *            1}, which is adequate for messaging scenarios, where time and CPU usage is much more important than
     *            additional unimpressive size reductions.
     */
    public void setCompressionLevel(int level) {
        // NOTE: If we ever allow to supply a Deflater, we should let this method throw an IllegalStateException if the
        // user has supplied a Deflater: He should rather set the compression level on the Deflater itself. Changing
        // it via this innocuous method would affect the outside-provided Deflater, which might get unintended
        // consequences if it was a part of a pool.
        def.setLevel(level);
    }

    /**
     * @return the number of uncompressed bytes written to this stream, i.e. the original size of the data before
     *         compression.
     */
    public long getUncompressedBytesInput() {
        if (_uncompressedBytesInput == -1) {
            def.getBytesRead();
        }
        return _uncompressedBytesInput;
    }

    /**
     * @return the number of compressed bytes written to the destination, i.e. the size of the compressed data.
     */
    public long getCompressedBytesOutput() {
        if (_compressedBytesOutput == -1) {
            def.getBytesWritten();
        }
        return _compressedBytesOutput;
    }

    /**
     * @return the time spent on invoking the Deflater instance, in nanoseconds.
     */
    public long getDeflateTimeNanos() {
        return _deflateTimeNanos;
    }

    /**
     * Returns the time spent on writing the compressed data to the destination stream. It is
     * {@link #getDeflateAndWriteTimeNanos()} minus {@link #getDeflateTimeNanos()}.
     * <p>
     * Note that in the extension {@link ByteArrayDeflaterOutputStreamWithStats}, this will return zero, as the data is
     * written to a byte array instead of a stream.
     * 
     * @return the time spent on writing the compressed data to the destination stream, in nanoseconds.
     */
    public long getWriteTimeNanos() {
        return _deflateAndWriteTimeNanos - _deflateTimeNanos;
    }

    /**
     * Returns the time spent on invoking the Deflater instance and writing the compressed data to the destination
     * stream, in nanoseconds.
     * <p>
     * Note that in the extension {@link ByteArrayDeflaterOutputStreamWithStats}, this time will include the time spent
     * growing the byte array - which has its own time measurement.
     * 
     * @return the time spent on invoking the Deflater instance and writing the compressed data to the destination
     *         stream, in nanoseconds.
     */
    public long getDeflateAndWriteTimeNanos() {
        return _deflateAndWriteTimeNanos;
    }

    // Override the deflate() method to time the deflate() call.
    @Override
    protected void deflate() throws IOException {
        long nanos_Start = System.nanoTime();
        int len = def.deflate(buf, 0, buf.length);
        _deflateTimeNanos += (System.nanoTime() - nanos_Start);
        if (len > 0) {
            out.write(buf, 0, len);
        }
        _deflateAndWriteTimeNanos += (System.nanoTime() - nanos_Start);
    }

    private boolean _closed;

    @Override
    public void close() throws IOException {
        // ?: Have we already closed?
        if (!_closed) {
            // -> No, we haven't closed yet, so close now.
            _closed = true;

            // Finish the compression
            finish();

            // Read and store the final stats
            _uncompressedBytesInput = def.getBytesRead();
            _compressedBytesOutput = def.getBytesWritten();

            // Invoke super. This also closes the underlying stream.
            super.close();
        }
    }
}
