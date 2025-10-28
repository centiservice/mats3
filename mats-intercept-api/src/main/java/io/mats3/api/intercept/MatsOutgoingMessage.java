/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.api.intercept;

import java.util.Optional;
import java.util.Set;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.MatsStageInterceptor.StageCommonContext;

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
     * "initiated from", and "from", the same source.
     *
     * @return the value supplied to {@link MatsInitiate#from(String)} at the time of the Mats flow initiation.
     */
    String getInitiatorId();

    // ===== Message Ids and properties

    String getMatsMessageId();

    MessageType getMessageType();

    DispatchType getDispatchType();

    // ===== Basics

    /**
     * Note: For messages out of an initiator, this method and {@link #getInitiatorId()} returns the same value, i.e. it
     * is "initiated from", and "from", the same source.
     *
     * @return which stage the message is from, or for initiations, the same value as {@link #getInitiatorId()}.
     */
    String getFrom();

    String getTo();

    boolean isToSubscription();

    Optional<String> getReplyTo();

    Optional<Boolean> isReplyToSubscription();

    /**
     * @return the "same stackheight" outgoing state; If this is a Request, it is the state that will be present upon
     *         the subsequent Reply. If this is a Next, it is the state present on the nest stage. If it is a Goto, it
     *         will be the "intialState" if set - and thus same as {@link #getInitialTargetState()}.
     */
    Optional<Object> getSameStackHeightState();

    /**
     * @return the outgoing data (DTO)
     */
    Object getData();

    // ===== "Sideloads" and trace props

    Set<String> getTracePropertyKeys();

    <T> T getTraceProperty(String propertyName, Class<T> type);

    Set<String> getBytesKeys();

    byte[] getBytes(String key);

    Set<String> getStringKeys();

    String getString(String key);

    /**
     * @return for initiations and Goto, it is possible to send along an initial <i>incoming</i> target state - this
     *         returns that.
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
         *
         * @see #REPLY_SUBSCRIPTION
         */
        REPLY,

        /**
         * "Specialization" of {@link #REPLY} for when the Initiation was done using
         * {@link MatsInitiate#replyToSubscription(String, Object) MatsInitiate#replyToSubscription(..)}
         * <p>
         * Only for {@link DispatchType#STAGE}.
         *
         * @see #REPLY
         */
        REPLY_SUBSCRIPTION,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        NEXT,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        NEXT_DIRECT,

        /**
         * Only for {@link DispatchType#STAGE}.
         */
        GOTO
    }

    enum DispatchType {
        INIT, STAGE, STAGE_INIT
    }

    interface MatsEditableOutgoingMessage extends MatsOutgoingMessage {
        /**
         * Set trace property.
         */
        void setTraceProperty(String propertyName, Object object);

        /**
         * An interceptor might need to add state to an outgoing message which will be present on incoming message of
         * the next stage of a multi-stage endpoint - i.e. "extra state" in addition to the State class that the user
         * has specified for the Endpoint.
         * <p>
         * Only relevant for {@link MessageType#REQUEST}, {@link MessageType#NEXT} and {@link MessageType#GOTO} - will
         * throw {@link IllegalStateException} otherwise.
         * <p>
         * REQUEST: If extra-state is added to an outgoing REQUEST-message from ServiceA.stage1, it will be present
         * again on the subsequent REPLY-message to ServiceA.stage2 (and any subsequent stages).
         * <p>
         * NEXT: If extra-state is added to an outgoing NEXT-message from ServiceA.stage1, it will be present again on
         * the subsequent incoming message to ServiceA.stage2 (and any subsequent stages).
         * <p>
         * To add info to the receiving stage of a REQUEST, you may employ {@link #addBytes(String, byte[])} and
         * {@link #addString(String, String)}. Given that such extra-state would need support from the receiving
         * endpoint too to make much sense (i.e. an installed interceptor reading and understanding the incoming extra
         * state, typically the same interceptor installed there as the one adding the state), it could just pick up the
         * side load and transfer it over to the extra state if that was desired. Therefore, even though it could be
         * possible to add extra-state to the <i>targeted/receiving endpoint</i>, I've decided against it so far.
         * <p>
         * The extra-state is available on the stage interception at
         * {@link StageCommonContext#getIncomingSameStackHeightExtraState(String, Class)}.
         */
        void setSameStackHeightExtraState(String key, Object object);

        /**
         * Add byte[] sideload to outgoing message.
         */
        void addBytes(String key, byte[] payload);

        /**
         * Add String sideload to outgoing message.
         */
        void addString(String key, String payload);
    }

    interface MatsSentOutgoingMessage extends MatsOutgoingMessage {
        // ===== MessageIds

        String getSystemMessageId();

        // NOTE: Technically, currently EnvelopeProduceNanos and DataSerializedSize is available before
        // the "sent" point, and could be present on the MatsOutgoingMessage interface.

        // ===== Serialization metrics

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
         *         applied, while it will return &gt; 0 if compression was applied.
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

        // ===== Other metrics

        /**
         * @return the number of bytes sent on the wire (best approximation), as far as Mats knows. Any overhead from
         *         the message system is unknown. Includes the envelope (which includes the TraceProperties), as well as
         *         the sideloads (bytes and strings). Implementation might add some more size for metadata. Notice that
         *         the strings are just "length()'ed", so any "exotic" characters are still just counted as 1 byte.
         */
        default int getMessageSystemTotalWireSize() {
            // :: Calculate the total wiresize, as far as we can figure out with info we have here.
            // Start with the envelope wire size
            int totalWireSize = this.getEnvelopeWireSize();
            // .. add the byte sideloads
            for (String bytesKey : this.getBytesKeys()) {
                totalWireSize += this.getBytes(bytesKey).length;
            }
            // .. add the string sideloads
            // Notice: This isn't exact if Strings contains "exotic" chars (non-ASCII).
            for (String stringKey : this.getStringKeys()) {
                totalWireSize += this.getString(stringKey).length();
            }
            return totalWireSize;
        }

        /**
         * @return the number of units (bytes or characters) that the outgoing data (DTO) serialized to.
         */
        int getDataSerializedSize();
    }
}
