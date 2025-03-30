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

import javax.inject.Inject;

import org.springframework.stereotype.Service;

import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.test.SpringTestDataTO;

/**
 *
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Service
public class AppEndpoint_LeafServices {

    @Inject
    private transient AppServiceCalculator _calculator;

    /**
     * Test "leaf service 1".
     */
    @MatsMapping(AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT1)
    public SpringTestDataTO leafService1(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(_calculator.multiplyByΠ(msg.number),
                msg.string + ':' + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT1);
    }

    /**
     * Test "leaf service 2".
     */
    @MatsMapping(AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT2)
    public SpringTestDataTO leafService2(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(_calculator.multiplyByΦ(msg.number),
                msg.string + ':' + AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFENDPOINT2);
    }
}
