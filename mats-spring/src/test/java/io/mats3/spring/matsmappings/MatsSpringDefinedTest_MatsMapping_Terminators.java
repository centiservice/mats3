/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
 * Tests that the {@link MatsMapping @MatsMapping} works for setting up Terminators.
 *
 * @author Endre Stølsvik - 2016-08-07 - http://endre.stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class MatsSpringDefinedTest_MatsMapping_Terminators {
    public static final String ENDPOINT_ID = "MatsMappingTerminatorsTest";
    public static final String TERMINATOR = ".Terminator";
    public static final String TERMINATOR_DTO = ".Terminator_Dto";
    public static final String TERMINATOR_DTO_STO = ".Terminator_Dto_Sto";
    public static final String TERMINATOR_STO_DTO = ".Terminator_Sto_Dto";
    public static final String TERMINATOR_CONTEXT = ".Terminator_Context";
    public static final String TERMINATOR_CONTEXT_DTO = ".Terminator_Context_Dto";
    public static final String TERMINATOR_CONTEXT_DTO_STO = ".Terminator_Context_Dto_Sto";
    public static final String TERMINATOR_CONTEXT_STO_DTO = ".Terminator_Context_Sto_Dto";

    @Configuration
    static class TerminatorsConfiguration {
        @Inject
        private MatsTestLatch _latch;

        // == Permutations WITHOUT ProcessContext

        /**
         * Terminator endpoint (no return value specified == void) - non-specified param, thus Dto.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR)
        public void springMatsSingleEndpoint(SpringTestDataTO msg) {
            _latch.resolve(null, msg);
        }

        /**
         * Terminator endpoint (no return value specified == void) - specified params: Dto.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_DTO)
        public void springMatsSingleEndpoint_Dto(@Dto SpringTestDataTO msg) {
            _latch.resolve(null, msg);
        }

        /**
         * "Single w/State"-endpoint (no return value specified == void) - specified params: Dto, Sto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_DTO_STO)
        public void springMatsSingleWithStateEndpoint_Dto_Sto(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            _latch.resolve(state, msg);
        }

        /**
         * "Single w/State"-endpoint (no return value specified == void) - specified params: Sto, Dto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_STO_DTO)
        public void springMatsSingleWithStateEndpoint_Sto_Dto(@Sto SpringTestStateTO state, @Dto SpringTestDataTO msg) {
            _latch.resolve(state, msg);
        }

        // == Permutations WITH ProcessContext

        /**
         * Terminator endpoint (no return value specified == void) - with Context - non-specified param, thus Dto.
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_CONTEXT)
        public void springMatsSingleEndpoint_Context(ProcessContext<Void> context, SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + TERMINATOR_CONTEXT, context.getTraceId());
            _latch.resolve(null, msg);
        }

        /**
         * Terminator endpoint (no return value specified == void) - with Context - specified params: Dto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_CONTEXT_DTO)
        public void springMatsSingleEndpoint_Context_Dto(ProcessContext<Void> context, @Dto SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + TERMINATOR_CONTEXT_DTO, context.getTraceId());
            _latch.resolve(null, msg);
        }

        /**
         * Terminator endpoint (no return value specified == void) - with Context - specified params: Dto, Sto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_CONTEXT_DTO_STO)
        public void springMatsSingleEndpoint_Context_Dto_Sto(ProcessContext<Void> context,
                @Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            Assert.assertEquals("test_trace_id" + TERMINATOR_CONTEXT_DTO_STO, context.getTraceId());
            _latch.resolve(state, msg);
        }

        /**
         * Terminator endpoint (no return value specified == void) - with Context - specified params: Sto, Dto
         */
        @MatsMapping(endpointId = ENDPOINT_ID + TERMINATOR_CONTEXT_STO_DTO)
        public void springMatsSingleEndpoint_Context_Sto_Dto(ProcessContext<Void> context,
                @Sto SpringTestStateTO state, @Dto SpringTestDataTO msg) {
            Assert.assertEquals("test_trace_id" + TERMINATOR_CONTEXT_STO_DTO, context.getTraceId());
            _latch.resolve(state, msg);
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void sendToTerminator() {
        doTest(TERMINATOR, new SpringTestDataTO(Math.PI, "x" + TERMINATOR), null);
    }

    @Test
    public void sendToTerminator_Dto() {
        doTest(TERMINATOR_DTO, new SpringTestDataTO(Math.E, "x" + TERMINATOR_DTO), null);
    }

    @Test
    public void sendToTerminator_Dto_Sto() {
        doTest(TERMINATOR_DTO_STO, new SpringTestDataTO(Math.PI, "x" + TERMINATOR_DTO_STO),
                new SpringTestStateTO(4, "RequestState-A"));
    }

    @Test
    public void sendToTerminator_Sto_Dto() {
        doTest(TERMINATOR_STO_DTO, new SpringTestDataTO(Math.E, "x" + TERMINATOR_STO_DTO),
                new SpringTestStateTO(5, "RequestState-B"));
    }

    @Test
    public void sendToTerminator_Context() {
        doTest(TERMINATOR_CONTEXT, new SpringTestDataTO(Math.PI, "y" + TERMINATOR_CONTEXT), null);
    }

    @Test
    public void sendToTerminator_Context_Dto() {
        doTest(TERMINATOR_CONTEXT_DTO, new SpringTestDataTO(Math.E, "y" + TERMINATOR_CONTEXT_DTO), null);
    }

    @Test
    public void sendToTerminator_Context_Dto_Sto() {
        doTest(TERMINATOR_CONTEXT_DTO_STO, new SpringTestDataTO(Math.PI, "y" + TERMINATOR_CONTEXT_DTO_STO),
                new SpringTestStateTO(6, "RequestState-C"));
    }

    @Test
    public void sendToTerminator_Context_Sto_Dto() {
        doTest(TERMINATOR_CONTEXT_STO_DTO, new SpringTestDataTO(Math.E, "y" + TERMINATOR_CONTEXT_STO_DTO),
                new SpringTestStateTO(7, "RequestState-D"));
    }

    private void doTest(String epId, SpringTestDataTO dto, SpringTestStateTO requestSto) {
        _matsInitiator.initiateUnchecked(
                init -> {
                    init.traceId("test_trace_id" + epId)
                            .from("FromId" + epId)
                            .to(ENDPOINT_ID + epId);
                    if (requestSto != null) {
                        init.send(dto, requestSto);
                    }
                    else {
                        init.send(dto);
                    }
                });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(requestSto, result.getState());
    }
}