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

package io.mats3.api_test.bugs;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests Issue 64 as understood after investigation as it was originally reported. The initial investigation was carried
 * out in 'mats-spring' test <code>Issue_64_ReplyToMatsClassMappingWithNullReplyState</code>.
 * <p>
 * If an initiation request is performed to some EndpointX, with init.replyTo(EndpointA, null), i.e. the replyState
 * being null, EndpointA was actually invoked with null state. This on first blush seems intuitive, but it screws over
 * what EndpointA expects: It has declared some state class S, and expects to be able to set variables on it etc - it
 * makes no sense that it suddenly is null. Also, Mats internals (specifically MatsTraceFieldImpl) also expects it to be
 * non-null if the Endpoint is multi-stage, as it should serialize it onto the stack upon REQUEST or NEXT. Therefore, a
 * state object cannot ever be null, and in the specific situation with replyState = null, this must instead create a
 * new empty state object.
 *
 * @author Endre Stølsvik - 2022-08-19 - http://endre.stolsvik.com
 */
public class Issue_64_ReplyToMultiStageWithNullReplyState {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR_SINGLE = MatsTestHelp.endpoint("TerminatorSingleStage");
    private static final String TERMINATOR_MULTI = MatsTestHelp.endpoint("TerminatorMultiStage");

    @BeforeClass
    public static void single() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class, (context, dto) -> {
            // Calculate the resulting values
            double resultNumber = dto.number * 2;
            String resultString = dto.string + ":FromService";
            // Return the reply DTO
            return new DataTO(resultNumber, resultString);
        });
    }

    @BeforeClass
    public static void stagedTerminator_SingleStage() {
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(TERMINATOR_SINGLE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(state, msg);
        });
        ep.finishSetup();
    }

    @BeforeClass
    public static void stagedTerminator_MultiStage() {
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(TERMINATOR_MULTI, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            ctx.next(msg);
        });
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            MATS.getMatsTestLatch().resolve(state, msg);
        });
        ep.finishSetup();
    }

    // ===== Tests =========

    @Test
    public void sameSetup_ButNonNullReplyToState_WorksAsExpected_single() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR_SINGLE, sto)
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    @Test
    public void sameSetup_ButNonNullReplyToState_WorksAsExpected_multi() {
        DataTO dto = new DataTO(43, "TheAnswer+1");
        StateTO sto = new StateTO(123, 321.123);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR_MULTI, sto)
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    /**
     * FAILS WITH BUG: A staged endpoint with just one stage: The incoming state is null, since we have said that it
     * should be! However, it should not be up to the invoker to decide whether an endpoint which has declared a state
     * class should get an instance or not. So either it must be illegal to send null, or null must be "translated" to
     * just an empty object - and of these two solutions, I believe the latter is best along all angles.
     */
    @Test
    public void problematicSetup_NullReplyToState_single() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR_SINGLE, null)
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        // This is the kicker: We DO send in null as replTo-state, so in some ways it is correct that the incoming
        // state object actually is null.
        // However, since a multi-stage Mats Endpoint _cannot_ have a null state, it must then be given a "new empty"
        // state instance instead of the incoming null state.
        Assert.assertNotNull(result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    private static class MatsIssue64Bypasser {
        private int noop;
    }

    /**
     * Temporary way to get around the problem of not having any state to send: You need some "dummy null" object to
     * send. Didn't work with a dummy String, as that could not be <i>deserialized</i> to the StateTO on receiving, and
     * it didn't work with <code>new Object()</code> (or any other class with no fields), as that couldn't be
     * <i>serialized</i> on send (this is evidently a sanity check thing from Jackson). So send a class with a dummy
     * field.
     */
    @Test
    public void problematicSetup_NullReplyToState_bypassProblem() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR_SINGLE, new MatsIssue64Bypasser())
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        // The MatsIssue64Bypasser serializes to "{noop:0}" (or probably "{}", since 0 is default), and thus
        // deserializes to an "empty state instance" on incoming.
        Assert.assertNotNull(result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    /**
     * FAILS WITH BUG: This test shows that a multi-stage cannot work with null as incoming state object: Both Mats
     * itself will after the initial stage is finished try to serialize the null, and MatsTrace won't accept a null
     * state. Also, the staged endpoint might want to set variables on the state - it is semantically HIS state object,
     * not the caller to decide whether it is present or not.
     */
    @Test
    public void problematicSetup_NullReplyToState_multi() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR_MULTI, null)
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertNotNull(result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }

    /**
     * Sending directly to the endpoint with initial state = null, which one might think should exhibit the same
     * behaviour (i.e. result in null incoming state), actually works.
     * <p>
     * The reason this works, is that the MatsTraceFieldImpl only adds the initial StackState if initialState != null.
     * Explicitly setting null, or not setting initial state, is exactly the same: send(dto) without initial state just
     * calls send(dto, null).
     */
    @Test
    public void sendingDirectlyWithNullInitialState_SurprisinglyWorks() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR_SINGLE)
                        .send(dto, null));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertNotNull(result.getState());
        Assert.assertEquals(new DataTO(dto.number, dto.string), result.getData());
    }

    /**
     * Requesting directly to the endpoint with initial state = null, which one might think should exhibit the same
     * behaviour (i.e. result in null incoming state), actually works.
     * <p>
     * The reason this works, is that the MatsTraceFieldImpl only adds initial StackState if initialState != null.
     * Explicitly setting null, or not setting initial state, is exactly the same: request(dto) without initial state
     * just calls request(dto, null).
     */
    @Test
    public void requestingDirectlyWithNullInitialState_SurprisinglyWorks() {
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR_SINGLE)
                        .replyTo("Not used", "Not used")
                        .request(dto));

        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertNotNull(result.getState());
        Assert.assertEquals(new DataTO(dto.number, dto.string), result.getData());
    }

}
