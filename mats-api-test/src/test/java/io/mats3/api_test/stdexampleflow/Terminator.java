package io.mats3.api_test.stdexampleflow;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestLatch;

/**
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
