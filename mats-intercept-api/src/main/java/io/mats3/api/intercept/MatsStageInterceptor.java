package io.mats3.api.intercept;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.mats3.MatsEndpoint.DetachedProcessContext;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsStage;
import io.mats3.api.intercept.MatsOutgoingMessage.DispatchType;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.StageProcessResult;

/**
 * <b>Implement this interface to intercept Stage Processing</b>, then register with
 * {@link MatsInterceptable#addStageInterceptor(MatsStageInterceptor)}.
 * <p />
 * Intercepts stage processors with ability to modify the stage processing, and implement extra logging and metrics
 * gathering.
 *
 * @author Endre Stølsvik - 2020-01-08 - http://endre.stolsvik.com
 */
public interface MatsStageInterceptor {

    /**
     * Invoked if any of the preprocessing and deserialization activities on the incoming message from the message
     * system fails, and hence no Stage processing will be performed - i.e., no further methods of the interceptor will
     * be invoked. Conversely, this method will not be invoked in normal situations: If the normal user lambda throws,
     * or database or messaging system fails, this will result in the {@link StageCompletedContext} of the
     * {@link #stageCompleted(StageCompletedContext) stageCompleted(..)} call describe the problem and contain the
     * cause, see {@link StageProcessResult#USER_EXCEPTION} and {@link StageProcessResult#SYSTEM_EXCEPTION}.
     * <p />
     * <b>Note: This should really never be triggered. It would be prudent to "notify the humans" if it ever did.</b>
     */
    default void stagePreprocessAndDeserializeError(StagePreprocessAndDeserializeErrorContext context) {
        /* no-op */
    }

    /**
     * Invoked right after message have been received, preprocessed and deserialized, before invoking the user lambda.
     * (If any error occurs during preprocess or deserialization, then
     * {@link #stagePreprocessAndDeserializeError(StagePreprocessAndDeserializeErrorContext)} is invoked instead).
     */
    default void stageReceived(StageReceivedContext context) {
        /* no-op */
    }

    /**
     * Enables the intercepting of the invocation of the "user lambda" in a Stage, with ability to wrap the
     * {@link ProcessContext} (and thus modify any reply, request, next or initiations) and/or modifying state and
     * message - or even take over the entire stage. Wrt. changing messages, you should also consider
     * {@link MatsStageInterceptOutgoingMessages#stageInterceptOutgoingMessages(StageInterceptOutgoingMessageContext)}.
     * <p />
     * Default implementation is to call directly through - and if overriding, you also need to call through to get the
     * actual stage to execute.
     * <p />
     * Pulled out in separate interface, so that we don't need to invoke it if the interceptor doesn't need it.
     */
    interface MatsStageInterceptUserLambda {
        default void stageInterceptUserLambda(StageInterceptUserLambdaContext context,
                ProcessLambda<Object, Object, Object> processLambda,
                ProcessContext<Object> ctx, Object state, Object msg)
                throws MatsRefuseMessageException {
            // Default: Call directly through
            processLambda.process(ctx, state, msg);
        }
    }

    /**
     * While still within the stage process context, this interception enables modifying outgoing messages from the user
     * lambda, setting trace properties, adding "sideloads", deleting a message, or initiating additional messages.
     * <p />
     * Pulled out in separate interface, so that we don't need to invoke it if the interceptor doesn't need it.
     */
    interface MatsStageInterceptOutgoingMessages extends MatsStageInterceptor {
        void stageInterceptOutgoingMessages(StageInterceptOutgoingMessageContext context);
    }

    /**
     * Invoked <i>after</i> the stage is fully completed, outgoing messages sent, db and messaging system committed. You
     * cannot anymore modify any outgoing messages etc - for this, implement {@link MatsStageInterceptOutgoingMessages}.
     *
     * @see #stageCompletedNextDirect(StageCompletedContext)
     */
    default void stageCompleted(StageCompletedContext context) {
        /* no-op */
    }

