package io.mats3.util;

import java.io.ByteArrayOutputStream;
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
        protected long _deflateTimeNanos;

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
            // ?: Have we already closed?
            if (!_closed) {
                // -> No, we haven't closed yet, so close now.
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
    }

    /**
     * A specialization of {@link DeflaterOutputStreamWithStats} which writes the compressed data to a byte array, as if
     * the target was a {@link ByteArrayOutputStream}, but more efficient as it doesn't use an intermediate buffer to
     * write to the target byte array. Also, no method throw IOException, as it is writing to a byte array.
     * <p>
     * It allows you to supply an {@link #ByteArrayDeflaterOutputStreamWithStats(byte[], int) initial byte array}, and a
     * starting position in that array, which is useful if you want to use an existing array that may contain some
     * existing data in front. This can be used to e.g. write multiple compressed data streams into the same byte array.
     * You probably want to know about {@link #getUncroppedInternalArray()} in that case, also read below.
     * <p>
     * If the byte array is filled up, it expands it by allocating a new larger array and copying the data over.
     * <p>
     * The method {@link #toByteArray()} returns the compressed data as a byte array of the correct size (chopped to the
     * correct size). The method {@link #getUncroppedInternalArray()} returns the internal byte array that the
     * compressed data is written to, which might be the original array if supplied in the construction and the data
     * fit, or a new, larger array after expansion. It is probably not of the correct size. The reason why you would use
     * this latter method is if you want to add more data to the array, e.g. by using it as the target in a new instance
     * of this class for adding another compressed "file". The current position in the array is given by
     * {@link #getCurrentPosition()}.
     * <p>
     * Thread-safety: This class is not thread-safe.
     */
    public static class ByteArrayDeflaterOutputStreamWithStats extends DeflaterOutputStreamWithStats {
        private byte[] _outputArray;
        private int _currentPosition;

        public ByteArrayDeflaterOutputStreamWithStats() {
            this(new byte[1024], 0);
        }

        public ByteArrayDeflaterOutputStreamWithStats(byte[] outputArray, int offset) {
            super(dummyOutputStream);
            if (outputArray == null) {
                throw new IllegalArgumentException("outputArray must not be null.");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0, was [" + offset + "]");
            }
            if (offset > outputArray.length) {
                throw new IllegalArgumentException("offset must be <= outputArray.length, was [" + offset + "]");
            }
            _outputArray = outputArray;
            _currentPosition = offset;
        }

        // dummy output stream, since super's constructor null-checks the output stream.
        private static final OutputStream dummyOutputStream = new OutputStream() {
            @Override
            public void write(int b) {
            }
        };

        private byte[] _tempBuffer;

        @Override
        public void write(int b) {
            try {
                super.write(b);
            }
            catch (IOException e) {
                throw new RuntimeException("This should never happen, as we're writing to a byte array.", e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            try {
                super.write(b, off, len);
            }
            catch (IOException e) {
                throw new RuntimeException("This should never happen, as we're writing to a byte array.", e);
            }
        }

        @Override
        public void write(byte[] b) {
            try {
                super.write(b);
            }
            catch (IOException e) {
                throw new RuntimeException("This should never happen, as we're writing to a byte array.", e);
            }
        }

        @Override
        protected void deflate() {
            // The Deflater thing is a bit annoying. It doesn't have a "outputBytesAvailable()"-type method, and due
            // to the way this deflate() method is invoked by super in both write(byte[], int, int) and finish(), we
            // may end up with growing the array, but we didn't need to. Therefore we use a temporary buffer effectively
            // as a "peek" buffer to see how many bytes are available, and only grow the array if we need to.

            long nanos_Start = System.nanoTime();

            // ?: Check if we're empty of bytes in the actual output array
            if (_currentPosition == _outputArray.length) {
                // -> No, we don't have any bytes left in the output array, so grow the array.

                // ?: Do we have a temporary buffer?
                if (_tempBuffer == null) {
                    // -> No, we don't have a temporary buffer, so create one.
                    _tempBuffer = new byte[512];
                }

                int len = def.deflate(_tempBuffer, 0, _tempBuffer.length);
                // ?: Was there any data?
                if (len > 0) {
                    // -> Yes, there was data, so grow the array and copy the data over.
                    growOutputArray();
                    System.arraycopy(_tempBuffer, 0, _outputArray, _currentPosition, len);
                    // Increment the current position.
                    _currentPosition += len;
                }
            }
            else {
                // -> Yes, we have bytes left in the output array, so just deflate straight into the output array.
                int len = def.deflate(_outputArray, _currentPosition, _outputArray.length - _currentPosition);
                // ?: Was there any data?
                if (len > 0) {
                    // -> Yes, there was data, so increment the current position.
                    _currentPosition += len;
                }
            }
            _deflateTimeNanos += (System.nanoTime() - nanos_Start);
        }

        private final static int FIRST_INCREMENT = 1024; // First increment size of 1KiB
        private final static int MAX_INCREMENT = 4 * 1024 * 1024; // Max increment size of 4MiB
        private final static int OBJECT_HEADER_SIZE = 24; // Approximate size of array object header
        private final static int MAX_ARRAY_SIZE = Integer.MAX_VALUE - OBJECT_HEADER_SIZE;

        private int _increment = FIRST_INCREMENT;

        private void growOutputArray() {
            // :: Calculate the target length
            long targetLength = _outputArray.length + _increment;

            // Calculate the new increment size
            _increment = Math.min(MAX_INCREMENT, _increment * 2);

            // ?: Is the target length larger than the maximum array size?
            if (targetLength > MAX_ARRAY_SIZE) {
                // -> Yes, the target length is larger than the maximum array size.
                // ?: Is the current array size already at the maximum size?
                if (_outputArray.length >= MAX_ARRAY_SIZE) {
                    // -> Yes, the current array size is already at the maximum size, so we can't grow the array more.
                    throw new OutOfMemoryError("When resizing array, we hit MAX_ARRAY_SIZE=" + MAX_ARRAY_SIZE + ".");
                }
                else {
                    // -> No, the current array size is not at the maximum size, so set the target length to max.
                    targetLength = MAX_ARRAY_SIZE;
                }
            }

            // :: Allocate a new array of the target length, and copy the data over.
            byte[] newOutputArray = new byte[(int) targetLength];
            System.arraycopy(_outputArray, 0, newOutputArray, 0, _outputArray.length);
            _outputArray = newOutputArray;
        }

        @Override
        public void flush() {
            // NOTE: We don't allow SYNC_FLUSH in the constructors, so we don't need to do what super does.
            // :: Not sure if this makes any sense, but its at least a sensible way to flush the deflater.
            // ?: Are we finished?
            if (!def.finished()) {
                // -> No, we're not finished, so invoke deflate() until the deflater says it needs input.
                while (!def.needsInput()) {
                    deflate();
                }
            }
            // We don't have to flush the underlying stream, as we're writing to a byte array.
        }

        @Override
        public void close() {
            try {
                super.close();
            }
            catch (IOException e) {
                throw new RuntimeException("This should never happen, as we're writing to a byte array.", e);
            }
        }

        /**
         * Returns the current position in the output array - that is, where any subsequent written data would be
         * output. After finishing, as will be done by any of {@link #finish()}, {@link #close()},
         * {@link #toByteArray()} or {@link #getUncroppedInternalArray()}, the value returned by this method will be
         * equal to the length of the byte array returned by {@link #toByteArray()}.
         * 
         * @return the current position in the output array.
         */
        public int getCurrentPosition() {
            return _currentPosition;
        }

        /**
         * Returns the uncropped internal byte array that the compressed data is written to - this method returns
         * whatever array is currently in use, which in case the user supplied an array might be the original array, or
         * a new, larger array after resizing. It is very likely not of the correct size. The reason why you would use
         * this variant as opposed to {@link #toByteArray()} is if you want to add more data to the array, e.g. by using
         * it as the target in a new instance of this class for adding another compressed "file". The current position
         * in the array is given by {@link #getCurrentPosition()}.
         * <p>
         * Note: For convenience, {@link #close()} is invoked for you. This finishes the compression process, and this
         * instance can no longer be used.
         *
         * @return the internal byte array that the compressed data is written to.
         */
        public byte[] getUncroppedInternalArray() {
            close();
            return _outputArray;
        }

        /**
         * Returns the compressed data as a byte array of the correct size (chopped to the correct size). Contrast this
         * with {@link #getUncroppedInternalArray()} which returns the internal byte array, which is likely not of the
         * correct size.
         * <p>
         * Note: For convenience, {@link #close()} is invoked for you. This finishes the compression process, and this
         * instance can no longer be used.
         *
         * @return the compressed data as a byte array of the correct size.
         */
        public byte[] toByteArray() {
            close();
            // ?: Did we by chance hit the right size exactly?
            if (_currentPosition == _outputArray.length) {
                // -> Yes, it is exactly the right size, so just return the array.
                return _outputArray;
            }
            // E-> No, it is not exactly the right size, so create a new array of the right size and copy the data.
            byte[] result = new byte[_currentPosition];
            System.arraycopy(_outputArray, 0, result, 0, _currentPosition);
            return result;
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
