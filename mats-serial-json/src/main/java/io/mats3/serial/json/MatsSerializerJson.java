package io.mats3.serial.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.KeepMatsTrace;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.compression.DeflaterOutputStreamWithStats;
import io.mats3.util.compression.InflaterInputStreamWithStats;

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

        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Allow for configuration in override - which is not recommended, but if you need..
        extraConfigureObjectMapper(mapper);

        // Store the ObjectMapper
        _objectMapper = mapper;

        // Make specific Reader and Writer for MatsTraceStringImpl
        _matsTraceJson_Reader = mapper.readerFor(MatsTraceStringImpl.class);
        _matsTraceJson_Writer = mapper.writerFor(MatsTraceStringImpl.class);
    }

    /**
     * Override if you want to change the Jackson ObjectMapper. <b>Not really recommended.</b>
     */
    protected void extraConfigureObjectMapper(ObjectMapper mapper) {
        /* no-op */
    }

    @Override
    public boolean handlesMeta(String meta) {
        if (meta == null) {
            return false;
        }
        // ?: If the meta starts with the identification String "MatsTrace_JSON_v1", we handle it.
        return meta.startsWith(IDENTIFICATION);
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
            long nanosAtStart_SerializationAndCompression = System.nanoTime();
            // :: We now always compress since we don't know whether the result will be small.
            // Target for compression is a ByteArrayOutputStream, which we then get the byte[] from.
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            // Compress using DeflaterOutputStreamWithStats, which will give us the time taken for compression.
            DeflaterOutputStreamWithStats out = new DeflaterOutputStreamWithStats(baos);
            // Write the MatsTrace to the compressed stream.
            // NOTE: Upon having fully written the MatsTrace, it will close the underlying DeflaterOutputStream,
            // which will close the underlying ByteArrayOutputStream.
            _matsTraceJson_Writer.writeValue(out, matsTrace);
            // Get the time taken for compression.
            long nanosTaken_Compression = out.getDeflateTimeNanos();
            // Calculate the time taken for serialization, by subtracting the compression time from the total.
            long nanosTaken_Serialization = System.nanoTime() - nanosAtStart_SerializationAndCompression
                    - nanosTaken_Compression;

            // Get the compressed bytes from the ByteArrayOutputStream.
            byte[] resultBytes = baos.toByteArray();
            // Get the actual MatsTrace serialized length from the DeflaterOutputStreamWithStats, which holds of how
            // many bytes were written to it by Jackson.
            long serializedBytesLength = out.getUncompressedBytesInput();
            // Create the meta string, which is the identification, the compression method, and the decompressed size.
            String meta = IDENTIFICATION + ':' + COMPRESS_DEFLATE + DECOMPRESSED_SIZE_ATTRIBUTE + serializedBytesLength;

            return new SerializedMatsTraceImpl(resultBytes, meta, (int) serializedBytesLength, nanosTaken_Serialization,
                    nanosTaken_Compression);
        }
        catch (IOException e) {
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
            long nanosTaken_Decompression;
            long nanosTaken_Deserialization;
            int decompressedBytesLength;

            // ?: Is there a colon in the meta string?
            if (meta.indexOf(':') != -1) {
                // -> Yes, there is. This is the identification-meta, so chop off everything before it.
                meta = meta.substring(meta.indexOf(':') + 1);
            }

            // NOTE: As of 2024-09-15, we only serialize with "deflate", but due to the existing user base, we need to
            // handle both "deflate" and "plain" for incoming - the latter for when we didn't compress small payloads.

            MatsTrace<String> matsTrace;
            if (meta.startsWith(COMPRESS_DEFLATE)) {
                // -> Compressed, so decompress the incoming bytes
                long nanosStart_DecompressionAndDeserialization = System.nanoTime();
                // Decompress using InflaterInputStreamWithStats, and the offset and length.
                InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(matsTraceBytes, offset, length);
                // Read the MatsTrace from the decompressed stream.
                // NOTE: Upon having fully read the MatsTrace, it will close the underlying InflaterOutputStream
                matsTrace = _matsTraceJson_Reader.readValue(in);
                // Get the decompressed bytes length, and the decompression time.
                decompressedBytesLength = (int) in.getUncompressedBytesOutput();
                nanosTaken_Decompression = in.getReadAndInflateTimeNanos();
                // Calculate the time taken for deserialization, by subtracting the decompression time from the total.
                nanosTaken_Deserialization = System.nanoTime() - nanosStart_DecompressionAndDeserialization
                        - nanosTaken_Decompression;
            }
            else if (meta.startsWith(COMPRESS_PLAIN)) {
                // -> Plain, no compression - use the incoming bytes directly
                // There is no decompression, so we "start deserialization timer" at the beginning.
                long nanosStart_Deserialization = System.nanoTime();
                // It per definition (and API contract) takes 0 nanos to NOT decompress.
                nanosTaken_Decompression = 0L;
                // The decompressed bytes length is the same as the incoming length, since we do not decompress.
                decompressedBytesLength = length;
                // Deserialize directly from the incoming bytes, using offset and length.
                matsTrace = _matsTraceJson_Reader.readValue(matsTraceBytes, offset, length);
                nanosTaken_Deserialization = System.nanoTime() - nanosStart_Deserialization;
            }
            else {
                throw new AssertionError("Can only deserialize 'plain' and 'deflate'.");
            }

            return new DeserializedMatsTraceImpl(matsTrace, matsTraceBytes.length, decompressedBytesLength,
                    nanosTaken_Deserialization, nanosTaken_Decompression);
        }
        catch (IOException e) {
            throw new SerializationException("Couldn't deserialize MatsTrace from given JSON, which is crazy!\n"
                    + new String(matsTraceBytes, StandardCharsets.UTF_8), e);
        }
    }

    private static final class DeserializedMatsTraceImpl implements DeserializedMatsTrace<String> {
        private final MatsTrace<String> _matsTrace;
        private final int _sizeIncoming;
        private final int _sizeDecompressed;
        private final long _nanosDeserialization;
        private final long _nanosDecompression;

        public DeserializedMatsTraceImpl(MatsTrace<String> matsTrace, int sizeIncoming, int sizeDecompressed,
                long nanosDeserialization, long nanosDecompression) {
            _matsTrace = matsTrace;
            _sizeIncoming = sizeIncoming;
            _sizeDecompressed = sizeDecompressed;
            _nanosDeserialization = nanosDeserialization;
            _nanosDecompression = nanosDecompression;
        }

        @Override
        public MatsTrace<String> getMatsTrace() {
            return _matsTrace;
        }

        @Override
        public int getSizeIncoming() {
            return _sizeIncoming;
        }

        @Override
        public int getSizeDecompressed() {
            return _sizeDecompressed;
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
    public int sizeOfSerialized(String s) {
        if (s == null) {
            return 0;
        }
        return s.length();
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
        // ?: Boolean?
        if ((Boolean.class == clazz) || (boolean.class == clazz)) {
            // -> Yes, boolean - deserialize from "false".
            // Note: Jackson also handles "0" and "1", but this is more general (GSON does not)
            return deserializeObject("0", clazz);
        }

        // ?: Is it otherwise a primitive or primitive wrapper class?
        if (clazz.isPrimitive() // Note: includes character.class
                || Number.class.isAssignableFrom(clazz)
                || (Character.class == clazz)) {
            // -> Yes number or char, so then "0" and "1" works for all.
            return deserializeObject("0", clazz);
        }

        if (String.class == clazz) {
            @SuppressWarnings("unchecked")
            T t = (T) "";
            return t;
        }

        // E-> No special case, so object

        // :: Deserialize from JSON empty object "{}"
        // Note: Newer Jackson and GSON also handles deserializing any Java Record from "{}".
        try {
            return deserializeObject("{}", clazz);
        }
        catch (SerializationException e) {
            throw new CannotCreateEmptyInstanceException("Could not create an empty object of type [" + clazz + "] by"
                    + " attempting to deserialize the empty object JSON string \"{}\".", e);
        }
    }

    private static class CannotCreateEmptyInstanceException extends SerializationException {
        CannotCreateEmptyInstanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
