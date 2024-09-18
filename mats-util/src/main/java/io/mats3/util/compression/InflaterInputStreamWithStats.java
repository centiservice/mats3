package io.mats3.util.compression;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * An {@link InputStream} that decompresses data using the Deflate algorithm. It extends {@link InflaterInputStream} and
 * provides statistics about the decompression process. It has the ability to use a byte array as the input data, which
 * is useful when you have the data in memory already.
 * <p>
 * Thread-safety: This class is not thread-safe.
 */
public class InflaterInputStreamWithStats extends InflaterInputStream {
    private long _compressedBytesInput = -1;
    private long _uncompressedBytesOutput = -1;

    private long _readAndInflateTimeNanos;
    private long _readTimeNanos;

    private final byte[] _inputArray;
    private final int _offset;
    private final int _length;

    private boolean _inputArrayUsed;

    /**
     * Constructor which takes an {@link InputStream} as the source of compressed data, using a buffer size of 512, same
     * default as {@link InflaterInputStream}.
     */
    public InflaterInputStreamWithStats(InputStream in) {
        this(in, 512);
    }

    /**
     * Constructor which takes an {@link InputStream} as the source of compressed data, as well as a buffer size. The
     * buffer is used to read compressed data from the source, and then setting the buffer as input to the Inflater.
     * Subsequent calls to read() are invoked on the Inflater, which reads and decompresses from the buffer.
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

    /**
     * @return the time spent on reading the compressed data from the source, in nanoseconds. Will be 0 if the input is
     *         a byte array.
     */
    public long getReadTimeNanos() {
        return _readTimeNanos;
    }

    /**
     * @return the time spent on invoking the Inflater, in nanoseconds. It is {@link #getReadAndInflateTimeNanos()} -
     *         {@link #getReadTimeNanos()}, and thus identical to {@link #getReadAndInflateTimeNanos()} if the input is
     *         a byte array.
     */
    public long getInflateTimeNanos() {
        return _readAndInflateTimeNanos - _readTimeNanos;
    }

    /**
     * @return the time spent on reading the compressed data from the source and invoking the Inflater, in nanoseconds.
     *         If the input is a byte array, reading time is 0, and thus this is identical to
     *         {@link #getInflateTimeNanos()}.
     */
    public long getReadAndInflateTimeNanos() {
        return _readAndInflateTimeNanos;
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
            // This is where the reading of the InputStream happens, so time it.
            long nanos_Start = System.nanoTime();
            super.fill();
            _readTimeNanos += (System.nanoTime() - nanos_Start);
        }
    }

    // Override the read() methods to time the read() calls, which is where the reading + decompression happens.
    // Note: super.read() calls fill() if the inflater needsInput(). Fill() reads the compressed data from the source
    // and sets it on the inflater.

    @Override
    public int read() throws IOException {
        long nanos_Start = System.nanoTime();
        int read = super.read();
        _readAndInflateTimeNanos += (System.nanoTime() - nanos_Start);
        return read;
    }

    // NOTICE: We won't override the read(byte[] b) method, as it simply calls the read(byte[] b, int off, int len)
    // method, and we'd then double count the time spent on decompression.

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        long nanos_Start = System.nanoTime();
        int read = super.read(b, off, len);
        _readAndInflateTimeNanos += (System.nanoTime() - nanos_Start);
        return read;
    }

    private boolean _closed;

    @Override
    public void close() throws IOException {
        // ?: Have we already closed?
        if (!_closed) {
            // -> No, we haven't closed yet, so close now.
            _closed = true;

            // Read and store the final stats
            _compressedBytesInput = inf.getBytesRead();
            _uncompressedBytesOutput = inf.getBytesWritten();

            // Invoke super. In case we're using the array variant, this also unnecessarily closes the dummy stream,
            // but that's fine.
            super.close();
        }
    }
}
