package io.mats3.api.intercept;

import java.util.Optional;
import java.util.Set;

import io.mats3.MatsEndpoint.ProcessContext;

/**
 * Represents an Outgoing Mats Message.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface MatsOutgoingMessage {

    // ===== Flow Ids and Flow properties

    String getTraceId();

    String getFlowId();

    boolean isNonPersistent();

    long getTimeToLive();

    boolean isInteractive();

    boolean isNoAudit();

    String getInitiatingAppName();

    String getInitiatingAppVersion();

    /**
     * Note: For messages out of an initiator, this method and {@link #getFrom()} returns the same value, i.e. it is
     * initiated from, and from, the same source.
     *
     * @return the value supplied to {@link io.mats3.MatsInitiator.MatsInitiate#from(String)} at the time of the Mats
     *         flow initiation.
     */
    String getInitiatorId();

    // ===== Message Ids and properties

    String getMatsMessageId();

    MessageType getMessageType();

    DispatchType getDispatchType();

    // ===== Basics

    /**
     * Note: For messages out of an initiator, this method and {@link #getInitiatorId()} returns the same value, i.e. it
     * is initiated from, and from, the same source.
     *
     * @return which stage the message is from, or for initiations, the same value as {@link #getInitiatorId()}.
     */
    String getFrom();

    String getTo();

    boolean isToSubscription();

    Optional<String> getReplyTo();

    Optional<Boolean> isReplyToSubscription();

    Optional<Object> getReplyToState();

    Object getMessage();

    // ===== Extra-state, "Sideloads" and trace props

    Set<String> getTracePropertyKeys();

    <T> T getTraceProperty(String propertyName, Class<T> type);

    Set<String> getBytesKeys();

    byte[] getBytes(String key);

    Set<String> getStringKeys();

    String getString(String key);

    /**
     * @return for initiations, it is possible, albeit should be uncommon, to send along an initial <i>incoming</i>
     *         target state - this returns that.
     */
    Optional<Object> getInitialTargetState();

    enum MessageType {
        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}.
         */
        SEND,

        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}.
         */
        PUBLISH,

        /**
         * For {@link DispatchType#INIT} or {@link DispatchType#STAGE_INIT}; and for {@link DispatchType#STAGE}.
         */
        REQUEST,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        REPLY,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        NEXT,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        GOTO
    }

    enum DispatchType {
        INIT, STAGE, STAGE_INIT
    }

    interface MatsEditableOutgoingMessage extends MatsOutgoingMessage {
        void setTraceProperty(String propertyName, Object object);

        /**
         * Only relevant for {@link MessageType#REQUEST}s - will throw IllegalStateException otherwise. Adds extra-state
         * to the state which will be present on the REPLY for the outgoing REQUEST: If this extra-state is added to a
         * REQUEST-message from ServiceA.stage1, it will be present again on the subsequent REPLY-message to
         * ServiceA.stage2.
         * <p />
         * To add info to the receiving stage, you may employ {@link #addBytes(String, byte[])} and
         * {@link #addString(String, String)}. Given that such extra-state would need support from the receiving
         * endpoint too to make much sense (i.e. an installed interceptor reading and understanding the incoming extra
         * state, typically the same interceptor installed there as the one adding the state), it could just pick up the
         * side load and transfer it over to the extra state if that was desired. Therefore, even though it could be
         * possible to add extra-state to the <i>targeted/receiving endpoint</i>, I've decided against it so far.
         */
        void setExtraStateForReplyOrNext(String key, Object object);

        void addBytes(String key, byte[] payload);

        void addString(String key, String payload);
    }

    interface MatsSentOutgoingMessage extends MatsOutgoingMessage {
        // ===== MessageIds

        String getSystemMessageId();

        // ===== Serialization stats

        /**
         * @return time taken (in nanoseconds) to create the Mats envelope, including serialization of all relevant
         *         constituents: DTO, STO and any Trace Properties. <b>Note that this will be a slice of the user lambda
         *         timing ({@link CommonCompletedContext#getUserLambdaNanos() getUserLambdaNanos()} for both init and
         *         stage), as it is done at the time of e.g. invoking {@link ProcessContext#request(String, Object)
         *         processContext.request()} inside the user lambda.</b>
         */
        long getEnvelopeProduceNanos();

        /**
         * @return time taken (in nanoseconds) to serialize the Mats envelope.
         */
        long getEnvelopeSerializationNanos();

        /**
         * @return size (in bytes) of the serialized envelope - before compression. Do read the JavaDoc of
         *         {@link #getEnvelopeWireSize()} too, as the same applies here: This size only refers to the Mats
         *         envelope, not the messaging system's final message size.
         */
        int getEnvelopeSerializedSize();

        /**
         * @return time taken (in nanoseconds) to compress the envelope - will be <code>0</code> if no compression was
         *         applied, while it will return > 0 if compression was applied.
         */
        long getEnvelopeCompressionNanos();

        /**
         * @return size (in bytes) of the envelope after compression, which will be put inside the messaging system's
         *         message envelope. If compression was not applied, returns the same value as
         *         {@link #getEnvelopeSerializedSize()}. Note that the returned size is only the (compressed) Mats
         *         envelope, and does not include the size of the messaging system's message/envelope and any meta-data
         *         that Mats adds to this. This means that the message size on the wire will be larger.
         */
        int getEnvelopeWireSize();

        /**
         * @return time taken (in nanoseconds) to produce, and then send (transfer) the message to the message broker.
         */
        long getMessageSystemProduceAndSendNanos();
    }
}
