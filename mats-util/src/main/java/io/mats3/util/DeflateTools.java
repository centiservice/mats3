package io.mats3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Utilities for compressing and decompressing byte arrays and streams using the Deflate algorithm. It contains an
 * implementation of {@link DeflaterOutputStreamWithStats} and {@link InflaterInputStreamWithStats} (extensions of their
 * standard Java counterparts) which provides statistics about the compression and decompression process. There are also
 * simple byte array-to-array compress and decompress methods. The {@link InflaterInputStreamWithStats} has the ability
 * to use a byte array as the input data, which is useful when you have the data in memory already. The classes use two
 * small pools of {@link Deflater} and {@link Inflater} instances (default up to 16 each, implemented with a simple
 * non-blocking stack) to reduce the overhead of creating new instances for each operation.
 *
 * @author Endre Stølsvik 2024-09-12 00:37 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DeflateTools {
    private static final Logger log = LoggerFactory.getLogger(DeflateTools.class);
    static final int MAX_POOLED = Integer.parseInt(
            System.getProperty("mats.deflate.maxPooled", "-1"));

    static final int COMPRESSION_LEVEL = Integer.parseInt(
            System.getProperty("mats.deflate.compressionLevel", "6"));

    private static final NonblockingStack<Deflater> _deflaterPool = new NonblockingStack<>();
    private static final NonblockingStack<Inflater> _inflaterPool = new NonblockingStack<>();

    private static final AtomicLong _numberOfDeflaterReuse = new AtomicLong();
    private static final AtomicLong _numberOfInflaterReuse = new AtomicLong();

    private static final AtomicLong _numberOfDeflaterPoolEmpty = new AtomicLong();
    private static final AtomicLong _numberOfInflaterPoolEmpty = new AtomicLong();

    /**
     * An {@link OutputStream} that compresses data using the Deflate algorithm. It extends {@link DeflaterOutputStream}
     * and provides statistics about the compression process.
     * <p>
     * It uses a small pool of {@link Deflater} instances (default up to 10, implemented with a simple non-blocking
     * stack) to reduce the overhead of creating new instances for each operation.
     */
    public static class DeflaterOutputStreamWithStats extends DeflaterOutputStream {
        private long _uncompressedBytesInput = -1;
        private long _compressedBytesOutput = -1;
        private long _deflateTimeNanos;

        public DeflaterOutputStreamWithStats(OutputStream out, int bufferSize) {
            super(out, getDeflater(), bufferSize);
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

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
        }

        @Override
        public void write(int b) throws IOException {
            super.write(b);
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
                // Enpool or end the Deflater
                enpoolOrEndDeflater(def);
            }
        }
    }

    /**
     * An {@link InputStream} that decompresses data using the Deflate algorithm. It extends {@link InflaterInputStream}
     * and provides statistics about the decompression process. It has the ability to use a byte array as the input
     * data, which is useful when you have the data in memory already.
     * <p>
     * It uses a small pool of {@link Inflater} instances (default up to 10, implemented with a simple non-blocking
     * stack) to reduce the overhead of creating new instances for each operation.
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
         * Constructor which takes an {@link InputStream} as the source of compressed data, as well as a buffer size.
         * The buffer is used to read compressed data from the source, and then feeding it the Inflater. The internal
         * default size is 512 bytes, but if the data is known to be large, a larger buffer size can be used to reduce
         * the number of interactions over JNI to the native zlib library.
         */
        public InflaterInputStreamWithStats(InputStream in, int bufferSize) {
            super(in, getInflater(), bufferSize);
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
            super(dummyInputStream, getInflater(), 1);
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
                // Enpool or end the Inflater
                enpoolOrEndInflater(inf);
            }
        }
    }

    public static byte[] compress(byte[] uncompressedData) {
        Deflater deflater = getDeflater();

        // Whether we should enpool the Deflater at end
        boolean reuseDeflater = false;
        try {
            deflater.setInput(uncompressedData);
            deflater.finish();
            // Hoping for at least 50% reduction, so set "best guess" to half incoming
            ByteArrayOutputStream outputStream = new ByteArrayOutputStreamDirectRef(uncompressedData.length / 2);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            try {
                outputStream.close();
            }
            catch (IOException e) {
                // Just in case this leaves the Deflater in some strange state, ditch it instead of reuse.
                // NOT setting reuseDeflater to true.
                throw new DecompressionException("Shall not throw IOException here.", e);
            }
            // We can reuse this Deflater, since things behaved correctly
            reuseDeflater = true;
            return outputStream.toByteArray();
        }
        finally {
            // ?: Still reuse this Inflater?
            if (reuseDeflater) {
                // -> Yes reuse
                enpoolOrEndDeflater(deflater);
            }
            else {
                // -> No, not reuse, so ditch it: end(), and do NOT enpool.
                // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
                deflater.end();
            }
        }
    }

    public static byte[] decompress(byte[] compressedData, int offset, int length, int bestGuessDecompressedSize) {
        Inflater inflater = getInflater();

        // Whether we should enpool the Inflater at end
        boolean reuseInflater = false;
        try {
            inflater.setInput(compressedData, offset, length);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStreamDirectRef(bestGuessDecompressedSize);
            byte[] buffer = new byte[bestGuessDecompressedSize > 64 * 1024 ? 2048 : 1024];
            while (!inflater.finished()) {
                try {
                    int count = inflater.inflate(buffer);
                    outputStream.write(buffer, 0, count);
                }
                catch (DataFormatException e) {
                    // Just in case this leaves the Inflater in some strange state, ditch it instead of reuse.
                    // NOT setting reuseInflater to true.
                    throw new DecompressionException("DataFormatException was bad here.", e);
                }
            }
            try {
                outputStream.close();
            }
            catch (IOException e) {
                throw new DecompressionException("Shall not throw IOException here.", e);
            }
            // We can reuse this Inflater, since things behaved correctly
            reuseInflater = true;
            return outputStream.toByteArray();
        }
        finally {
            // ?: Still reuse this Inflater?
            if (reuseInflater) {
                // -> Yes reuse
                enpoolOrEndInflater(inflater);
            }
            else {
                // -> No, not reuse, so ditch it: end(), and do NOT enpool.
                // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
                inflater.end();
            }
        }
    }

    // ================ Internals

    private static Deflater getDeflater() {
        // Get a Deflater from the pool
        Deflater deflater = _deflaterPool.pop();
        // ?: Did we get a Deflater from the pool?
        if (deflater != null) {
            // -> Yes, present in pool, so return it
            _numberOfDeflaterReuse.incrementAndGet();
            return deflater;
        }
        // E-> No, none in pool, so make a new one.
        _numberOfDeflaterPoolEmpty.incrementAndGet();
        return new Deflater(COMPRESSION_LEVEL);
    }

    private static void enpoolOrEndDeflater(Deflater def) {
        // ?: Should we enpool the Deflater?
        if (_deflaterPool.size() < MAX_POOLED) {
            // -> Yes, pool not full, so enpool it.
            // Reset the Deflater, and enpool it.
            def.reset();
            _deflaterPool.push(def);
        }
        else {
            // -> No, pool full, so ditch it: end(), and do NOT enpool.
            // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
            def.end();
        }
    }

    static int getDeflaterPoolSize() {
        return _deflaterPool.size();
    }

    static long getDeflaterReuses() {
        return _numberOfDeflaterReuse.get();
    }

    static long getDeflaterPoolEmpty() {
        return _numberOfDeflaterPoolEmpty.get();
    }

    private static Inflater getInflater() {
        // Get an Inflater from the pool
        Inflater inflater = _inflaterPool.pop();
        // ?: Did we get an Inflater from the pool?
        if (inflater != null) {
            // -> Yes, present in pool, so return it
            _numberOfInflaterReuse.incrementAndGet();
            return inflater;
        }
        // E-> No, none in pool, so make a new one.
        _numberOfInflaterPoolEmpty.incrementAndGet();
        return new Inflater();
    }

    private static void enpoolOrEndInflater(Inflater inflater) {
        // ?: Should we enpool the Inflater?
        if (_inflaterPool.size() < MAX_POOLED) {
            // -> Yes, pool not full, so enpool it.
            // Reset the Inflater, and enpool it.
            inflater.reset();
            _inflaterPool.push(inflater);
        }
        else {
            // -> No, pool full, so ditch it: end(), and do NOT enpool.
            // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
            inflater.end();
        }
    }

    static int getInflaterPoolSize() {
        return _inflaterPool.size();
    }

    static long getInflaterReuses() {
        return _numberOfInflaterReuse.get();
    }

    static long getInflaterPoolEmpty() {
        return _numberOfInflaterPoolEmpty.get();
    }



    /**
     * Upon {@link #toByteArray()}, if the byte array actually is identically sized as the count, then just return the
     * byte array instead of copying it one more time. This is relevant if we know the target size. Note that this
     * obviously means that if this BAOS is used after {@link #toByteArray()} is invoked, the returned byte array will
     * be the internal buffer, and thus any further writes will be directly into this buffer.
     */
    static class ByteArrayOutputStreamDirectRef extends ByteArrayOutputStream {
        ByteArrayOutputStreamDirectRef(int size) {
            super(size);
        }

        @Override
        public byte[] toByteArray() {
            if (buf.length == count) {
                return buf;
            }
            return super.toByteArray();
        }

        byte[] getInternalBuffer() {
            return buf;
        }
    }

    /**
     * By Brian Goetz; Nonblocking stack using Treiber's algorithm.
     */
    static class NonblockingStack<E> {
        AtomicReference<Node<E>> head = new AtomicReference<>();
        AtomicInteger size = new AtomicInteger();

        public void push(E item) {
            Node<E> newHead = new Node<E>(item);
            Node<E> oldHead;
            do {
                oldHead = head.get();
                newHead.next = oldHead;
            } while (!head.compareAndSet(oldHead, newHead));
            size.incrementAndGet();
        }

        public E pop() {
            Node<E> oldHead;
            Node<E> newHead;
            do {
                oldHead = head.get();
                if (oldHead == null) {
                    return null;
                }
                newHead = oldHead.next;
            } while (!head.compareAndSet(oldHead, newHead));
            size.decrementAndGet();
            return oldHead.item;
        }

        public int size() {
            return size.get();
        }

        public int sizeFromWalk() {
            int count = 0;
            Node<E> node = head.get();
            while (node != null) {
                count++;
                node = node.next;
            }
            return count;
        }

        static class Node<E> {
            final E item;
            Node<E> next;

            public Node(E item) {
                this.item = item;
            }
        }
    }

    /**
     * "Shall not happen"-exception.
     */
    private static class DecompressionException extends RuntimeException {
        DecompressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
