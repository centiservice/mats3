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
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsOutgoingMessage.MessageType;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext.ProcessResult;

/**
 * <b>EXPERIMENTAL!!</b> (Will probably change. Implementation started 2020-01-08)
 * <p />
 * Meant for intercepting stage processors with ability to modify the stage processing, and implement extra logging and
 * metrics gathering.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface MatsStageInterceptor {

    /**
     * Invoked if any of the preprocessing and deserialization activities on the incoming message from the message
     * system fails, and hence no Stage processing will be performed - i.e., no further methods of the interceptor will
     * be invoked. Conversely, this method will not be invoked in normal situations: If the normal user lambda throws,
     * or database or messaging system fails, this will result in the {@link StageCompletedContext} of the
     * {@link #stageCompleted(StageCompletedContext) stageCompleted(..)} call describe the problem and contain the
     * cause, see {@link ProcessResult#USER_EXCEPTION} and {@link ProcessResult#SYSTEM_EXCEPTION}.
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
     */
    default void stageCompleted(StageCompletedContext context) {
        /* no-op */
    }

    interface StageInterceptContext {
        MatsStage<?, ?, ?> getStage();

        Instant getStartedInstant();

        long getStartedNanoTime();
    }

    interface StagePreprocessAndDeserializeErrorContext extends StageInterceptContext {
        ReceiveDeconstructError getReceiveDeconstructError();

        Optional<Throwable> getThrowable();

        enum ReceiveDeconstructError {
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
     * <b>Remember: There are also lots of properties on the ProcessContext!</b>
     */
    interface StageCommonContext extends StageInterceptContext {
        /**
         * @return the {@link MessageType} of the incoming message.
         */
        MessageType getIncomingMessageType();

        /**
         * @return the incoming message.
         */
        Object getIncomingMessage();

        /**
         * @return the incoming state, if present (which it typically is for all stages except initial - albeit it is
         *         also possible to send state to the initial stage).
         */
        Optional<Object> getIncomingState();

        /**
         * @return the extra-state, if any, on the incoming message.
         */
        <T> Optional<T> getIncomingExtraState(String key, Class<T> type);

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
         *         <code>0</code> if no compression was applied, while it will return > 0 if compression was applied.
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
         * @return time taken (in nanoseconds) to deserialize the message (DTO) and state (STO) from the envelope,
         *         before invoking the user lambda with them.
         */
        long getMessageAndStateDeserializationNanos();
    }

    interface StageReceivedContext extends StageCommonContext {
        ProcessContext<Object> getProcessContext();
    }

    interface StageInterceptUserLambdaContext extends StageCommonContext {
        ProcessContext<Object> getProcessContext();
    }

    interface StageInterceptOutgoingMessageContext extends StageCommonContext, CommonInterceptOutgoingMessagesContext {
        ProcessContext<Object> getProcessContext();
    }

    interface StageCompletedContext extends StageCommonContext, CommonCompletedContext {
        DetachedProcessContext getProcessContext();

        /**
         * @return The type of the main result of the Stage Processing - <b>which do not include any stage-initiations,
         *         look at {@link #getStageInitiatedMessages()} for that.</b>
         */
        ProcessResult getProcessResult();

        /**
         * The main result of the Stage Processing - <b>which do not include any initiation, look at
         * {@link #getStageInitiatedMessages()} for that.</b>
         */
        enum ProcessResult {
            REQUEST,

            REPLY,

            NEXT,

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
             * the "VERY BAD!" scenario where db is committed, whereupon the messaging commit failed - which most
             * probably is a "notify the humans!"-situation, unless the user code is crafted very specifically to handle
             * such a situation).
             */
            SYSTEM_EXCEPTION
        }

        /**
         * @return the Reply, Next or Goto outgoing message, it this was the {@link ProcessResult ProcessingResult}.
         *         Otherwise, <code>Optional.empty()</code>. The message will be of {@link DispatchType#STAGE
         *         ProcessType.STAGE}.
         */
        Optional<MatsSentOutgoingMessage> getStageResultMessage();

        /**
         * @return the outgoing Requests, if this was the {@link ProcessResult}. Otherwise, an empty list. The messages
         *         will be of {@link DispatchType#STAGE ProcessType.STAGE}.
         */
        List<MatsSentOutgoingMessage> getStageRequestMessages();

        /**
         * @return all stage-initiated messages, which is of {@link DispatchType#STAGE_INIT ProcessType.STAGE_INIT}.
         */
        List<MatsSentOutgoingMessage> getStageInitiatedMessages();
    }
}
