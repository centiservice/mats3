package io.mats3.util.compression;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A specialization of {@link DeflaterOutputStreamWithStats} which writes the compressed data to a byte array, as if the
 * target was a {@link ByteArrayOutputStream}. It is marginally more efficient as it doesn't use an intermediate buffer
 * to write to the target byte array. The growing strategy is also a bit more memory conservative in that the max grow
 * increment is capped at 8 MiB, instead of pure doubling. Also, no method throw IOException, as it is writing to a byte
 * array.
 * <p>
 * It allows you to supply an {@link #ByteArrayDeflaterOutputStreamWithStats(byte[], int) initial byte array}, and a
 * starting position in that array, which is useful if you want to use an existing array that may contain some existing
 * data in front. This can be used to e.g. write multiple compressed data streams into the same byte array. You probably
 * want to know about {@link #getUncroppedInternalArray()} in that case, also read below.
 * <p>
 * If the byte array is filled up, it is grown by allocating a new larger array and copying the data over. It does this
 * by using a capped exponential growth strategy, starting at an increment of 1KiB, and doubling the increment each
 * time, capped at 8MiB. Compared to the <code>ByteArrayOutputStream</code>, which grows by doubling each time, this is
 * a trade-off: This strategy will at large sizes have higher memory churn (as it grows and thus reallocates and copies
 * more often), but it will have lower max memory usage (as it grows less each time). This becomes pronounced when the
 * data becomes large: When the size for example tips over 200MiB, this solution will at the grow-point have a max
 * memory usage of 408MiB (200MiB + 208MiB), while <code>ByteArrayOutputStream</code> will need 600MiB (200MiB +
 * 400MiB).
 * <p>
 * The method {@link #toByteArray()} returns the compressed data as a byte array of the correct size (chopped to the
 * correct size). The method {@link #getUncroppedInternalArray()} returns the internal byte array that the compressed
 * data is written to, which might be the original array if supplied in the construction and the data fits, or a new,
 * larger array after growing. It is probably not of the correct size. The reason why you would use this latter method
 * is if you want to add more data to the array, e.g. by using it as the target in a new instance of this class for
 * adding another compressed "file". The current position in the array is given by {@link #getCurrentPosition()}.
 * <p>
 * Thread-safety: This class is not thread-safe.
 */
public class ByteArrayDeflaterOutputStreamWithStats extends DeflaterOutputStreamWithStats {
    private byte[] _outputArray;
    private int _currentPosition;

    private long _growTimeNanos;

    // dummy output stream, since super's constructor null-checks the output stream argument.
    private static final OutputStream __dummyOutputStream = new OutputStream() {
        @Override
        public void write(int b) {
            throw new AssertionError("This output stream should never be written to - it is just a dummy."
                    + " We are writing to a byte array. You're witnessing a bug in this class.");
        }
    };

    public ByteArrayDeflaterOutputStreamWithStats() {
        this(new byte[1024], 0);
    }

    public ByteArrayDeflaterOutputStreamWithStats(byte[] outputArray, int offset) {
        // We're not using the super's output stream, so we just pass a dummy output stream.
        // We're also not using the super's buffer, so we just pass 1 (it will allocate it, but we won't use it).
        super(__dummyOutputStream, 1);
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

    // ================================================================================================================
    // All write methods just call through to super, but rethrowing exceptions as RuntimeExceptions, as they should
    // never happen in this class, since we're writing to a byte array.
    // Note that the actual writing is "caught" in deflate() - the dummy output stream is never written to.
    // ================================================================================================================

    @Override
    public void write(int b) {
        try {
            super.write(b);
        }
        catch (IOException e) {
            throw new UnexpectedException(e);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        try {
            super.write(b, off, len);
        }
        catch (IOException e) {
            throw new UnexpectedException(e);
        }
    }

    @Override
    public void write(byte[] b) {
        try {
            super.write(b);
        }
        catch (IOException e) {
            throw new UnexpectedException(e);
        }
    }

    /**
     * Returns the current position in the output array - that is, where any subsequent written data would be output.
     * After finishing and thus completing the compression process, as will be done by any of {@link #finish()},
     * {@link #close()}, {@link #toByteArray()} or {@link #getUncroppedInternalArray()}, the value returned by this
     * method will be equal to the length of the byte array returned by {@link #toByteArray()}.
     *
     * @return the current position in the output array.
     */
    public int getCurrentPosition() {
        return _currentPosition;
    }

    /**
     * Returns the time spent on growing the output array (allocate new, copy over), in nanoseconds. Note that
     * {@link #getDeflateAndWriteTimeNanos()} includes this time.
     * 
     * @return the time spent on growing the output array (allocate new, copy over), in nanoseconds.
     */
    public long getGrowTimeNanos() {
        return _growTimeNanos;
    }

    /**
     * Returns the uncropped internal byte array that the compressed data is written to - this method returns whatever
     * array is currently in use, which in case the user supplied an array might be the original array, or a new, larger
     * array after resizing. It is very likely not of the correct size. The reason why you would use this variant as
     * opposed to {@link #toByteArray()} is if you want to add more data to the array, e.g. by using it as the target in
     * a new instance of this class for adding another compressed "file". The current position in the array is given by
     * {@link #getCurrentPosition()}.
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
     * Returns the compressed data as a byte array of the correct size (chopped to the correct size). Contrast this with
     * {@link #getUncroppedInternalArray()} which returns the internal byte array, which is likely larger than the
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
            throw new UnexpectedException(e);
        }
    }

    /**
     * Thrown all the places where an IOException may occur by OutputStream contract, which should never happen in this
     * class since we're writing to a byte array, This to avoid having to declare IOException in the method signatures,
     * which should make it a bit more convenient to use.
     */
    private static class UnexpectedException extends RuntimeException {
        public UnexpectedException(Throwable cause) {
            super("This should never happen, as we're writing to a byte array.", cause);
        }
    }

    // ===== Internals

    private final static int FIRST_INCREMENT = 1024; // First increment size of 1KiB
    private final static int MAX_INCREMENT = 8 * 1024 * 1024; // Max increment size of 8MiB
    private final static int OBJECT_HEADER_SIZE = 24; // Approximate size of array object header
    private final static int MAX_ARRAY_SIZE = Integer.MAX_VALUE - OBJECT_HEADER_SIZE;

    private byte[] _tempBuffer;
    private int _increment = FIRST_INCREMENT;

    @Override
    protected void deflate() {
        // The Deflater thing is a bit annoying. It doesn't have a "outputBytesAvailable()"-type method, and due to the
        // way this deflate() method is invoked by super in both write(byte[], int, int) and finish(), we may end up
        // with growing the array, but we didn't need to. Therefore we use a temporary buffer effectively as a "peek"
        // buffer to see how many bytes are available, and only grow the array if we need to.

        long nanos_Start = System.nanoTime();

        // ?: Check if we're empty of space in the actual output array
        if (_currentPosition == _outputArray.length) {
            // -> No, we don't have any bytes left in the output array, so might need to grow the array.

            // :: Check whether there actually are bytes left in the deflater, using a temp array. This to avoid
            // growing the array if we don't need to.

            // ?: Do we have a temporary buffer?
            if (_tempBuffer == null) {
                // -> No, we don't have a temporary buffer, so create one.
                _tempBuffer = new byte[512];
            }

            int len = def.deflate(_tempBuffer, 0, _tempBuffer.length);
            // ?: Was there any data?
            if (len > 0) {
                // -> Yes, there was data, so grow the array and copy the data over.
                long nanos_StartGrow = System.nanoTime();
                growOutputArray();
                System.arraycopy(_tempBuffer, 0, _outputArray, _currentPosition, len);
                _growTimeNanos += (System.nanoTime() - nanos_StartGrow);

                // Increment the current position.
                _currentPosition += len;
            }
        }
        else {
            // -> Yes, we have bytes left in the output array, so just deflate straight into the output array.
            int len = def.deflate(_outputArray, _currentPosition, _outputArray.length - _currentPosition);
            // Increment the current position (might have been zero, but no use in checking).
            _currentPosition += len;
        }
        // Record the time spent on this deflate() call.
        long nanos_Total = System.nanoTime() - nanos_Start;
        _deflateTimeNanos += nanos_Total;
        _deflateAndWriteTimeNanos += nanos_Total;
    }

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
}
