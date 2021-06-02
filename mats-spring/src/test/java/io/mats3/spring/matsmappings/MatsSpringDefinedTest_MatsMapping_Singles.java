package io.mats3.spring.matsmappings;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests several permutations of Spring-defined Single-stage MATS endpoints: Order of parameters in the endpoints
 * defined by {@link MatsMapping @MatsMapping}-annotated methods.
 *
 * @author Endre Stølsvik - 2016-06-23 - http://endre.stolsvik.com
 */
@RunWith(SpringRunner.class)
public class MatsSpringDefinedTest_MatsMapping_Singles {
    public static final String ENDPOINT_ID = "MatsMappingSingleStagesTest";
    public static final String TERMINATOR = ".TERMINATOR";
    public static final String SINGLE = ".Single";
    public static final String SINGLE_DTO = ".Single_Dto";
    public static final String SINGLE_DTO_STO = ".Single_Dto_Sto";
    public static final String SINGLE_STO_DTO = ".Single_Sto_Dto";
    public static final String SINGLE_CONTEXT = ".Single_Context";
    public static final String SINGLE_CONTEXT_DTO = ".Single_Context_Dto";
    public static final String SINGLE_CONTEXT_DTO_STO = ".Single_Context_Dto_Sto";
    public static final String SINGLE_CONTEXT_STO_DTO = ".Single_Context_Sto_Dto";
    public static final String SUBSCRIPTIONTERMINATOR = ".SubscriptionTerminator";

    @Configuration
    @MatsTestContext
    static class SingleStagesConfiguration {

        // == Permutations WITHOUT ProcessContext

        /**
         * Single-stage endpoint (return value specified) - non-specified param, thus Dto.
         */
        @MatsMapping(ENDPOINT_ID + SINGLE)
        public SpringTestDataTO springMatsSingleEndpoint(SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + SINGLE);
        }

