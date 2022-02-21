package io.mats3.serial.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * Implementation of {@link MatsSerializer} that employs <a href="https://github.com/FasterXML/jackson">Jackson JSON
 * library</a> for serialization and deserialization, and compress and decompress using {@link Deflater} and
 * {@link Inflater}.
 * <p />
 * The Jackson {@link ObjectMapper} is configured to only handle fields (think "data struct"), i.e. not use setters or
 * getters; and to only include non-null fields; and upon deserialization to ignore properties from the JSON that has no
 * field in the class to be deserialized into (both to enable the modification of DTOs on the client side by removing
 * fields that aren't used in that client scenario, and to handle <i>widening conversions<i> for incoming DTOs), and to
 * use string serialization for dates (and handle the JSR310 new dates):
 *
 * <pre>
 * // Create Jackson ObjectMapper
 * ObjectMapper mapper = new ObjectMapper();
 * // Do not use setters and getters, thus only fields, and ignore visibility modifiers.
 * mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
 * mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
 * // Drop null fields (null fields in DTOs are dropped from serialization to JSON)
 * mapper.setSerializationInclusion(Include.NON_NULL);
 * // Do not fail on unknown fields (i.e. if DTO class to deserialize to lacks fields that are present in the JSON)
 * mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
 * // Handle the java.time classes sanely, i.e. as dates, not a bunch of integers.
 * mapper.registerModule(new JavaTimeModule());
 * // .. and write dates and times as Strings, e.g. 2020-11-15
 * mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
 * // Handle JDK8 Optionals as normal fields.
 * mapper.registerModule(new Jdk8Module());
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class MatsSerializerJson implements MatsSerializer<String> {

    public static String IDENTIFICATION = "MatsTrace_JSON_v1";

    /**
     * The default compression level - which I chose to be {@link Deflater#BEST_SPEED} (compression level 1), since I
     * assume that the rather small incremental reduction in size does not outweigh the pretty large increase in time,
     * as one hopefully runs on a pretty fast network (and that the MQ backing store is fast).
     */
    public static int DEFAULT_COMPRESSION_LEVEL = Deflater.BEST_SPEED;

    private final int _compressionLevel;

    private final ObjectMapper _objectMapper;
    private final ObjectReader _matsTraceJson_Reader;
    private final ObjectWriter _matsTraceJson_Writer;

    /**
     * Constructs a MatsSerializer, using the {@link #DEFAULT_COMPRESSION_LEVEL} (which is {@link Deflater#BEST_SPEED},
     * which is 1).
     */
    public static MatsSerializerJson create() {
        return new MatsSerializerJson(DEFAULT_COMPRESSION_LEVEL);
    }

    /**
     * Constructs a MatsSerializer, using the specified Compression Level - refer to {@link Deflater}'s constants and
     * levels.
     *
     * @param compressionLevel
     *            the compression level given to {@link Deflater} to use.
     */
    public static MatsSerializerJson create(int compressionLevel) {
        return new MatsSerializerJson(compressionLevel);
    }

    /**
     * Constructs a MatsSerializer, using the specified Compression Level - refer to {@link Deflater}'s constants and
     * levels.
     *
     * @param compressionLevel
     *            the compression level given to {@link Deflater} to use.
     */
    protected MatsSerializerJson(int compressionLevel) {
        _compressionLevel = compressionLevel;

        ObjectMapper mapper = new ObjectMapper();

        // Read and write any access modifier fields (e.g. private)
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

        // Drop nulls
        mapper.setSerializationInclusion(Include.NON_NULL);

        // If props are in JSON that aren't in Java DTO, do not fail.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Write e.g. Dates as "1975-03-11" instead of timestamp, and instead of array-of-ints [1975, 3, 11].
        // Uses ISO8601 with milliseconds and timezone (if present).
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Optional, OptionalLong, OptionalDouble
        mapper.registerModule(new Jdk8Module());

        // Make specific Reader and Writer for MatsTraceStringImpl (thus possibly caching class structure?)
        _matsTraceJson_Reader = mapper.readerFor(MatsTraceStringImpl.class);
        _matsTraceJson_Writer = mapper.writerFor(MatsTraceStringImpl.class);
        _objectMapper = mapper;

        // TODO / OPTIMIZE: What about making specific mappers for each new class found, using e.g. ConcurrentHashMap?
    }

    @Override
    public boolean handlesMeta(String meta) {
        if (meta == null) {
            return false;
        }
        // If it starts with the old "plain" or "deflate", then we handle it, as well as if it is the new identification
        // "MatsTrace_JSON_v1".
        return meta.startsWith(COMPRESS_DEFLATE) || meta.startsWith(COMPRESS_PLAIN) | meta.startsWith(IDENTIFICATION);
    }

    @Override
    public MatsTrace<String> createNewMatsTrace(String traceId, String flowId,
            KeepMatsTrace keepMatsTrace, boolean nonPersistent, boolean interactive, long ttlMillis, boolean noAudit) {
        return MatsTraceStringImpl.createNew(traceId, flowId, keepMatsTrace, nonPersistent, interactive, ttlMillis,
                noAudit);
    }

    private static final String COMPRESS_DEFLATE = "deflate";
    private static final String COMPRESS_PLAIN = "plain";
    private static final String DECOMPRESSED_SIZE_ATTRIBUTE = ";decompSize=";

    @Override
    public SerializedMatsTrace serializeMatsTrace(MatsTrace<String> matsTrace) {
        try {
            long nanosAtStart_Serialization = System.nanoTime();
            byte[] serializedBytes = _matsTraceJson_Writer.writeValueAsBytes(matsTrace);
            long now = System.nanoTime();
            long nanosTaken_Serialization = now - nanosAtStart_Serialization;
            long nanosAtStart_Compression = now;

            String meta;
            byte[] resultBytes;
            long nanosTaken_Compression;

            if (serializedBytes.length > 900) {
                resultBytes = compress(serializedBytes);
                nanosTaken_Compression = System.nanoTime() - nanosAtStart_Compression;
                // Add the uncompressed size, for precise buffer allocation for decompression.
                meta = COMPRESS_DEFLATE + DECOMPRESSED_SIZE_ATTRIBUTE + serializedBytes.length;
            }
            else {
                resultBytes = serializedBytes;
                nanosTaken_Compression = 0;
                meta = COMPRESS_PLAIN;
            }

            return new SerializedMatsTraceImpl(resultBytes, meta, serializedBytes.length, nanosTaken_Serialization,
                    nanosTaken_Compression);
        }
        catch (JsonProcessingException e) {
            throw new SerializationException("Couldn't serialize MatsTrace, which is crazy!\n" + matsTrace, e);
        }
    }

    private static class SerializedMatsTraceImpl implements SerializedMatsTrace {
        private final byte[] _matsTraceBytes;
        private final String _meta;
        private final int _sizeUncompressed;
        private final long _nanosSerialization;
        private final long _nanosCompression;

        public SerializedMatsTraceImpl(byte[] matsTraceBytes, String meta, int sizeUncompressed,
                long nanosSerialization, long nanosCompression) {
            _matsTraceBytes = matsTraceBytes;
            _meta = meta;
            _sizeUncompressed = sizeUncompressed;
            _nanosSerialization = nanosSerialization;
            _nanosCompression = nanosCompression;
        }

        @Override
        public byte[] getMatsTraceBytes() {
            return _matsTraceBytes;
        }

        @Override
        public String getMeta() {
            return _meta;
        }

        @Override
        public int getSizeUncompressed() {
            return _sizeUncompressed;
        }

        @Override
        public long getNanosSerialization() {
            return _nanosSerialization;
        }

        @Override
        public long getNanosCompression() {
            return _nanosCompression;
        }
    }

    @Override
    public DeserializedMatsTrace<String> deserializeMatsTrace(byte[] matsTraceBytes, String meta) {
        return deserializeMatsTrace(matsTraceBytes, 0, matsTraceBytes.length, meta);
    }

    @Override
    public DeserializedMatsTrace<String> deserializeMatsTrace(byte[] matsTraceBytes, int offset, int length,
            String meta) {
        try {
            long nanosStart = System.nanoTime();
            long decompressionNanos;
            long nanosStartDeserialization;

            int decompressedBytesLength;

            // ?: Is there a colon in the meta string?
            if (meta.indexOf(':') != -1) {
                // -> Yes, there is. This is the identification-meta, so chop off everything before it.
                // NOTICE: It is currently not added as prefix, as another implementation of the Mats-concepts are using
                // an older version of MatsSerializer, which does not handle it.
                // TODO: When "everybody" is at-or-above 0.16.0, it can be added.
                meta = meta.substring(meta.indexOf(':') + 1);
            }

            MatsTrace<String> matsTrace;
            if (meta.startsWith(COMPRESS_DEFLATE)) {
                // -> Compressed, so decompress the incoming bytes
                // Do an initial guess on the decompressed size
                int bestGuessDecompressedSize = length * 4;
                // Find actual decompressed size from meta, if present
                int decompressedBytesAttributeIndex = meta.indexOf(DECOMPRESSED_SIZE_ATTRIBUTE);
                // ?: Was the size attribute present?
                if (decompressedBytesAttributeIndex != -1) {
                    // -> Yes, present.
                    // Find the start of the number
                    int start = decompressedBytesAttributeIndex + DECOMPRESSED_SIZE_ATTRIBUTE.length();
                    // Find the end of the number - either to next ';', or till end.
                    int end = meta.indexOf(';', start);
                    end = (end != -1) ? end : meta.length();
                    String sizeString = meta.substring(start, end);
                    bestGuessDecompressedSize = Integer.parseInt(sizeString);
                }

                // Decompress
                byte[] decompressedBytes = decompress(matsTraceBytes, offset, length, bestGuessDecompressedSize);
                // Begin deserialization time
                nanosStartDeserialization = System.nanoTime();
                // Store how long it took to decompress (shall not be zero, since we did decompress).
                decompressionNanos = Math.max(1L, nanosStartDeserialization - nanosStart);
                // Store the size of the decompressed array
                decompressedBytesLength = decompressedBytes.length;
                // Deserialize using the entire decompressed byte array
                matsTrace = _matsTraceJson_Reader.readValue(decompressedBytes);
            }
            else if (meta.startsWith(COMPRESS_PLAIN)) {
                // -> Plain, no compression - use the incoming bytes directly
                // There is no decompression, so we "start deserialization timer" at the beginning.
                nanosStartDeserialization = nanosStart;
                // It per definition (and API contract) takes 0 nanos to NOT decompress.
                decompressionNanos = 0L;
                // The decompressed bytes length is the same as the incoming length, since we do not decompress.
                decompressedBytesLength = length;
                // Deserialize directly from the incoming bytes, using offset and length.
                matsTrace = _matsTraceJson_Reader.readValue(matsTraceBytes, offset, length);
            }
            else {
                throw new AssertionError("Can only deserialize 'plain' and 'deflate'.");
            }

            long deserializationNanos = System.nanoTime() - nanosStartDeserialization;
            return new DeserializedMatsTraceImpl(matsTrace, decompressedBytesLength, deserializationNanos,
                    decompressionNanos);
        }
        catch (IOException e) {
            throw new SerializationException("Couldn't deserialize MatsTrace from given JSON, which is crazy!\n"
                    + new String(matsTraceBytes, StandardCharsets.UTF_8), e);
        }
    }

    private static final class DeserializedMatsTraceImpl implements DeserializedMatsTrace<String> {
        private final MatsTrace<String> _matsTrace;
        private final int _sizeUncompressed;
        private final long _nanosDeserialization;
        private final long _nanosDecompression;

        public DeserializedMatsTraceImpl(MatsTrace<String> matsTrace, int sizeUncompressed,
                long nanosDeserialization, long nanosDecompression) {
            _matsTrace = matsTrace;
            _sizeUncompressed = sizeUncompressed;
            _nanosDeserialization = nanosDeserialization;
            _nanosDecompression = nanosDecompression;
        }

        @Override
        public MatsTrace<String> getMatsTrace() {
            return _matsTrace;
        }

        @Override
        public int getSizeDecompressed() {
            return _sizeUncompressed;
        }

        @Override
        public long getNanosDeserialization() {
            return _nanosDeserialization;
        }

        @Override
        public long getNanosDecompression() {
            return _nanosDecompression;
        }
    }

    @Override
    public String serializeObject(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return _objectMapper.writeValueAsString(object);
        }
        catch (JsonProcessingException e) {
            throw new SerializationException("Couldn't serialize Object [" + object + "].", e);
        }
    }

    @Override
    public <T> T deserializeObject(String serialized, Class<T> type) {
        if (serialized == null) {
            return null;
        }
        try {
            return _objectMapper.readValue(serialized, type);
        }
        catch (IOException e) {
            throw new SerializationException("Couldn't deserialize JSON into object of type [" + type + "].\n"
                    + serialized, e);
        }
    }

    @Override
    public <T> T newInstance(Class<T> clazz) {
        Constructor<T> noArgsConstructor;
        try {
            noArgsConstructor = clazz.getDeclaredConstructor();
        }
        catch (NoSuchMethodException e) {
            throw new CannotCreateEmptyInstanceException("Missing no-args constructor on class ["
                    + clazz.getName() + "].", e);
        }
        try {
            noArgsConstructor.setAccessible(true);
            return noArgsConstructor.newInstance();
        }
        catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new CannotCreateEmptyInstanceException("Couldn't create new empty instance of class ["
                    + clazz.getName() + "].", e);
        }
    }

    private static class CannotCreateEmptyInstanceException extends SerializationException {
        CannotCreateEmptyInstanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final NonblockingStack<Deflater> _deflaterPool = new NonblockingStack<>();

    protected byte[] compress(byte[] data) {
        // Get a Inflater from the pool
        Deflater deflater = _deflaterPool.pop();
        // ?: Did we get an Inflater from the pool?
        if (deflater == null) {
            // -> No, so make a new one.
            deflater = new Deflater(_compressionLevel);
        }

        // Whether we should enpool the deflater at end
        boolean reuseDeflater = false;
        try {
            deflater.setInput(data);
            // Hoping for at least 50% reduction, so set "best guess" to half incoming
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length / 2);
            deflater.finish();
            byte[] buffer = new byte[2048];
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
                // -> Yes reuse, so reset() it, and enpool.
                deflater.reset();
                _deflaterPool.push(deflater);
            }
            else {
                // -> No, not reuse, so ditch it: end(), and do NOT enpool.
                // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
                deflater.end();
            }
        }
    }

    private static final NonblockingStack<Inflater> _inflaterPool = new NonblockingStack<>();

    protected byte[] decompress(byte[] data, int offset, int length, int bestGuessDecompressedSize) {
        // Get a Inflater from the pool
        Inflater inflater = _inflaterPool.pop();
        // ?: Did we get an Inflater from the pool?
        if (inflater == null) {
            // -> No, so make a new one.
            inflater = new Inflater();
        }

        // Whether we should enpool the inflater at end
        boolean reuseInflater = false;
        try {
            inflater.setInput(data, offset, length);
            // TODO: Optimize: Decompress directly to bytearray: toByteArray() creates a new.
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(bestGuessDecompressedSize);
            byte[] buffer = new byte[4096];
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
                // -> Yes reuse, so reset() it, and enpool.
                inflater.reset();
                _inflaterPool.push(inflater);
            }
            else {
                // -> No, not reuse, so ditch it: end(), and do NOT enpool.
                // Invoke the "end()" method to timely release off-heap resource, thus not depending on finalization.
                inflater.end();
            }
        }
    }

    /**
     * By Brian Goetz; Nonblocking stack using Treiber's algorithm.
     */
    private static class NonblockingStack<E> {
        AtomicReference<Node<E>> head = new AtomicReference<>();

        public void push(E item) {
            Node<E> newHead = new Node<E>(item);
            Node<E> oldHead;
            do {
                oldHead = head.get();
                newHead.next = oldHead;
            } while (!head.compareAndSet(oldHead, newHead));
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
            return oldHead.item;
        }

        static class Node<E> {
            final E item;
            Node<E> next;

            public Node(E item) {
                this.item = item;
            }
        }
    }

    private static class DecompressionException extends SerializationException {
        DecompressionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
