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

package io.mats3.spring.test.apptest2;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.mats3.MatsFactory;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories.TestCustomQualifier;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

@Service
public class AppService {
    private static final Logger log = LoggerFactory.getLogger(AppService.class);

    @Inject
    private MatsTestLatch _latch;

    @Inject
    private AtomicInteger _atomicInteger;

    @Inject
    @TestCustomQualifier(region = "SouthWest")
    private MatsFactory _matsFactory;

    void run() {
        once("Test1", 7, 1);
        once("Test2", 13, 2);
    }

    void once(String string, int number, int atomic) {
        SpringTestDataTO dto = new SpringTestDataTO(Math.PI * number, string + ":Data");
        SpringTestStateTO sto = new SpringTestStateTO(256 * number, string + "State");
        _matsFactory.getDefaultInitiator().initiateUnchecked(
                msg -> msg.traceId("TraceId")
                        .from("FromId")
                        .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".multi")
                        .replyTo(AppMain_TwoMatsFactories.ENDPOINT_ID + ".terminator", sto)
                        .request(dto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        System.out.println("XXX State: " + result.getState());
        System.out.println("YYY Reply: " + result.getData());
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3, dto.string + ":single:multi"),
                result.getData());
        Assert.assertEquals(atomic, _atomicInteger.get());
    }
}
