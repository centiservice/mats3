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

package io.mats3.spring.bugs;

import jakarta.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.bugs.Issue_64_ReplyToMatsClassMappingWithNullReplyState.MultipleMappingsConfiguration.MatsStagedClassTerminator;
import io.mats3.spring.matsmappings.MatsSpringDefinedTest_MultipleMappingsTest;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests Issue 64 as originally reported. A situation where an initiate sets replyTo(matsClassMappingEndpoint, null)
 * (notice the <code>@MatsClassMapping</code>), i.e. setting <code>null</code> reply state. This "correctly" gives null
 * as incoming state to the staged endpoint, but this works out rather bad for MatsClassMapping, where the state object
 * also works as "this" for the "template" fields (i.e. injected fields), as well as the stage methods.
 * <p>
 * Realized that this was not a problem with <code>@MatsClassMapping</code> itself, but more generically as it is thus
 * evidently possible to force the receiving endpoint to get a null state, even though the endpoint has declared a state
 * class, so continued investigation with a plain Java test in "mats-api-test", class:
 * <code>Issue_64_ReplyToMultiStageWithNullReplyState</code>.
 *
 * @author Endre Stølsvik - 2022-08-18 - http://endre.stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Issue_64_ReplyToMatsClassMappingWithNullReplyState {
    private static final Logger log = LoggerFactory.getLogger(Issue_64_ReplyToMatsClassMappingWithNullReplyState.class);

    public static final String ENDPOINT_ID = "Issue_64_ReplyToMatsClassMappingWithNullReplyState";
    public static final String SINGLE = ".Single";
    public static final String CLASSMAPPING_TERMINATOR = ".Terminator";

    @Configuration
    static class MultipleMappingsConfiguration {

        @MatsMapping(ENDPOINT_ID + SINGLE)
        public SpringTestDataTO springMatsSingleEndpoint(ProcessContext<SpringTestDataTO> ctx, SpringTestDataTO msg) {
            return new SpringTestDataTO(msg.number * 2, msg.string + ctx.getEndpointId());
        }

        @MatsClassMapping(ENDPOINT_ID + CLASSMAPPING_TERMINATOR)
        public static class MatsStagedClassTerminator {
            public int numero;
            public String cuerda;

            @Inject
            private transient MatsTestLatch _latch;

            @Stage(Stage.INITIAL)
            void singeStage(SpringTestDataTO msg) {
                _latch.resolve(this, msg);
            }
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void sameSetup_ButNonNullReplyToState_WorksAsExpected() {
        // ARRANGE:
        SpringTestStateTO sto = new SpringTestStateTO(5, "two");
        SpringTestDataTO dto = new SpringTestDataTO(2, "five");

        // ACT:
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE)
                    .replyTo(ENDPOINT_ID + CLASSMAPPING_TERMINATOR, sto)
                    .request(dto);
        });

        // :: ASSERT
        // Notice how the state classes are completely different, but the MatsStagedClassTerminator have the same field,
        // so the deserialization shall go fine.
        Result<MatsStagedClassTerminator, SpringTestDataTO> result = _latch.waitForResult();
        MatsStagedClassTerminator state = result.getState();
        Assert.assertEquals(sto.numero, state.numero);
        Assert.assertEquals(sto.cuerda, state.cuerda);

        // Assert that the DTO came correctly in.
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ENDPOINT_ID + SINGLE), result.getData());
    }

    @Test
    public void problematicSetup_WithNullReplyToState() {
        // ARRANGE:
        SpringTestDataTO dto = new SpringTestDataTO(2, "five");

        // ACT:
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + SINGLE)
                    .replyTo(ENDPOINT_ID + CLASSMAPPING_TERMINATOR, null)
                    .request(dto);
        });

        // :: ASSERT
        // Notice how the state classes are completely different, but the MatsStagedClassTerminator have the same field,
        // so the deserialization shall go fine.
        Result<Object, SpringTestDataTO> result = _latch.waitForResult();
        Object state = result.getState();
        Assert.assertTrue(state instanceof MatsStagedClassTerminator);

        // Assert that the DTO came correctly in.
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ENDPOINT_ID + SINGLE), result.getData());
    }

    /**
     * Sends directly to the MatsClassMapping, but with initialState specifically set to null. This works.
     */
    @Test
    public void alternativeAssumedProblematicSetup_WhichSurprisinglyWorks_sendWithInitialState() {
        // ARRANGE:
        SpringTestDataTO dto = new SpringTestDataTO(2, "five");

        // ACT:
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + CLASSMAPPING_TERMINATOR)
                    .send(dto, null);
        });

        // :: ASSERT
        Result<Object, SpringTestDataTO> result = _latch.waitForResult();
        Object state = result.getState();
        Assert.assertTrue(state instanceof MatsStagedClassTerminator);

        // Assert that the DTO came correctly in.
        Assert.assertEquals(new SpringTestDataTO(dto.number, dto.string), result.getData());
    }

    /**
     * Requests directly to the MatsClassMapping, but with initialState specifically set to null. This works.
     */
    @Test
    public void alternativeAssumedProblematicSetup_WhichSurprisinglyWorks_requestWithInitialState() {
        // ARRANGE:
        SpringTestDataTO dto = new SpringTestDataTO(2, "five");

        // ACT:
        _matsInitiator.initiateUnchecked(init -> {
            init.traceId("test_trace_id:" + Math.random())
                    .from(MatsSpringDefinedTest_MultipleMappingsTest.class.getSimpleName())
                    .to(ENDPOINT_ID + CLASSMAPPING_TERMINATOR)
                    .replyTo("WontBeUsed", "WontBeUsed")
                    .request(dto, null);
        });

        // :: ASSERT
        Result<Object, SpringTestDataTO> result = _latch.waitForResult();
        Object state = result.getState();
        Assert.assertTrue(state instanceof MatsStagedClassTerminator);

        // Assert that the DTO came correctly in.
        Assert.assertEquals(new SpringTestDataTO(dto.number, dto.string), result.getData());
    }
}
