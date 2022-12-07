package io.mats3.api_test.stdexampleflow;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.Assert;

import io.mats3.MatsInitiator;
import io.mats3.api_test.stdexampleflow.Terminator.InitiationState;
import io.mats3.api_test.stdexampleflow.Terminator._EndpointAReplyDTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.MatsFuturizer;

/**
 * This class is really just to have a representation of all the elements of the Mats' README.md figure. Using the
 * test-specific {@link MatsTestLatch} is not an ordinary way to synchronize/block on the result from a Mats processing.
 * An alternative, more correct way to bridge synchronous code over to the asynchronous Mats fabric is to use the
 * {@link MatsFuturizer}.
 *
 * @author Endre Stølsvik 2021-09-26 21:39 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Initiator {

    static void initiateAndWaitForReply(MatsInitiator initiator) throws InterruptedException {
        // Set the latch in the Terminator. This is NOT how to code multi threaded, but will suffice for this test.
        Terminator.__latch = new MatsTestLatch();

        initiator.initiateUnchecked(init -> init
                .traceId("TraceId:" + ThreadLocalRandom.current().nextLong())
                .from("InitiateAndWaitForReply")
                .to("EndpointA")
                .replyTo("Terminator", InitiationState.of(true))
                .request(_EndpointARequestDTO.from(2, 3, 4, 5, 6)));

        Result<InitiationState, _EndpointAReplyDTO> result = Terminator.__latch.waitForResult();
        Assert.assertEquals(true, result.getState().printResultOnConsole);
        Assert.assertEquals(2d * 3d - ((4d / 5d) + 6d), result.getData().result, 0);
    }

    // ===== Imported DTOs for EndpointA

    static class _EndpointARequestDTO {
        double a, b, c, d, e;

        public static _EndpointARequestDTO from(double a, double b, double c, double d, double e) {
            _EndpointARequestDTO ret = new _EndpointARequestDTO();
            ret.a = a;
            ret.b = b;
            ret.c = c;
            ret.d = d;
            ret.e = e;
            return ret;
        }
    }
}
