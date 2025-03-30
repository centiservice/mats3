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

package io.mats3.api_test.stdexampleflow;

import io.mats3.MatsFactory;

/**
 * Mats single-stage Endpoint which calculates <code>a*b</code>. A single Endpoint is a convenience method of creating
 * a Mats Endpoint with only a single stage. Since it only has a single stage, it does not need a state object (its
 * state is specified as void.class).
 *
 * @author Endre Stølsvik 2021-09-26 19:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class EndpointB {

    static void setupEndpoint(MatsFactory matsFactory) {
        matsFactory.single("EndpointB",
                EndpointBReplyDTO.class, EndpointBRequestDTO.class,
                (ctx, msg) -> {
                    double result = msg.a * msg.b;
                    return EndpointBReplyDTO.from(result);
                });
    }

    // ====== EndpointB Request and Reply DTOs

    public static class EndpointBRequestDTO {
        double a, b;

        static EndpointBRequestDTO from(double a, double b) {
            EndpointBRequestDTO ret = new EndpointBRequestDTO();
            ret.a = a;
            ret.b = b;
            return ret;
        }
    }

    public static class EndpointBReplyDTO {
        double result;

        static EndpointBReplyDTO from(double result) {
            EndpointBReplyDTO ret = new EndpointBReplyDTO();
            ret.result = result;
            return ret;
        }
    }

}
