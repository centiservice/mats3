package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the "next stage" functionality: A three-stage service is set, where init-stage calls next to stage1, that calls
 * next to state2. A Terminator is set up. Then an initiator does a request to the service, setting replyTo(Terminator).
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]             - init request
 *     [Service S0 - init] - next
 *     [Service S1]        - next
 *     [Service S2 - last] - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_MutilStageNext {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupMultiStageService() {
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            context.next(new DataTO(dto.number * 2, dto.string + ":InitialStage"));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.PI;
            context.next(new DataTO(dto.number * 3, dto.string + ":Stage1"));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE, Math.PI), sto);
            return new DataTO(dto.number * 5, dto.string + ":ReplyStage");
        });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @Test
    public void doTest() {
        StateTO sto = new StateTO(420, 420.024);
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 5,
                dto.string + ":InitialStage" + ":Stage1" + ":ReplyStage"),
                result.getData());
    }
}
