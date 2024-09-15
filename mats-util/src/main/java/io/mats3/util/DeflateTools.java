package io.mats3.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for compressing and decompressing byte arrays and streams using the Deflate algorithm. It contains an
 * implementation of {@link DeflaterOutputStreamWithStats} and {@link InflaterInputStreamWithStats} (extensions of their
 * standard Java counterparts) which provides statistics about the compression and decompression process. The
 * {@link InflaterInputStreamWithStats} has the ability to use a byte array as the input data, which is useful when you
 * have the data in memory already.
 *
 * @author Endre Stølsvik 2024-09-12 00:37 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DeflateTools {
    private static final Logger log = LoggerFactory.getLogger(DeflateTools.class);

    /**
     * The compression level to use when compressing data. Based on some performance tests (look in the
     * 'Test_SerializationPerformance' class), using synthetic JSON data, the size reductions diminishes very fast,
     * while the CPU and time usage increases substantially. There seems to be an inflection point at level 3: At higher
     * levels, the time used goes up rather fast, while the size reduction is minimal. So we use level 3 as default.
     * <p>
     * <i>(Note that when using "DEFAULT_COMPRESSION = -1", the default level for Zlib is 6, and this checks out when
     * comparing the timings and sizes from the tests - they are the same as with explicit 6.)</i>
     * <p>
     * You can override this by setting the system property "mats.deflate.compressionLevel" to the desired level.
     */
    public static int getCompressionLevel() {
        return Integer.parseInt(System.getProperty("mats.deflate.compressionLevel", "3"));
    }

    /**
     * An {@link OutputStream} that compresses data using the Deflate algorithm. It extends {@link DeflaterOutputStream}
     * and provides statistics about the compression process. Notice that it doesn't seem like the Jackson library uses
     * single-byte output at all, but rather performs its writes in chunks of 8000 bytes or less. This alleviates the
     * need to use a BufferedOutputStream on top of this DeflaterOutputStreamWithStats, as the Jackson library evidently
     * does its own buffering.
     */
    public static class DeflaterOutputStreamWithStats extends DeflaterOutputStream {
        private long _uncompressedBytesInput = -1;
        private long _compressedBytesOutput = -1;
        private long _deflateTimeNanos;

        /**
         * Constructor which takes an {@link OutputStream} as the destination for compressed data. The internal default
         * buffer size is 512 bytes - the same as the default in {@link DeflaterOutputStream} - and which seems to offer
         * a great balance between memory usage and performance: 1024, 2048 and 4096 didn't offer any significant
         * performance improvements in the performance testing.
         *
         * @param out
         *            the destination for the compressed data.
         */
        public DeflaterOutputStreamWithStats(OutputStream out) {
            super(out, new Deflater(getCompressionLevel()), 512);
        }

        /**
         * Constructor which takes an {@link OutputStream} as the destination for compressed data, as well as a buffer
         * size. You should really check whether anything else than 512 (as the default) is beneficial, as the
         * performance testing didn't show any significant improvements for higher than 512.
         *
         * @param out
         *            the destination for the compressed data.
         * @param bufferSize
         *            the size of the buffer to use when compressing the data.
         */
        public DeflaterOutputStreamWithStats(OutputStream out, int bufferSize) {
            super(out, new Deflater(getCompressionLevel()), bufferSize);
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
         * @return the time spent on compressing the data, in nanoseconds.
         */
        public long getDeflateTimeNanos() {
            return _deflateTimeNanos;
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
        }

        private boolean _closed;

        @Override
        public void close() throws IOException {
            if (_closed) {
                log.warn("close() invoked more than once on DeflaterOutputStreamWithStats.",
                        new Exception("DEBUG: Stacktrace for close() invoked more than once on"
                                + " DeflaterOutputStreamWithStats. This is handled, but it should be looked into."));
                return;
            }
            _closed = true;
            try {
                super.close();
            }
            finally {
                // Read and store the final stats
                _uncompressedBytesInput = def.getBytesRead();
                _compressedBytesOutput = def.getBytesWritten();
                // End the Deflater
                def.end();
            }
        }
    }

    /**
     * An {@link InputStream} that decompresses data using the Deflate algorithm. It extends {@link InflaterInputStream}
     * and provides statistics about the decompression process. It has the ability to use a byte array as the input
     * data, which is useful when you have the data in memory already.
     */
    public static class InflaterInputStreamWithStats extends InflaterInputStream {
        private long _compressedBytesInput = -1;
        private long _uncompressedBytesOutput = -1;
        private long _inflateTimeNanos;

        private final byte[] _inputArray;
        private final int _offset;
        private final int _length;

        private boolean _inputArrayUsed;

        /**
         * Constructor which takes an {@link InputStream} as the source of compressed data, using a buffer size of 512,
         * same default as {@link InflaterInputStream}.
         */
        public InflaterInputStreamWithStats(InputStream in) {
            this(in, 512);
        }

        /**
         * Constructor which takes an {@link InputStream} as the source of compressed data, as well as a buffer size.
         * The buffer is used to read compressed data from the source, and then setting the buffer as input to the
         * Inflater. Subsequent calls to read() are invoked on the Inflater, which reads and decompresses from the
         * buffer.
         */
        public InflaterInputStreamWithStats(InputStream in, int bufferSize) {
            super(in, new Inflater(), bufferSize);
            _inputArray = null;
            _offset = -1;
            _length = -1;
        }

        /**
         * Constructor which takes a byte array as the source of compressed data.
         */
        public InflaterInputStreamWithStats(byte[] inputArray) {
            this(inputArray, 0, inputArray.length);
        }

        /**
         * Constructor which takes a byte array, with offset and length, as the source of compressed data.
         */
        public InflaterInputStreamWithStats(byte[] inputArray, int offset, int count) {
            super(dummyInputStream, new Inflater(), 1);
            _offset = offset;
            _length = count;
            _inputArray = inputArray;
        }

        private static final InputStream dummyInputStream = new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };

        /**
         * @return the time spent on decompressing the data, in nanoseconds.
         */
        public long getInflateTimeNanos() {
            return _inflateTimeNanos;
        }

        /**
         * @return the number of compressed bytes read from the source, i.e. the size of the compressed data.
         */
        public long getCompressedBytesInput() {
            if (_compressedBytesInput == -1) {
                inf.getBytesRead();
            }
            return _compressedBytesInput;
        }

        /**
         * @return the number of uncompressed bytes read from this stream, i.e. the resulting size of the data after
         *         decompression.
         */
        public long getUncompressedBytesOutput() {
            if (_uncompressedBytesOutput == -1) {
                inf.getBytesWritten();
            }
            return _uncompressedBytesOutput;
        }

        @Override
        protected void fill() throws IOException {
            // ?: Are we using the input array variant?
            if (_inputArray != null) {
                // -> Yes, using input array variant, so set the input array if not already done.
                // ?: Have we already used the input array?
                if (_inputArrayUsed) {
                    // -> Yes, we have already used the input array, so this is an illegal state.
                    throw new IOException("Illegal state: The input array has already been set and used, why are we"
                            + " trying to refill?");
                }
                // E-> No, we have not used the input array, so set it now.
                inf.setInput(_inputArray, _offset, _length);
                _inputArrayUsed = true;
            }
            else {
                // -> No, not using input array variant, so invoke super.fill().
                super.fill();
            }
        }

        @Override
        public int read() throws IOException {
            long nanos_Start = System.nanoTime();
            int read = super.read();
            _inflateTimeNanos += (System.nanoTime() - nanos_Start);
            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            long nanos_Start = System.nanoTime();
            int read = super.read(b, off, len);
            _inflateTimeNanos += (System.nanoTime() - nanos_Start);
            return read;
        }

        private boolean _closed;

        @Override
        public void close() throws IOException {
            if (_closed) {
                log.warn("close() invoked more than once on InflaterInputStreamWithStats.",
                        new Exception("DEBUG: Stacktrace for close() invoked more than once on"
                                + " InflaterInputStreamWithStats. This is handled, but it should be looked into."));
                return;
            }
            _closed = true;
            try {
                // ?: Are we using the input array variant?
                if (_inputArray == null) {
                    // -> No, not using input array variant, so invoke super.close(), which closes the underlying
                    // InputStream.
                    super.close();
                }
            }
            finally {
                // Read and store the final stats
                _compressedBytesInput = inf.getBytesRead();
                _uncompressedBytesOutput = inf.getBytesWritten();
                // End the Inflater
                inf.end();
            }
        }
    }
}
