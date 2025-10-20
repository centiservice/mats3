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

package io.mats3.spring.test.apptest1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.spring.EnableMats;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * This test "pieces together" what we need from the application to run the test, and then employs the
 * {@link MatsTestInfrastructureConfiguration} to get the needed Mats infrastructure pieces
 * (including @{@link EnableMats}) - instead of having to do that manually.
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { AppEndpoint_MainService.class,
        AppEndpoint_LeafServices.class, AppServiceCalculator.class, MatsTestInfrastructureConfiguration.class })
@DirtiesContext
public class Test_C_PieceTogetherNeededAppComponents_Auto {
    @Inject
    private MatsFuturizer _matsFuturizer;

    private void matsEndpointTest(double number, String string) {
        SpringTestDataTO dto = new SpringTestDataTO(number, string);
        CompletableFuture<Reply<SpringTestDataTO>> future = _matsFuturizer.futurizeNonessential(MatsTestHelp.traceId(),
                MatsTestHelp.from("testX"),
                AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT,
                SpringTestDataTO.class, dto);

        SpringTestDataTO reply;
        try {
            reply = future.get(10, TimeUnit.SECONDS).get();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Didn't get reply", e);
        }

        Assert.assertEquals(Test_A_UseFullApplicationConfiguration.expectedNumber(dto.number), reply.number, 0d);
        Assert.assertEquals(Test_A_UseFullApplicationConfiguration.expectedString(dto.string), reply.string);
    }

    // Running the test a few times, to check that the network "stays up" between tests.
    //
    // NOTICE: JUnit actually creates a new instance of the test class for each test, i.e. each of the methods below is
    // run within a new instance. This implies that the injection of the fields in this class is done multiple times,
    // i.e. for each new test. However, the Spring Context stays the same between the tests, thus the injected fields
    // get the same values each time.

    @Test
    public void matsEndpointTest1() {
        matsEndpointTest(10, "StartString");
    }

    @Test
    public void matsEndpointTest2() {
        matsEndpointTest(20, "BeginnerString");
    }

    @Test
    public void matsEndpointTest3() {
        matsEndpointTest(42, "TheAnswer");
    }
}