        /**
         * Single-stage endpoint (return value specified) - specified params: Dto.
         */
        @MatsMapping(ENDPOINT_ID + SINGLE_DTO)
        public SpringTestDataTO springMatsSingleEndpoint_Dto(@Dto SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + SINGLE_DTO);
        }

        /**
         * "Single w/State"-endpoint (return value specified) - specified params: Dto, Sto
         */
        @MatsMapping(ENDPOINT_ID + SINGLE_DTO_STO)
        public SpringTestDataTO springMatsSingleWithStateEndpoint_Dto_Sto(@Dto SpringTestDataTO msg,
                @Sto SpringTestStateTO state) {
            return new SpringTestDataTO(msg.number * state.numero,
                    msg.string + SINGLE_DTO_STO + '.' + state.cuerda);
        }

        /**
         * "Single w/State"-endpoint (return value specified) - specified params: Sto, Dto
         */
        @MatsMapping(ENDPOINT_ID + SINGLE_STO_DTO)
        public SpringTestDataTO springMatsSingleWithStateEndpoint_Sto_Dto(@Sto SpringTestStateTO state,
                @Dto SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * state.numero,
                    msg.string + SINGLE_STO_DTO + '.' + state.cuerda);
        }

        // == Permutations WITH ProcessContext

        /**
         * Single-stage endpoint (return value specified) - with Context - non-specified param, thus Dto.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + SINGLE_CONTEXT)
        public SpringTestDataTO springMatsSingleEndpoint_Context(ProcessContext<SpringTestDataTO> context,
                SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + SINGLE_CONTEXT, context.getTraceId());
            return new SpringTestDataTO(msg.number * 2, msg.string + SINGLE_CONTEXT);
        }

        /**
         * Single-stage endpoint (return value specified) - with Context - specified params: Dto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + SINGLE_CONTEXT_DTO)
        public SpringTestDataTO springMatsSingleEndpoint_Context_Dto(ProcessContext<SpringTestDataTO> context,
                @Dto SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + SINGLE_CONTEXT_DTO, context.getTraceId());
            return new SpringTestDataTO(msg.number * 2, msg.string + SINGLE_CONTEXT_DTO);
        }

        /**
         * Single-stage endpoint (return value specified) - with Context - specified params: Dto, Sto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + SINGLE_CONTEXT_DTO_STO)
        public SpringTestDataTO springMatsSingleEndpoint_Context_Dto_Sto(ProcessContext<SpringTestDataTO> context,
                @Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            Assert.assertEquals("test_trace_id" + SINGLE_CONTEXT_DTO_STO, context.getTraceId());
            return new SpringTestDataTO(msg.number * state.numero,
                    msg.string + SINGLE_CONTEXT_DTO_STO + '.' + state.cuerda);
        }

        /**
         * Single-stage endpoint (return value specified) - with Context - specified params: Sto, Dto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + SINGLE_CONTEXT_STO_DTO)
        public SpringTestDataTO springMatsSingleEndpoint_Context_Sto_Dto(ProcessContext<SpringTestDataTO> context,
                @Sto SpringTestStateTO state, @Dto SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + SINGLE_CONTEXT_STO_DTO, context.getTraceId());
            return new SpringTestDataTO(msg.number * state.numero,
                    msg.string + SINGLE_CONTEXT_STO_DTO + '.' + state.cuerda);
        }

        // == A SubscriptionTerminator using @MatsMapping

        /**
         * Basic SubscriptionTerminator, taking both STO and DTO
         */
        @MatsMapping(subscription = true, value = ENDPOINT_ID + SUBSCRIPTIONTERMINATOR)
        public void springMatsSubscriptionTerminator(@Sto SpringTestStateTO state, @Dto SpringTestDataTO msg) {
            _latch.resolve(state, msg);
        }

        // == A Terminator for all the tests.

        @Inject
        private MatsTestLatch _latch;

        /**
         * Terminator for all the tests (no return value specified; return type == void).
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR)
        public void springMatsTerminatorEndpoint(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            _latch.resolve(state, msg);
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void requestSingle() {
        doTest(SINGLE, new SpringTestDataTO(Math.PI, "x" + SINGLE), null);
    }

    @Test
    public void requestSingle_Dto() {
        doTest(SINGLE_DTO, new SpringTestDataTO(Math.E, "x" + SINGLE_DTO), null);
    }

    @Test
    public void requestSingle_Dto_Sto() {
        doTest(SINGLE_DTO_STO, new SpringTestDataTO(Math.PI, "x" + SINGLE_DTO_STO),
                new SpringTestStateTO(4, "RequestState-A"));
    }

    @Test
    public void requestSingle_Sto_Dto() {
        doTest(SINGLE_STO_DTO, new SpringTestDataTO(Math.E, "x" + SINGLE_STO_DTO),
                new SpringTestStateTO(5, "RequestState-B"));
    }

    @Test
    public void requestSingle_Context() {
        doTest(SINGLE_CONTEXT, new SpringTestDataTO(Math.PI, "y" + SINGLE_CONTEXT), null);
    }

    @Test
    public void requestSingle_Context_Dto() {
        doTest(SINGLE_CONTEXT_DTO, new SpringTestDataTO(Math.E, "y" + SINGLE_CONTEXT_DTO), null);
    }

    @Test
    public void requestSingle_Context_Dto_Sto() {
        doTest(SINGLE_CONTEXT_DTO_STO, new SpringTestDataTO(Math.PI, "y" + SINGLE_CONTEXT_DTO_STO),
                new SpringTestStateTO(6, "RequestState-C"));
    }

    @Test
    public void requestSingle_Context_Sto_Dto() {
        doTest(SINGLE_CONTEXT_STO_DTO, new SpringTestDataTO(Math.E, "y" + SINGLE_CONTEXT_STO_DTO),
                new SpringTestStateTO(7, "RequestState-D"));
    }

    @Test
    public void subscriptionTerminator() {
        SpringTestStateTO sto = new SpringTestStateTO(1337, "TopicState");
        SpringTestDataTO dto = new SpringTestDataTO(Math.PI, "TopicMessage");
        _matsInitiator.initiateUnchecked(
                init -> {
                    init.traceId("test_trace_id")
                            .from("FromId")
                            .to(ENDPOINT_ID + SUBSCRIPTIONTERMINATOR)
                            .publish(dto, sto);
                });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(dto, result.getData());
    }

    private void doTest(String epId, SpringTestDataTO dto, SpringTestStateTO requestSto) {
        SpringTestStateTO sto = new SpringTestStateTO(256, "State");
        _matsInitiator.initiateUnchecked(
                init -> {
                    init.traceId("test_trace_id" + epId)
                            .from("FromId" + epId)
                            .to(ENDPOINT_ID + epId)
                            .replyTo(ENDPOINT_ID + TERMINATOR, sto);
                    if (requestSto != null) {
                        init.request(dto, requestSto);
                    }
                    else {
                        init.request(dto);
                    }
                });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        if (requestSto != null) {
            Assert.assertEquals(new SpringTestDataTO(dto.number * requestSto.numero,
                    dto.string + epId + '.' + requestSto.cuerda), result.getData());
        }
        else {
            Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + epId), result.getData());
        }
    }
}
