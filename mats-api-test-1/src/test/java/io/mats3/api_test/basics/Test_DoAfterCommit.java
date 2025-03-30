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

import java.sql.Connection;
import java.util.Optional;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory.ContextLocal;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestBarrier;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Test to check that {@link ProcessContext#doAfterCommit(Runnable)} is run, and that it is run <i>outside</i> of the
 * transaction and init/stage context.
 */
public class Test_DoAfterCommit {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.createWithDb();

    private static final String ENDPOINT = MatsTestHelp.endpoint("Single");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final MatsTestBarrier _testBarrierInside = new MatsTestBarrier();
    private static final MatsTestBarrier _testBarrierDoAfterCommit = new MatsTestBarrier();

    @Test
    public void stageTest() throws InterruptedException {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("Inside terminator lambda.");
                    // Assert that we have the SQL Connection
                    Optional<Connection> conStage = ContextLocal.getAttribute(Connection.class);
                    if (conStage.isPresent()) {
                        _testBarrierInside.resolve(dto);
                    }
                    else {
                        _testBarrierInside.resolveException(new AssertionError(
                                "Connection should be present inside stage."));
                    }

                    context.doAfterCommit(() -> {
                        log.debug("Inside doAfterCommit lambda.");
                        // Assert that we do NOT have the SQL Connection
                        // (This is a proxy on whether we're still inside the stage context or not)
                        Optional<Connection> conAfter = ContextLocal.getAttribute(Connection.class);
                        if (conAfter.isPresent()) {
                            _testBarrierDoAfterCommit.resolveException(new AssertionError(
                                    "Connection should NOT be present inside do-after-commit lambda"));
                        }
                        else {
                            _testBarrierDoAfterCommit.resolve();
                        }
                    });
                });

        DataTO dto = new DataTO(10, "ThisIsIt");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish.
        DataTO dtoInside = _testBarrierInside.await();
        Assert.assertEquals(dto, dtoInside);

        // Wait for the doAfterCommit lambda runs.
        _testBarrierDoAfterCommit.await();

        // :: Clean up
        _testBarrierInside.reset();
        _testBarrierDoAfterCommit.reset();
        MATS.cleanMatsFactories();
    }

    @Test
    public void stashTest() throws InterruptedException {
        // :: ARRANGE (get a stash to unstash!)

        MatsTestBarrier endpointBarrier = new MatsTestBarrier();
        MatsTestBarrier terminatorBarrier = new MatsTestBarrier();

        byte[][] stash = new byte[1][];
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    stash[0] = context.stash();
                    endpointBarrier.resolve(dto);
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });

        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    terminatorBarrier.resolve(dto);
                });

        DataTO dto = new DataTO(10, "ThisIsIt");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(ENDPOINT)
                        .replyTo(TERMINATOR, new StateTO(42, 42.42))
                        .request(dto));

        // Wait synchronously for terminator to finish and have stashed.
        DataTO endpointDto = endpointBarrier.await();
        Assert.assertEquals(dto, endpointDto);

        // Assert that we got stash
        Assert.assertNotNull(stash[0]);

        // Wait synchronously for terminator to finish.
        DataTO terminatorDto = terminatorBarrier.await();
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), terminatorDto);

        endpointBarrier.reset();
        terminatorBarrier.reset();

        // :: ACT

        // Unstash!
        MATS.getMatsInitiator().initiateUnchecked(initiate -> initiate.unstash(stash[0],
                DataTO.class, StateTO.class, DataTO.class, (context, state, incomingDto) -> {
                    log.debug("Inside unstash lambda.");
                    // Assert that we have the SQL Connection
                    Optional<Connection> conUnstash = ContextLocal.getAttribute(Connection.class);
                    if (conUnstash.isPresent()) {
                        _testBarrierInside.resolve(incomingDto);
                    }
                    else {
                        _testBarrierInside.resolveException(new AssertionError(
                                "Connection should be present inside unstash."));
                    }
                    context.reply(new DataTO(incomingDto.number * 3, incomingDto.string + ":FromUnstash"));

                    context.doAfterCommit(() -> {
                        log.debug("Inside doAfterCommit lambda.");
                        // Assert that we do NOT have the SQL Connection
                        // (This is a proxy on whether we're still inside the init/unstash context or not)
                        Optional<Connection> conAfter = ContextLocal.getAttribute(Connection.class);
                        if (conAfter.isPresent()) {
                            _testBarrierDoAfterCommit.resolveException(new AssertionError(
                                    "Connection should NOT be present inside do-after-commit lambda"));
                        }
                        else {
                            _testBarrierDoAfterCommit.resolve();
                        }
                    });
                }));

        // :: ASSERT

        // Wait synchronously for unstash lambda to finish.
        DataTO dtoInside = _testBarrierInside.await();
        Assert.assertEquals(dto, dtoInside);

        // Wait for the doAfterCommit lambda runs.
        _testBarrierDoAfterCommit.await();

        // Wait for the terminator to finish (it should now get the reply from the unstash lambda).
        terminatorDto = terminatorBarrier.await();
        Assert.assertEquals(new DataTO(dto.number * 3, dto.string + ":FromUnstash"), terminatorDto);

        // :: Clean up
        _testBarrierInside.reset();
        _testBarrierDoAfterCommit.reset();
        MATS.cleanMatsFactories();
    }
}
