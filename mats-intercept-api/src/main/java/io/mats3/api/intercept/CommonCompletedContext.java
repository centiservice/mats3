package io.mats3.api.intercept;

import java.util.List;
import java.util.Optional;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateCompletedContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptUserLambda;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext;

/**
 * Common part of the interface of {@link InitiateCompletedContext} and {@link StageCompletedContext}.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface CommonCompletedContext {
    /**
     * @return time taken (in nanoseconds) to run the user supplied initiate lambda itself. If it includes database
     *         access (e.g. a SELECT and/or UPDATE), this time should probably be dominated by this. If there are
     *         {@link MatsInitiateInterceptUserLambda}s involved, the interceptor(s) might have added some overhead
     *         both by the interception, and by potentially having wrapped the {@link MatsInitiate} instance.
     */
    long getUserLambdaNanos();

    /**
     * @return time taken (in nanoseconds) to serialize the Mats envelope(s), should basically be the sum of any
     *         {@link #getOutgoingMessages() outgoing} messages'
     *         {@link MatsSentOutgoingMessage#getEnvelopeSerializationNanos()} plus
     *         {@link MatsSentOutgoingMessage#getEnvelopeCompressionNanos()}.
     */
    long getSumEnvelopeSerializationAndCompressionNanos();

    /**
     * @return time taken (in nanoseconds) to commit the database. Will be <code>0</code> if this {@link MatsFactory} is
     *         not configured with SQL transaction management, and will hopefully be very low if "SQL transaction
     *         elision" is in effect (i.e. that the initiate didn't perform any SQL), but never <code>0</code> - that
     *         is, you may use the 0 to check whether DB commit was in effect or not.
     */
    long getDbCommitNanos();

    /**
     * @return time taken (in nanoseconds) to produce, and then send (transfer) the message(s) to the message broker,
     *         should basically be the sum of any {@link #getOutgoingMessages() outgoing} messages'
     *         {@link MatsSentOutgoingMessage#getMessageSystemProductionAndSendNanos()}.
     * @see MatsSentOutgoingMessage#getMessageSystemProductionAndSendNanos()
     */
    long getSumMessageSystemProductionAndSendNanos();

    /**
     * @return time taken (in nanoseconds) to commit the MQ system. If the initiation didn't actually send any messages,
     *         MQ transaction elision might kick in, and this number may become very low or <code>0</code>.
     */
    long getMessageSystemCommitNanos();

    /**
     * @return time taken (in nanoseconds) from initiate(..) was invoked, or the incoming message was received for a
     *         Stage, to everything is finished and committed (i.e. basically "at exit" of initiate(..), or right before
     *         going back up to the receive-loop for a Stage)
     */
    long getTotalExecutionNanos();

    /**
     * @return any Throwable that was raised during the initiation/stage, meaning that the initiation/stage failed.
     */
    Optional<Throwable> getThrowable();

    /**
     * @return the resulting full list of outgoing messages that was sent in this initiation/stage context. <i>(For
     *         Stages, this contains all messages, including any reply, next or gotos, any request(s), and initiated
     *         messages - for which there are separate methods to get in separate lists/Optional.)</i>
     */
    List<MatsSentOutgoingMessage> getOutgoingMessages();
}
