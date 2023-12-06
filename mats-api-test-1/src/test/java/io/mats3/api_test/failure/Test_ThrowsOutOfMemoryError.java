package io.mats3.api_test.failure;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that throwing an {@link OutOfMemoryError} (an Error) will DLQ.
 *
 * @author Endre Stølsvik 2023-12-05 00:15 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_ThrowsOutOfMemoryError {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void doTest() {
        String TERMINATOR = MatsTestHelp.terminator();
        String INITIATOR_ID = MatsTestHelp.from("test");

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    throw new OutOfMemoryError("FAKE! TEST! This is not really an out of memory!");
                });

        // Send message directly to the "Terminator" endpoint.
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(INITIATOR_ID)
                        .to(TERMINATOR)
                        .send(dto));

        // Assert
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(
                TERMINATOR);
        Assert.assertEquals(INITIATOR_ID, dlqMessage.getFrom());
        Assert.assertEquals(TERMINATOR, dlqMessage.getTo());
    }
}
