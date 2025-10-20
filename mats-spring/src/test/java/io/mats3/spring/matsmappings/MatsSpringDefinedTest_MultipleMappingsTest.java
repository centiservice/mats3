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

import jakarta.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests that {@link MatsMapping @MatsMapping} works when being present multiple times.
 *
 * @author Endre Stølsvik - 2016-08-07 - http://endre.stolsvik.com
 */
@RunWith(SpringRunner.class)
public class MatsSpringDefinedTest_MultipleMappingsTest {
    public static final String ENDPOINT_ID = "MultipleMappingsTest";
    public static final String TERMINATOR1 = ".TERMINATOR1";
    public static final String TERMINATOR2 = ".TERMINATOR2";
    public static final String SINGLE1 = ".Single1";
    public static final String SINGLE2 = ".Single2";
    public static final String SINGLE1_B = ".Single1B";
    public static final String SINGLE2_B = ".Single2B";

    @Configuration
    @MatsTestContext
    static class MultipleMappingsConfiguration {
        @MatsMapping(ENDPOINT_ID + SINGLE1)
        @MatsMapping(ENDPOINT_ID + SINGLE2)
        public SpringTestDataTO springMatsSingleEndpoint(ProcessContext<SpringTestDataTO> ctx, SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + ctx.getEndpointId());
        }

        @MatsClassMapping(ENDPOINT_ID + SINGLE1_B)
        @MatsClassMapping(ENDPOINT_ID + SINGLE2_B)
        @Configuration
        public static class MatsStagedClass {
            @Stage(Stage.INITIAL)
            SpringTestDataTO singeStage(SpringTestDataTO msg, ProcessContext<SpringTestDataTO> ctx) {
                return new SpringTestDataTO(msg.number * 4, msg.string + ctx.getEndpointId());
            }
        }

        @Inject
        private MatsTestLatch _latch;

        @MatsMapping(ENDPOINT_ID + TERMINATOR1)
        @MatsMapping(ENDPOINT_ID + TERMINATOR2)
        public void springMatsSingleEndpoint_Dto(ProcessContext<Void> ctx,
                @Dto SpringTestDataTO msg, @Sto SpringTestStateTO sto) {
            _latch.resolve(sto, new SpringTestDataTO(msg.number * 3, msg.string + ctx.getEndpointId()));
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void single1_terminator1() {
        SpringTestDataTO dto = new SpringTestDataTO(2, "five");
        SpringTestStateTO sto = new SpringTestStateTO(5, "two");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE1)
                    .replyTo(ENDPOINT_ID + TERMINATOR1, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3, dto.string + ENDPOINT_ID + SINGLE1
                + ENDPOINT_ID + TERMINATOR1), result.getData());
    }

    @Test
    public void single2_terminator2() {
        SpringTestDataTO dto = new SpringTestDataTO(6, "six");
        SpringTestStateTO sto = new SpringTestStateTO(7, "seven");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE2)
                    .replyTo(ENDPOINT_ID + TERMINATOR2, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3, dto.string + ENDPOINT_ID + SINGLE2
                + ENDPOINT_ID + TERMINATOR2), result.getData());
    }

    @Test
    public void single1_B_terminator1() {
        SpringTestDataTO dto = new SpringTestDataTO(3, "one");
        SpringTestStateTO sto = new SpringTestStateTO(4, "two");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE1_B)
                    .replyTo(ENDPOINT_ID + TERMINATOR1, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 4 * 3, dto.string + ENDPOINT_ID + SINGLE1_B
                + ENDPOINT_ID + TERMINATOR1), result.getData());
    }

    @Test
    public void single2_B_terminator2() {
        SpringTestDataTO dto = new SpringTestDataTO(1, "three");
        SpringTestStateTO sto = new SpringTestStateTO(2, "four");
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE2_B)
                    .replyTo(ENDPOINT_ID + TERMINATOR2, sto)
                    .request(dto);
        });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 4 * 3, dto.string + ENDPOINT_ID + SINGLE2_B
                + ENDPOINT_ID + TERMINATOR2), result.getData());
    }
}