    /**
     * Special variant of {@link #stageCompleted(StageCompletedContext)} which is invoked when a
     * {@link ProcessContext#nextDirect(Object)} has been performed: As opposed to the ordinary variant, this does
     * <i>not</i> execute outside the stage's transactional demarcation, since the nextDirect stage processing happens
     * within the same transactional demarcation as the stage which invoked nextDirect. Therefore, no outgoing messages
     * has yet been sent, and db and message system are not yet committed.
     */
    default void stageCompletedNextDirect(StageCompletedContext context) {
        stageCompleted(context);
    }

    interface StageInterceptContext {
        /**
         * @return the {@link MatsStage} in question.
         */
        MatsStage<?, ?, ?> getStage();

        /**
         * @return when the message was received, as {@link Instant#now()}, as early as possible right after the message
         *         system returns it (before decoding it etc).
         */
        Instant getStartedInstant();

        /**
         * @return when the message was received, as {@link System#nanoTime()}, as early as possible right after the
         *         message system returns it (before decoding it etc).
         */
        long getStartedNanoTime();
    }

    interface StagePreprocessAndDeserializeErrorContext extends StageInterceptContext {
        StagePreprocessAndDeserializeError getStagePreprocessAndDeserializeError();

        Optional<Throwable> getThrowable();

        enum StagePreprocessAndDeserializeError {
            /**
             * If the incoming message is not of the message system's expected type (for JMS, it should be MapMessage)
             */
            WRONG_MESSAGE_TYPE,

            /**
             * If there is missing required contents in the message system message (i.e. MatsTrace keys give null
             * values).
             */
            MISSING_CONTENTS,

            /**
             * If getting problems deserializing the message, or getting hold of other properties from the message.
             */
            DECONSTRUCT_ERROR,

            /**
             * If the incoming Mats message is not intended for this stage.
             */
            WRONG_STAGE

            // TODO: Here we should add some "SIGNATURE_VALIDATION_ERROR"
        }
    }

    /**
     * Common context elements for stage interception, including all the incoming message/envelope data and metadata.
     * <p/>
     * <b>Remember: There are also lots of properties on the ProcessContext!</b>
     */
    interface StageCommonContext extends StageInterceptContext {
        /**
         * Set an attribute on this particular interception, which is shared between the different stages of
         * interception, and also between all interceptors - use some namespacing to avoid accidental collisions. Use
         * instead of ThreadLocals.
         *
         * @param key
         *            the key name for this intercept context attribute.
         * @param value
         *            the value which should be held.
         */
        void putInterceptContextAttribute(String key, Object value);

        /**
         * @param key
         *            the key name for this intercept context attribute.
         * @param <T>
         *            the type of this attribute, to avoid explicit casting - but you should really know the type,
         *            otherwise request <code>Object</code>.
         * @return the intercept context attribute that was stored by
         *         {@link #putInterceptContextAttribute(String, Object)}, <code>null</code> if there is no such value.
         */
        <T> T getInterceptContextAttribute(String key);

        /**
         * @return the {@link DetachedProcessContext} for the executing stage - this is overridden in several sub
         *         interfaces to return the actual "live" {@link ProcessContext}.
         */
        DetachedProcessContext getProcessContext();

        /**
         * Returns the timestamp when the initial stage of the Endpoint which this Stage belongs to, was entered. Use to
         * calculate <i>total endpoint time</i>: If the result of a stage is {@link StageProcessResult#REPLY} or
         * {@link StageProcessResult#NONE}, then the endpoint is finished, and the current time vs. endpoint entered
         * timestamp (this method) is the total time this endpoint used.
         * <p/>
         * <b>Note that this is susceptible to time skews between nodes: If the initial stage was run on node A, while
         * this stage is run on node B, calculations on the timestamp returned from this method (from node A) vs. this
         * node (B's) {@link System#currentTimeMillis()} is highly dependent on the time synchronization between node A
         * and node B.</b>
         *
         * @see #getPrecedingSameStackHeightOutgoingTimestamp()
         * @see ProcessContext#getFromTimestamp()
         * @return the timestamp when the initial stage of the Endpoint which this Stage belongs to, was entered.
         */
        Instant getEndpointEnteredTimestamp();

