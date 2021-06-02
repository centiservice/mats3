package io.mats3.lib_test.types;

import java.util.List;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.MatsObject;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the {@link MatsObject} special snacks.
 *
 * @author Endre Stølsvik 2019-08-25 00:49 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_IncomingClass_MatsObject {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static volatile IllegalArgumentException _exception;

    @BeforeClass
    public static void setupTerminator1() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, MatsObject.class,
                (context, sto, matsObject) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    try {
                        matsObject.toClass(List.class);
                    }
                    catch (IllegalArgumentException iaE) {
                        _exception = iaE;
                    }
                    DataTO dto = matsObject.toClass(DataTO.class);
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(IllegalArgumentException.class, _exception.getClass());
        try {
            Thread.sleep(50);
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
