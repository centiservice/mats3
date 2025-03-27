package io.mats3.serial;

import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * Defines the operations needed serialize and deserialize {@link MatsTrace}s to and from byte arrays (e.g. UTF-8
 * encoded JSON or XML, or some binary serialization protocol), <b>and</b> STOs and DTOs to and from some type, e.g.
 * byte arrays or Strings. This is separated out from the Mats communication implementation (i.e. JMS or RabbitMQ), as
 * it is a separate aspect, i.e. both the JMS and RabbitMQ implementation can utilize the same serializer.
 * <p />
 * There are two levels of serialization needed: For the DTOs and STOs that the Mats API expose to the "end user", and
 * then the serialization of the MatsTrace itself. There is an implementation of MatsTrace in the impl package called
 * <code>MatsTraceFieldImpl</code> which is meant to be serialized by fields (thus the field names are short).
 * <p>
 * The default implementation in 'mats-serial-json' (<code>MatsSerializerJson</code>) employs the Jackson JSON library
 * to serialize to JSON, both for the "inner" DTO-and-STO part, and for the "outer" MatsTrace part.
 * <p>
 * It is worth pointing out that <i>all</i> the communicating parties needs to be using the same serialization
 * mechanism, as this constitute the "wire-representation" of the protocol that {@link MatsTrace} represents. There is
 * however a mechanism to handle different serializations, by means of a {@link #handlesMeta(String) metadata
 * construct}: Along with the serialized bytes, a metadata String must be provided. It is thus possible to construct a
 * MatsSerializer that holds multiple underlying MatsSerializers, choosing based on the "meta" String. This can then be
 * used to upgrade from a format to another, see later chapter.
 * <p>
 * Serialization and deserialization of the MatsTrace is quite obviously a performance critical part of the Mats
 * implementation.
 * <p>
 * <b>Two serialization mechanisms:</b> The MatsSerializer actually defines two separate serialization mechanisms: One
 * for the DTOs and STOs, and one for the MatsTrace which contains the serialized versions of the DTOs and STOs. These
 * two mechanisms are in principle separate, and one could imagine that one would want to use different serialization
 * mechanisms for these two - but the MatsTrace/MatsSerializer mechanism have the implementation of both in the same
 * construct - and the combination is identified by the "meta" described in the next paragraph. The default
 * implementation uses the same serialization mechanism for both (Jackson).
 * <p>
 * <b>Meta:</b> There's a concept of "meta", and the method {@link #handlesMeta(String)}. The "meta" is a String that is
 * provided along with the serialized bytes, and which is needed back when deserializing. It has two functions: Identify
 * the MatsSerializer, and provide meta information to that serializer - currently whether compression was employed, and
 * the size of the uncompressed data. One can make a "super-MatsSerializer" that wraps multiple MatsSerializers, and
 * which chooses the correct for deserialization one based on providing the meta to each of the contained
 * MatsSerializers <code>handlesMeta(..)</code> and see which returns true. For serialization, this super-MatsSerializer
 * will be coded or configured to use one specific of the contained MatsSerializers. This can be used to upgrade the
 * serialization format in a two-step fashion: First make a revision-change that includes the new serializer version,
 * but still employs the old for serialization. Then, when all parties are upgraded to the new config, you make a new
 * revision or minor change that changes the config to employ the new serializer for serialization. Then, when all
 * parties are up on this version, you can optionally make a third version that removes the old serializer. The "meta"
 * is also needed to deserialize the DTOs and STOs - it can then be gotten from
 * {@link MatsTrace#getMatsSerializerMeta()}, since the MatsSerializer sets this transient field upon construction of a
 * new MatsTrace, and when deserializing a MatsTrace.
 *
 * @author Endre Stølsvik - 2015-07-22 - http://endre.stolsvik.com
 */
public interface MatsSerializer {
    /**
     * Whether this implementation of MatsSerializer handles the specified {@link SerializedMatsTrace#getMeta() "meta"}.
     * <p>
     * This feature can at some point be used to configure up a bunch of serializers, whereby the one that handles the
     * incoming format gets the job to deserialize it into a MatsTrace. One can then also migrate to a newer version in
     * a two (three)-step fashion: First make a revision-change that includes the new serializer version, but still
     * employs the old for serialization. Then, when all parties are upgraded to the new config, you make a new revision
     * or minor change that changes the config to employ the new serializer for serialization. Then, when all parties
     * are up on this version, you can potentially make a third version that removes the old serializer.
     */
    default boolean handlesMeta(String meta) {
        return false;
    }

    /**
     * Used when initiating a new Mats flow. Since the {@link MatsTrace} implementation is dependent on the
     * serialization mechanism in use, we need a way provided by the serializer to instantiate new instances of the
     * implementation of MatsTrace. A {@link Call} must be added before it is good to be sent.
     *
     * @param traceId
     *            the Trace Id of this new {@link MatsTrace}.
     * @param flowId
     *            System-defined id for this call flow - guaranteed unique.
     * @param keepMatsTrace
     *            to which extent the MatsTrace should "keep trace", i.e. whether all Calls and States should be kept
     *            through the entire flow from initiation to terminator - default shall be
     *            {@link KeepMatsTrace#COMPACT}. The only reason for why this exists is for debugging: The
     *            implementation cannot depend on this feature. To see the call history, do a toString() on the
     *            ProcessContext of the lambda, which should perform a toString() on the corresponding MatsTrace, which
     *            should have a human readable trace output.
     * @param nonPersistent
     *            whether the message should be JMS-style "non-persistent" - default shall be <code>false</code>, i.e.
     *            the default is that a message is persistent.
     * @param interactive
     *            whether the message should be prioritized in that a human is actively waiting for the reply, default
     *            shall be <code>false</code>.
     * @param ttlMillis
     *            the number of milliseconds the message should live before being time out. 0 means "forever", and is
     *            the default.
     * @param noAudit
     *            hint to the underlying implementation, or to any monitoring/auditing tooling on the Message Broker,
     *            that it does not make much value in auditing this message flow, typically because it is just a
     *            "getter" of information to show to some user, or a health-check validating that some service is up and
     *            answers in a timely fashion.
     * @return a new instance of the underlying {@link MatsTrace} implementation.
     */
    MatsTrace createNewMatsTrace(String traceId, String flowId,
            KeepMatsTrace keepMatsTrace, boolean nonPersistent, boolean interactive, long ttlMillis, boolean noAudit);

    /**
     * The key postfix that should be used for the "meta" key on which the {@link SerializedMatsTrace#getMeta() meta}
     * value from {@link #serializeMatsTrace(MatsTrace)} should be stored. The meta value needs to be provided back when
     * invoking {@link #deserializeMatsTrace(byte[], String)}.
     */
    String META_KEY_POSTFIX = ":meta";

    /**
     * Used for serializing the {@link MatsTrace} to a byte array (contained within a {@link SerializedMatsTrace}),
     * providing some timing and size information, as well as the important "meta" information - which must be provided
     * back upon {@link #deserializeMatsTrace(byte[], String) deserialization}.
     *
     * @param matsTrace
     *            the {@link MatsTrace} instance to serialize.
     * @return a byte array representation of the provided {@link MatsTrace}.
     * @see #META_KEY_POSTFIX
     */
    SerializedMatsTrace serializeMatsTrace(MatsTrace matsTrace);

    interface SerializedMatsTrace {
        /**
         * @return the serialized-to-bytes {@link MatsTrace} - which probably also are compressed. Along with these
         *         bytes, you need to supply back the {@link #getMeta() meta} information when invoking
         *         {@link #deserializeMatsTrace(byte[], String)}.
         */
        byte[] getMatsTraceBytes();

        /**
         * @return the "meta" information about this serialization (think "envelope" in network protocol terms) -
         *         currently describes which compression algorithm is in use, or if it is uncompressed. Needs to be
         *         provided back to the {@link #deserializeMatsTrace(byte[], String) deserialization} method.
         * @see #META_KEY_POSTFIX
         */
        String getMeta();

        /**
         * @return the number of bytes the trace became <i>before</i> compression. (The number after compression is just
         *         to do '.length' on {@link #getMatsTraceBytes() the bytes}.
         */
        int getSizeUncompressed();

        /**
         * @return how long time the serialization process took, in milliseconds.
         */
        long getNanosSerialization();

        /**
         * @return <code>getMatsTraceBytes().length</code>
         */
        default int getSizeCompressed() {
            return getMatsTraceBytes().length;
        }

        /**
         * @return how long time the (optional) compression process took, in milliseconds - will be 0 if no compression
         *         took place.
         */
        long getNanosCompression();
    }

    /**
     * Used for deserializing a byte array into a {@link MatsTrace} - this includes offset and length.
     *
     * @param serialized
     *            the byte array from which to reconstitute the {@link MatsTrace}.
     * @param offset
     *            from where to start in the byte array.
     * @param len
     *            how many bytes to use of the byte array, from the offset.
     * @param meta
     *            some meta information that the deserialized needs back {@link SerializedMatsTrace#getMeta() from the
     *            serialization process}.
     * @return the reconstituted {@link MatsTrace}.
     * @see #META_KEY_POSTFIX
     */
    DeserializedMatsTrace deserializeMatsTrace(byte[] serialized, int offset, int len, String meta);

    /**
     * Used for deserializing a byte array into a {@link MatsTrace} - this uses the entire byte array.
     *
     * @param serialized
     *            the byte array from which to reconstitute the {@link MatsTrace}.
     * @param meta
     *            some meta information that the deserialized needs back {@link SerializedMatsTrace#getMeta() from the
     *            serialization process}.
     * @return the reconstituted {@link MatsTrace}.
     * @see #META_KEY_POSTFIX
     */
    DeserializedMatsTrace deserializeMatsTrace(byte[] serialized, String meta);

    interface DeserializedMatsTrace {
        /**
         * @return the deserialized {@link MatsTrace}.
         */
        MatsTrace getMatsTrace();

        /**
         * @return the number of bytes the (potentically compressed) trace was, i.e. the length of the 'serialized' byte
         *         array to {@link #deserializeMatsTrace(byte[], String)}.
         */
        int getSizeIncoming();

        /**
         * @return the number of bytes the trace became after decompression, before deserialization.
         */
        int getSizeDecompressed();

        /**
         * @return how long time the (optional) decompression process took, in nanoseconds - will be 0 if no
         *         decompression took place.
         */
        long getNanosDecompression();

        /**
         * @return how long time the deserialization process took, in nanoseconds.
         */
        long getNanosDeserialization();
    }

    /**
     * Used for serializing STOs and DTOs into the serialized type, typically {@link String}.
     * <p>
     * If <code>null</code> is provided as the Object parameter, then <code>null</code> shall be returned.
     *
     * @param object
     *            the object to serialize. If <code>null</code> is provided, then <code>null</code> shall be returned.
     * @return the serialized representation of the provided object, or <code>null</code> if null was provided as
     *         'object'.
     */
    Object serializeObject(Object object);

    /**
     * @param serialized
     *            the serialized representation of the object, if <code>null</code> is provided, then 0 shall be
     *            returned.
     * @param meta
     *            meta information that the deserializer might need back to choose between sub-MatsSerializers,
     *            originally at {@link SerializedMatsTrace#getMeta() from the serialization process}, but which also is
     *            present at {@link MatsTrace#getMatsSerializerMeta()}.
     * @return the size in bytes or characters of the serialized DTO or STO, shall return 0 for <code>null</code>. This
     *         is meant for metrics, NOT for determining an absolute byte size for a storage array or anything to this
     *         effect.
     */
    int sizeOfSerialized(Object serialized, String meta);

    /**
     * Used for deserializing the value (typically {@link String}) to STOs and DTOs.
     * <p>
     * If <code>null</code> is provided as the 'serialized' parameter, then <code>null</code> shall be returned.
     *
     * @param serialized
     *            the value of type T that should be deserialized into an object of Class T. If <code>null</code> is
     *            provided, then <code>null</code> shall be returned.
     * @param type
     *            the Class that the supplied value of type Z is thought to represent (i.e. the STO or DTO class).
     * @param meta
     *            meta information that the deserializer might need back to choose between sub-MatsSerializers,
     *            originally at {@link SerializedMatsTrace#getMeta() from the serialization process}, but which also is
     *            present at {@link MatsTrace#getMatsSerializerMeta()}.
     * @return the reconstituted Object (STO or DTO), or <code>null</code> if null was provided as 'serialized'.
     */
    <T> T deserializeObject(Object serialized, Class<T> type, String meta);

    /**
     * Will return a new instance of the requested type. This is used to instantiate "empty objects" for Endpoint State
     * objects (STOs), and to test, at Endpoint registration, whether it is possible to get hold of instances of the
     * Request and Reply DTO for an Endpoint (to ensure that it will be possible in runtime).
     * <p/>
     * The reason for having this in the MatsSerializer is that it is somewhat dependent on the object serializer in
     * use: GSON allows to instantiate private, missing-no-args-constructor classes, while Jackson does not.
     *
     * @param type
     *            Which class you want an object of.
     * @param <T>
     *            the type of that class.
     * @return an "empty" new instance of the class.
     */
    <T> T newInstance(Class<T> type);

    /**
     * The methods in this interface shall throw this RuntimeException if they encounter problems.
     */
    class SerializationException extends RuntimeException {
        public SerializationException(String message) {
            super(message);
        }

        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
