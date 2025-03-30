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
import io.mats3.test.MatsTestLatch;

/**
 * A Terminator is a Mats Endpoint which does not produce any new Flow messages (i.e. no request, return, next).
 *
 * @author Endre Stølsvik 2021-09-26 19:34 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Terminator {

    static class InitiationState {
        boolean printResultOnConsole;

        static InitiationState of(boolean printResultOnConsole) {
            InitiationState ret = new InitiationState();
            ret.printResultOnConsole = printResultOnConsole;
            return ret;
        }
    }

    static volatile MatsTestLatch __latch;

    static void setupTerminator(MatsFactory matsFactory) {
        matsFactory.terminator("Terminator", InitiationState.class,
                _EndpointAReplyDTO.class, (ctx, state, msg) -> {
                    if (state.printResultOnConsole) {
                        System.out.println("The result is: [" + msg.result + "]");
                    }
                    // ?: If we have a MatsTestLatch present, then hit it.
                    MatsTestLatch latch = __latch;
                    if (latch != null) {
                        latch.resolve(state, msg);
                    }
                });
    }

    // ===== Imported DTOs for EndpointA

    static class _EndpointAReplyDTO {
        double result;
    }
}