        /**
         * Returns the timestamp of the preceding outgoing message <i>on the same stack height</i> as this stage. Use to
         * calculate "Time since previous Stage of same Endpoint", which includes queue times and processing times of
         * requested endpoints happening in between the send and the receive, as well as any other latencies. For
         * example, it is the time between when EndpointA.Stage2 performs a REQUEST to AnotherEndpointB, till the REPLY
         * from that endpoint is received on EndpointA.Stage3 (There can potentially be dozens of message passing and
         * processings in between those two stages (of the same endpoint), as AnotherEndpointB might itself have a dozen
         * stages, each performing requests to yet other endpoints).
         * <p/>
         * <b>Note that this is susceptible to time skews between nodes: If the preceding stage was run on node A, while
         * this stage is run on node B, calculations on the timestamp returned from this method (from node A) vs. this
         * node (B's) {@link System#currentTimeMillis()} is highly dependent on the time synchronization between node A
         * and node B.</b>
         * <p/>
         * Note: There is no preceding stage for a REQUEST to the Initial Stage of an Endpoint.
         * <p/>
         * Note: On a Terminator whose corresponding initiation did a REQUEST, the "same stack height" ends up being the
         * initiator (it is stack height 0). If an initiation goes directly to a Terminator (e.g. "fire-and-forget"
         * PUBLISH or SEND), again the "same stack height" is the initiator.
         *
         * @see #getEndpointEnteredTimestamp()
         * @see ProcessContext#getFromTimestamp()
         * @return The timestamp of the outgoing message <i>on the same stack height</i> as this stage. If the current
         *         Call is a REQUEST, there is no such message, and <code>-1</code> is returned.
         */
        Instant getPrecedingSameStackHeightOutgoingTimestamp();

        /**
         * @return the {@link MessageType} of the incoming message.
         */
        MessageType getIncomingMessageType();

        /**
         * @return the incoming state (STO), if present (which it typically is for all stages except initial - albeit it
         *         is also possible to send state to the initial stage).
         */
        Optional<Object> getIncomingState();

        /**
         * @return the number of units (bytes or characters) that the state (STO) serialized to. If
         *         {@link #getIncomingState()} is <code>Optional.empty()</code>, then this method returns 0.
         */
        int getStateSerializedSize();

        /**
         * @return the incoming data (DTO).
         */
        Object getIncomingData();

        /**
         * @return the number of units (bytes or characters) that the data (DTO) serialized to. (<code>Null</code> data
         *         might give 0 or 4, or really whatever else the serializer uses to represent <code>null</code>).
         */
        int getDataSerializedSize();

        /**
         * @return the extra-state, if any, on the incoming REPLY, NEXT or GOTO message - as set by
         *         {@link MatsEditableOutgoingMessage#setSameStackHeightExtraState(String, Object)}.
         */
        <T> Optional<T> getIncomingSameStackHeightExtraState(String key, Class<T> type);

        /**
         * @return the total time taken (in nanoseconds) from the reception of a message system message, via
         *         deconstruct; decompression; deserialization of envelope, message and state, to right before the
         *         invocation of the {@link MatsStageInterceptor#stageReceived(StageReceivedContext)}; approx. the sum
         *         of the timings below.
         */
        long getTotalPreprocessAndDeserializeNanos();

        /**
         * @return time taken (in nanoseconds) to pick the pieces out of the message system message.
         */
        long getMessageSystemDeconstructNanos();

        /**
         * @return size (in bytes) of the envelope "on the wire", which often is compressed, which is picked out of the
         *         message system message in {@link #getMessageSystemDeconstructNanos()}. If compression was not
         *         applied, returns the same value as {@link #getEnvelopeSerializedSize()}. Note that the returned size
         *         is only the (compressed) Mats envelope, and does not include the size of the messaging system's
         *         message/envelope and any meta-data that Mats adds to this. This means that the message size on the
         *         wire will be larger.
         */
        int getEnvelopeWireSize();

        /**
         * @return time taken (in nanoseconds) to decompress the "on the wire" representation of the envelope. - will be
         *         <code>0</code> if no compression was applied, while it will return &gt; 0 if compression was applied.
         */
        long getEnvelopeDecompressionNanos();

