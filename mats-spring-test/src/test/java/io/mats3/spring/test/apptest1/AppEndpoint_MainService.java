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

import jakarta.inject.Inject;

import org.junit.Assert;
import org.springframework.stereotype.Component;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.test.SpringTestDataTO;

/**
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Component
public class AppEndpoint_MainService {
    /**
     * Test Multi-Stage @MatsClassMapping endpoint.
     */
    @MatsClassMapping(AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT)
    static class MultiEndPoint {
        @Inject
        private transient AppServiceCalculator _calculator;

        private ProcessContext<SpringTestDataTO> _context;

        // State fields
        private double _initialNumber;

        @Stage(Stage.INITIAL)
        void initialStage(SpringTestDataTO in) {
            // Assert initial values of state (i.e. the state field _initialNumber)
            Assert.assertEquals(0, _initialNumber, 0);

            // Set the initial number on the state, since we need it in the last stage
            _initialNumber = in.number;

            // Perform request to the LEAFENDPOINT1, using the values we got from the request.
            _context.request(AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT1, new SpringTestDataTO(in.number,
                    in.string + ":(π=" + _calculator.multiplyByΠ(1) + ')'));
        }

        @Stage(10)
        void secondStage(SpringTestDataTO in) {
            // Perform request to the LEAFENDPOINT2, using the values we got returned from LEAFENDPOINT2
            _context.request(AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT2, new SpringTestDataTO(in.number,
                    in.string + ":(φ=" + _calculator.multiplyByΦ(1) + ')'));
        }

        @Stage(20)
        SpringTestDataTO lastStage(SpringTestDataTO in) {
            // Create reply, using values we got returned from LEAFENDPOINT2, plus the value we stored in state
            return new SpringTestDataTO(in.number * _initialNumber,
                    in.string + ':' + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT);
        }
    }
}
