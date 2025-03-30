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

package io.mats3.api_test.basics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that the {@link io.mats3.MatsFactory.FactoryConfig#setInitiateTraceIdModifier(Function) Initiate TraceId
 * Modifier Function} works. There are also tests that checks that if the TraceId modifier employs the MDC, we do not
 * get a recursive situation when sending many messages in one initiation (this happened because when setting the
 * TraceId from within a initiation, the MDC is also set to the result. Thus, if you sent one more, the modifier
 * function would pick up the newly set traceId, and thus you got the result of "recursion" where this "one more"
 * message would get the previous message's traceId prepended, and not only the initial MDC).
 *
 * @author Endre Stølsvik 2021-04-12 21:15 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_InitiateTraceIdModifier {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @After
    @Before
    public void clearMdcAndMatsFactories() {
        MDC.clear();
        MATS.cleanMatsFactories();
    }

    /**
     * Dynamic TraceId modifier, using existing MDC to pick out traceId. This makes for a recursion problem, since the
     * new traceId is also set back on the MDC. That is, if you initiate multiple messages, you could get this method
     * invoked on top of the existing. Mats should avoid this.
     */
    private void setTraceIdModifier_UsingExistingMdcTraceId() {
        MATS.getMatsFactory().getFactoryConfig().setInitiateTraceIdModifier((origTraceId) -> {
            String mdcTraceId = MDC.get("traceId");
            if (mdcTraceId != null) {
                return mdcTraceId + "+" + origTraceId;
            }
            // E-> No MDC
            return origTraceId;
        });
    }

    @Test
    public void simpleModifyTraceId() {
        // :: Arrange
        MATS.getMatsFactory().getFactoryConfig().setInitiateTraceIdModifier((origTraceId) -> "Prefixed+" + origTraceId);

        String TERMINATOR = MatsTestHelp.terminator();
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals("Prefixed+" + origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_mdcSet() {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        MDC.put("traceId", "Prefixed");

        String TERMINATOR = MatsTestHelp.terminator();
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        // Should have been modified (since MDC was set)
        Assert.assertEquals("Prefixed+" + origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_mdcNull() {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        // !Not setting MDC.traceId

        String TERMINATOR = MatsTestHelp.terminator();
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());
        // Should NOT have been modified (since MDC.traceId was null)
        Assert.assertEquals(origTraceId, result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_manyMessages_mdcSet() throws InterruptedException {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        MDC.put("traceId", "Prefixed");

        String TERMINATOR_MANY = MatsTestHelp.endpoint("Terminator_Many");

        int numMessages = 20;
        CountDownLatch countDownLatch = new CountDownLatch(numMessages);
        List<String> traceIdsWithinMats = new CopyOnWriteArrayList<>();

        MATS.getMatsFactory().terminator(TERMINATOR_MANY, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    traceIdsWithinMats.add(context.getTraceId());
                    countDownLatch.countDown();
                });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        List<String> origTraceIds = new ArrayList<>();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    for (int i = 0; i < numMessages; i++) {
                        String traceId = MatsTestHelp.traceId();
                        origTraceIds.add(traceId);
                        init.traceId(traceId)
                                .from(MatsTestHelp.from("test"))
                                .to(TERMINATOR_MANY)
                                .send(dto, sto);
                    }
                });

        // :: Assert
        boolean waited = countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Should have counted down!", waited);

        // Assert that we have all the traceIds
        Assert.assertEquals(traceIdsWithinMats.size(), numMessages);
        traceIdsWithinMats.sort(Comparator.naturalOrder());
        log.info("######### All traceIds within Mats: " + traceIdsWithinMats);
        // Should have been modified (since MDC was set)
        List<String> expectedTraceIds = origTraceIds.stream()
                .map(s -> "Prefixed+" + s)
                .sorted()
                .collect(Collectors.toList());
        Assert.assertEquals(expectedTraceIds, traceIdsWithinMats);
    }

    @Test
    public void usingExistingMdcTraceIdToModify_manyMessages_mdcNull() throws InterruptedException {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        // !Not setting MDC.traceId

        String TERMINATOR_MANY = MatsTestHelp.endpoint("Terminator_Many");

        int numMessages = 20;
        CountDownLatch countDownLatch = new CountDownLatch(numMessages);
        List<String> traceIdsWithinMats = new CopyOnWriteArrayList<>();

        MATS.getMatsFactory().terminator(TERMINATOR_MANY, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    traceIdsWithinMats.add(context.getTraceId());
                    countDownLatch.countDown();
                });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        List<String> origTraceIds = new ArrayList<>();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    for (int i = 0; i < numMessages; i++) {
                        String traceId = MatsTestHelp.traceId();
                        origTraceIds.add(traceId);
                        init.traceId(traceId)
                                .from(MatsTestHelp.from("test"))
                                .to(TERMINATOR_MANY)
                                .send(dto, sto);
                    }
                });

        // :: Assert
        boolean waited = countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Should have counted down!", waited);

        // Assert that we have all the traceIds
        Assert.assertEquals(traceIdsWithinMats.size(), numMessages);
        traceIdsWithinMats.sort(Comparator.naturalOrder());
        log.info("######### All traceIds within Mats: " + traceIdsWithinMats);
        // Should NOT have been modified (since MDC was null)
        List<String> expectedTraceIds = origTraceIds.stream()
                .sorted()
                .collect(Collectors.toList());
        Assert.assertEquals(expectedTraceIds, traceIdsWithinMats);
    }

    @Test
    public void usingExistingMdcTraceIdToModify_initiateWithStageShouldNotModify() {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        MDC.put("traceId", "Prefixed");

        String TERMINATOR = MatsTestHelp.terminator();
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(context, sto, dto);
                });

        // :: Create a single-stage Terminator, which sends a new message (now with an already modified MDC)
        String sendingTerminator = MatsTestHelp.endpoint();
        MATS.getMatsFactory().terminator(sendingTerminator, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            ctx.initiate(init -> init.traceId("WithinStage").to(TERMINATOR).send(msg, state));
        });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(sendingTerminator)
                        .send(dto, sto));

        // :: Assert
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());

        // NOTE: The TraceId was prefixed with "Prefixed+" at the "from the outside" initiation, but NOT when
        // initiated new message within Stage (otherwise, there would be 2x the prefix).
        Assert.assertEquals("Prefixed+" + origTraceId + "|WithinStage", result.getContext().getTraceId());
    }

    @Test
    public void usingExistingMdcTraceIdToModify_initiateWithStageShouldNotModify_manyMessages()
            throws InterruptedException {
        // :: Arrange
        setTraceIdModifier_UsingExistingMdcTraceId();
        MDC.put("traceId", "Prefixed");

        int numMessages = 5;
        CountDownLatch countDownLatch = new CountDownLatch(numMessages * 2);
        List<String> traceIdsWithinMats = new CopyOnWriteArrayList<>();

        String TERMINATOR = MatsTestHelp.terminator();
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    traceIdsWithinMats.add(context.getTraceId());
                    countDownLatch.countDown();
                });

        // :: Create a single-stage Terminator, which sends a new message (now with an already modified MDC)
        String sendingTerminator = MatsTestHelp.endpoint();
        MATS.getMatsFactory().terminator(sendingTerminator, StateTO.class, DataTO.class, (ctx, state, msg) -> {
            // First, repeatedly outside the InitiateLambda
            for (int i = 0; i < numMessages; i++) {
                final int iF = i;
                ctx.initiate(init -> init.traceId("WithinStage_RepOutsideInitiate#" + iF)
                        .to(TERMINATOR).send(msg, state));
            }

            // Then, repeatedly inside the InitiateLambda
            ctx.initiate(init -> {
                for (int i = 0; i < numMessages; i++) {
                    init.traceId("WithinStage_RepWithinInitiate#" + i)
                            .to(TERMINATOR).send(msg, state);
                }
            });
        });

        // :: Act
        StateTO sto = new StateTO(7, 3.14);
        DataTO dto = new DataTO(42, "TheAnswer");
        String origTraceId = MatsTestHelp.traceId();
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> init
                        .traceId(origTraceId)
                        .from(MatsTestHelp.from("test"))
                        .to(sendingTerminator)
                        .send(dto, sto));

        // :: Assert
        boolean waited = countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Should have counted down!", waited);

        log.info("######### All traceIds within Mats: " + traceIdsWithinMats);

        // Construct the expected set of traceIds
        List<String> expectedTraceIds = new ArrayList<>();
        for (int i = 0; i < numMessages; i++) {
            expectedTraceIds.add("Prefixed+" + origTraceId + "|WithinStage_RepOutsideInitiate#" + i);
            expectedTraceIds.add("Prefixed+" + origTraceId + "|WithinStage_RepWithinInitiate#" + i);
        }
        expectedTraceIds.sort(Comparator.naturalOrder());

        // Assert that we have all the traceIds
        Assert.assertEquals(traceIdsWithinMats.size(), numMessages * 2);
        traceIdsWithinMats.sort(Comparator.naturalOrder());

        // NOTE: The TraceId was prefixed with "Prefixed+" at the "from the outside" initiation, but NOT when
        // initiated new message within Stage (otherwise, there would be 2x the prefix).
        Assert.assertEquals(expectedTraceIds, traceIdsWithinMats);
    }
}
