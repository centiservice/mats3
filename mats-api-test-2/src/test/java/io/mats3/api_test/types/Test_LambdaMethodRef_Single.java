package io.mats3.api_test.types;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests method references as the processing lambda, and whether we can invoke a method with a wider signature than the
 * specified classes.
 *
 * @author Endre Stølsvik - 2016-06-05 - http://endre.stolsvik.com
 */
public class Test_LambdaMethodRef_Single {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final String MATCH_TYPES = ".MatchTypes";
    private static final String WIDE_TYPES = ".WideTypes";

    @BeforeClass
    public static void setupService_MatchTypes_StaticMethodRef() {
        MATS.getMatsFactory().single(ENDPOINT + MATCH_TYPES, DataTO.class, DataTO.class,
                Test_LambdaMethodRef_Single::lambda_MatchTypes);
    }

    private static DataTO lambda_MatchTypes(ProcessContext<DataTO> context, DataTO dto) {
        return new DataTO(dto.number * 2, dto.string + MATCH_TYPES);
    }

    @BeforeClass
    public static void setupService_WideTypes_InstanceMethodRef() {
        MATS.getMatsFactory().single(ENDPOINT + WIDE_TYPES, DataTO.class, DataTO.class, Test_LambdaMethodRef_Single::lambda_WideTypes);
    }

    private static SubDataTO lambda_WideTypes(ProcessContext<?> context, Object dtoObject) {
        DataTO dto = (DataTO) dtoObject;
        return new SubDataTO(dto.number * 4, dto.string + WIDE_TYPES, dto.string + (Math.PI * 2));
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, SubDataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void matchTypes() {
        DataTO dto = new DataTO(Math.PI, "TheAnswer_1");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from("matchTypes"))
                        .to(ENDPOINT + MATCH_TYPES)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());

        // Note that for the "MatchTypes", the type of the incoming will actually be a SubDataTO, as that is what
        // the terminator defines, and is what the JSON-lib will deserialize the incoming JSON to.
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + MATCH_TYPES), result.getData());
    }

    @Test
    public void wideTypes() {
        DataTO dto = new DataTO(Math.E, "TheAnswer_2");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.randomId())
                        .from(MatsTestHelp.from("wideTypes"))
                        .to(ENDPOINT + WIDE_TYPES)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, SubDataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SubDataTO(dto.number * 4, dto.string + WIDE_TYPES, dto.string + (Math.PI * 2)),
                result.getData());
    }
}
