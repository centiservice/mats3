package io.mats3.test.junit;

import java.util.Objects;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestMqInterface;
import io.mats3.test.MatsTestMqInterface.MatsMessageRepresentation;

public class U_MatsTestMqInterface {

    private static final String TERMINATOR_WHICH_THROWS = MatsTestHelp.terminator();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    public static class MatsTestData {
        String string;
        Double number;

        public MatsTestData() {
            // For Jacson.
        }

        public MatsTestData(String string, Double number) {
            this.string = string;
            this.number = number;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MatsTestData that = (MatsTestData) o;
            return Objects.equals(string, that.string) &&
                    Objects.equals(number, that.number);
        }
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR_WHICH_THROWS, MatsTestData.class, MatsTestData.class,
                (ctx, state, msg) -> {
                    throw new MatsRefuseMessageException("DLQ THIS THING!");
                });
    }

    @Test
    public void doTest() {
        // :: Arrange

        MatsTestData dto = new MatsTestData("data", Math.PI);
        MatsTestData sto = new MatsTestData("state", Math.E);

        String from = MatsTestHelp.from("test");

        // :: Act

        MessageReference[] messageReference = new MessageReference[1];

        MATS.getMatsInitiator().initiateUnchecked(init -> {
            messageReference[0] = init.traceId(MatsTestHelp.traceId())
                    .from(from)
                    .to(TERMINATOR_WHICH_THROWS)
                    .send(dto, sto);
        });

        // :: Assert

        // Fetch the magic MatsTestMqInterface for interfacing "directly" with the underlying ActiveMQ
        MatsTestMqInterface matsTestMqInterface = MATS.getMatsTestMqInterface();

        // Note: Throws AssertionError if not gotten in 10 seconds.
        MatsMessageRepresentation dlqMessage = matsTestMqInterface.getDlqMessage(TERMINATOR_WHICH_THROWS);

        // :: Assert that all the values are correct.
        Assert.assertEquals(dto, dlqMessage.getIncomingMessage(MatsTestData.class));
        Assert.assertEquals(sto, dlqMessage.getIncomingState(MatsTestData.class));
        Assert.assertEquals(from, dlqMessage.getFrom());
        Assert.assertEquals(TERMINATOR_WHICH_THROWS, dlqMessage.getTo());
        Assert.assertEquals(messageReference[0].getMatsMessageId(), dlqMessage.getMatsMessageId());
    }
}
