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
 * Mats single-stage Endpoint which calculates <code>a/b</code>.
 *
 * @author Endre Stølsvik 2021-09-26 19:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class EndpointD {
    static void setupEndpoint(MatsFactory matsFactory) {
        matsFactory.single("EndpointD",
                EndpointDReplyDTO.class, EndpointDRequestDTO.class,
                (ctx, msg) -> {
                    double result = msg.a / msg.b;
                    return EndpointDReplyDTO.from(result);
                });
    }

    // ====== EndpointD Request and Reply DTOs

    public static class EndpointDRequestDTO {
        double a, b;

        static EndpointDRequestDTO from(double a, double b) {
            EndpointDRequestDTO ret = new EndpointDRequestDTO();
            ret.a = a;
            ret.b = b;
            return ret;
        }
    }

    public static class EndpointDReplyDTO {
        double result;

        static EndpointDReplyDTO from(double result) {
            EndpointDReplyDTO ret = new EndpointDReplyDTO();
            ret.result = result;
            return ret;
        }
    }

}
