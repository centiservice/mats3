package io.mats3.api.intercept;

import java.util.List;
import java.util.Optional;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.api.intercept.MatsInitiateInterceptor.InitiateCompletedContext;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptUserLambda;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsSentOutgoingMessage;
import io.mats3.api.intercept.MatsStageInterceptor.StageCommonContext;
import io.mats3.api.intercept.MatsStageInterceptor.StageCompletedContext;

/**
 * Common part of the interface of {@link InitiateCompletedContext} and {@link StageCompletedContext}.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface CommonCompletedContext {
    /**
     * @return time taken (in nanoseconds) from initiate(..) was invoked, or the incoming message was received for a
     *         Stage, to everything is finished and committed (i.e. basically "at exit" of initiate(..), or right before
     *         going back up to the receive-loop for a Stage). This should pretty much be the sum of "all of the below",
     *         i.e. {@link #getUserLambdaNanos()} + {@link #getSumEnvelopeSerializationAndCompressionNanos()} +
     *         {@link #getDbCommitNanos()} + {@link #getSumMessageSystemProductionAndSendNanos()} +
     *         {@link #getMessageSystemCommitNanos()}, PLUS, for Stages, the message reception timings
     *         {@link StageCommonContext#getTotalPreprocessAndDeserializeNanos()} (which again are decomposed).
     */
    long getTotalExecutionNanos();

    /**
     * @return time taken (in nanoseconds) to run the user supplied initiate lambda itself, from entry to exit. Any time
     *         taken by invoking message-resulting methods, e.g. {@link ProcessContext#request(String, Object)
     *         processContext.request()}, which in the current JMS implementation of Mats involves creating the envelope
     *         including serializing the state and message DTO, will thus also count toward this time (these timings are
     *         separately available in {@link MatsSentOutgoingMessage#getEnvelopeProduceNanos()}). If the user lambda
     *         includes database access (e.g. a SELECT and/or UPDATE), this timing should probably be dominated by this.
     *         If there are {@link MatsInitiateInterceptUserLambda}s involved, the interceptor(s) might have added some
     *         overhead both by the interception, and by potentially having wrapped the {@link MatsInitiate} instance.
     */
    long getUserLambdaNanos();

    List<MatsMeasurement> getMeasurements();

    List<MatsTimingMeasurement> getTimingMeasurements();

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
     *         is, you may use the 0 to check whether SQL transaction management was in effect or not.
     */
    long getDbCommitNanos();

    /**
     * @return time taken (in nanoseconds) to produce, and then send (transfer) the message(s) to the message broker,
     *         should basically be the sum of any {@link #getOutgoingMessages() outgoing} messages'
     *         {@link MatsSentOutgoingMessage#getMessageSystemProduceAndSendNanos()}.
     * @see MatsSentOutgoingMessage#getMessageSystemProduceAndSendNanos()
     */
    long getSumMessageSystemProductionAndSendNanos();

    /**
     * @return time taken (in nanoseconds) to commit the MQ system. If the initiation didn't actually send any messages,
     *         MQ transaction elision might kick in, and this number may become very low or <code>0</code>.
     */
    long getMessageSystemCommitNanos();

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

    interface MatsMeasurement {
        String getMetricId();

        String getMetricDescription();

        String getBaseUnit();

        double getMeasure();

        String[] getLabelKeyValue();
    }

    interface MatsTimingMeasurement {
        String getMetricId();

        String getMetricDescription();

        long getNanos();

        String[] getLabelKeyValue();
    }
}
