package io.mats3.api_test.types;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests an endpoint replying with null - where the Reply DTO is specified as either Void, Object or DataTO.
 *
 * @author Endre Stølsvik 2020-04-03 21:47 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_ReplyClass_Void_Object_DataTO_ReplyWithNull {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();


    @BeforeClass
    public static void setupService_ReplyClass_Void() {
        MATS.getMatsFactory().single(ENDPOINT + ".ReplyClass_Void", Void.TYPE, DataTO.class,
                (context, dto) -> null);
    }

    @BeforeClass
    public static void setupService_ReplyClass_Object() {
        MATS.getMatsFactory().single(ENDPOINT + ".ReplyClass_Object", Object.class, DataTO.class,
                (context, dto) -> null);
    }

    @BeforeClass
    public static void setupService_ReplyClass_DataTO() {
        MATS.getMatsFactory().single(ENDPOINT + ".ReplyClass_DataTO", DataTO.class, DataTO.class,
                (context, dto) -> null);
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    public void test(String toService) {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from(toService))
                        .to(ENDPOINT + "." + toService)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertNull(result.getData());
    }

    @Test
    public void testWithService_ReplyClass_DataTO() {
        test("ReplyClass_DataTO");
    }

    @Test
    public void testWithService_ReplyClass_Void() {
        test("ReplyClass_Void");
    }

    @Test
    public void testWithService_ReplyClass_Object() {
        test("ReplyClass_Object");
    }

}