        /**
         * @return size (in bytes) of the serialized envelope - after decompression. Do read the JavaDoc of
         *         {@link #getEnvelopeWireSize()} too, as the same applies here: This size only refers to the Mats
         *         envelope, not the messaging system's final message size.
         */
        int getEnvelopeSerializedSize();

        /**
         * @return time taken (in nanoseconds) to deserialize the envelope.
         */
        long getEnvelopeDeserializationNanos();

        /**
         * @return time taken (in nanoseconds) to deserialize the data (DTO) and state (STO) from the envelope, before
         *         invoking the user lambda with them.
         */
        long getDataAndStateDeserializationNanos();

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
            for (String bytesKey : this.getProcessContext().getBytesKeys()) {
                totalWireSize += this.getProcessContext().getBytes(bytesKey).length;
            }
            // .. add the string sideloads
            // Notice: This isn't exact if Strings contains "exotic" chars (non-ASCII).
            for (String stringKey : this.getProcessContext().getStringKeys()) {
                totalWireSize += this.getProcessContext().getString(stringKey).length();
            }
            return totalWireSize;
        }
    }

    interface StageReceivedContext extends StageCommonContext {
        /**
         * @return the live {@link ProcessContext} for the executing stage.
         */
        ProcessContext<Object> getProcessContext();
    }

    interface StageInterceptUserLambdaContext extends StageCommonContext {
        /**
         * @return the live {@link ProcessContext} for the executing stage.
         */
        ProcessContext<Object> getProcessContext();
    }

    interface StageInterceptOutgoingMessageContext extends StageCommonContext, CommonInterceptOutgoingMessagesContext {
        /**
         * @return the live {@link ProcessContext} for the executing stage.
         */
        ProcessContext<Object> getProcessContext();
    }

    interface StageCompletedContext extends StageCommonContext, CommonCompletedContext {
        /**
         * @return The type of the main result of the Stage Processing - <b>which do not include any stage-initiations,
         *         look at {@link #getStageInitiatedMessages()} for that.</b>
         */
        StageProcessResult getStageProcessResult();

        /**
         * The main result of the Stage Processing - if the stage also initiated messages, this will be known by
         * {@link #getStageInitiatedMessages()} being non-empty.
         */
        enum StageProcessResult {
            REQUEST,

            REPLY,

            NEXT,

            NEXT_DIRECT,

            GOTO,

            /**
             * No standard processing result, which is default mode for a Terminator - <b>but note that
             * {@link #getStageInitiatedMessages() initiations} might have been produced nevertheless!</b>
             */
            NONE,

            /**
             * Any exception thrown in the user lambda, causing rollback of the processing. This may both be code
             * failures (e.g. {@link NullPointerException}, explicit validation failures (which probably should result
             * in {@link MatsRefuseMessageException}), and database access or other types of external communication
             * failures.
             */
            USER_EXCEPTION,

            /**
             * If the messaging or processing system failed, this will be either
             * {@link io.mats3.MatsInitiator.MatsBackendException MatsBackendException} (messaging handling or db
             * commit), or {@link io.mats3.MatsInitiator.MatsMessageSendException MatsMessageSendException} (which is
             * the "VERY BAD!" scenario where db is committed, whereupon the messaging commit failed - which quite
             * possibly is a "notify the humans!"-situation, unless the user code is crafted to handle such a
             * situation by being idempotent).
             */
            SYSTEM_EXCEPTION
        }

        /**
         * @return the Reply, Next or Goto outgoing message, it this was the {@link StageProcessResult ProcessingResult}.
         *         Otherwise, <code>Optional.empty()</code>. The message will be of {@link DispatchType#STAGE
         *         DispatchType.STAGE}.
         */
        Optional<MatsSentOutgoingMessage> getStageResultMessage();

        /**
         * @return the outgoing Requests, if this was the {@link StageProcessResult}. Otherwise, an empty list. The messages
         *         will be of {@link DispatchType#STAGE DispatchType.STAGE}.
         */
        List<MatsSentOutgoingMessage> getStageRequestMessages();

        /**
         * @return all stage-initiated messages, which are of {@link DispatchType#STAGE_INIT DispatchType.STAGE_INIT}.
         */
        List<MatsSentOutgoingMessage> getStageInitiatedMessages();
    }
}
