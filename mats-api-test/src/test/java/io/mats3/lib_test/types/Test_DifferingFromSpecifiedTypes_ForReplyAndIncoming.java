package io.mats3.lib_test.types;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;

/**
 * It is possible for a service to reply with a more specific (i.e. sub class, or "narrower") than what it specifies.
 * (This can be of use if one consumer of a service needs some extra info compared to the standard, e.g. based on the
 * Request DTO having some flag set, the service can add in the extra info. One can of course just leave some fields
 * null on the sole Reply DTO in use, but it might be of more information to stick the extra fields in a sub class Reply
 * DTO, which then can be JavaDoc'ed separately).
 * <p>
 * This class tests that option, by having a service that can reply with both the {@link DataTO} and the sub class
 * {@link SubDataTO}, and two different terminators: One expecting a {@link DataTO} and one expecting a
 * {@link SubDataTO}. Three combinatios are tested: Reply with SubDataTO, then Receiving SubDataTO, and Receiving
 * DataTO. And then Reply with DataTO, Receiving SubDataTO. Replying with DataTO, Receiving DataTO is the normal case,
 * and is tested many times other places.
 *
 * @see Test_ReplyClass_Object
 * @see Test_ServiceInvokedWithDifferentClass
 *
 * @author Endre Stølsvik - 2016-06-04 - http://endre.stolsvik.com
 */
public class Test_DifferingFromSpecifiedTypes_ForReplyAndIncoming {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    if (dto.string.equals("SubDataTO")) {
                        // Covariant reply (i.e. more specialized, narrower) type than specified
                        return new SubDataTO(dto.number * 2, dto.string + ":FromService_SubDataTO",
                                "SubDataTO_Specific");
                    }
                    else {
                        // Reply with the ordinary DataDto
                        return new DataTO(dto.number * 2, dto.string + ":FromService_DataTO");
                    }
                });
    }

    @BeforeClass
    public static void setupTerminator_DataDto() {
        // Expecting/Deserializing into the base/parent ("wide") type:
        MATS.getMatsFactory().terminator(TERMINATOR + ".DataTO", StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @BeforeClass
    public static void setupTerminator_SubDataDto() {
        // Expecting/Deserializing into the child ("narrow"/"specific") type:
        MATS.getMatsFactory().terminator(TERMINATOR + ".SubDataTO", StateTO.class, SubDataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    MatsInitiator _initiator;

    @Before
    public void getInitiator() {
        _initiator = MATS.getMatsInitiator();
    }

    @Test
    public void serviceRepliesWithMoreSpecificDtoThanSpecified_AndTerminatorExpectsThis() {
        // Specifies that Service shall reply with SubDataDto (more specific reply DTO than specified)
        DataTO dto = new DataTO(Math.E, "SubDataTO");
        StateTO sto = new StateTO(420, 420.024);
        _initiator.initiateUnchecked((msg) -> msg.traceId(MatsTestHelp.randomId())
                .from(MatsTestHelp.from("1"))
                .to(SERVICE)
                .replyTo(TERMINATOR + ".SubDataTO", sto)
                .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, SubDataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SubDataTO(dto.number * 2, dto.string + ":FromService_SubDataTO", "SubDataTO_Specific"),
                result.getData());
    }

    @Test
    public void serviceRepliesWithMoreSpecificDtoThanSpecified_ButTerminatorExpectsBaseDto_ThusTooManyFields() {
        // Specifies that Service shall reply with SubDataDto (more specific reply DTO than specified)
        DataTO dto = new DataTO(Math.PI, "SubDataTO");
        StateTO sto = new StateTO(420, 420.024);
        _initiator.initiateUnchecked((msg) -> msg.traceId(MatsTestHelp.randomId())
                .from(MatsTestHelp.from("2"))
                .to(SERVICE)
                .replyTo(TERMINATOR + ".DataTO", sto)
                .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService_SubDataTO"), result.getData());
    }

    @Test
    public void serviceRepliesWithBaseDtoAsSpecified_ButTerminatorExpectsSubDataDto_ThusMissingFields() {
        // Specifies that Service shall reply with DataDto (the DTO it specifies it will reply with)
        DataTO dto = new DataTO(Math.E * Math.PI, "DataTO");
        StateTO sto = new StateTO(420, 420.024);
        _initiator.initiateUnchecked((msg) -> msg.traceId(MatsTestHelp.randomId())
                .from(MatsTestHelp.from("3"))
                .to(SERVICE)
                .replyTo(TERMINATOR + ".SubDataTO", sto)
                .request(dto));

        // Wait synchronously for terminator to finish.
        Result<StateTO, SubDataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SubDataTO(dto.number * 2, dto.string + ":FromService_DataTO", null),
                result.getData());
    }
}
