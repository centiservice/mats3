package io.mats3.api_test.stdexampleflow;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.api_test.stdexampleflow.Initiator._EndpointARequestDTO;
import io.mats3.api_test.stdexampleflow.Terminator._EndpointAReplyDTO;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Runs a test using Endpoint A-D + Terminator and Initiator presented in the illustration in Mats's README.md.
 *
 * @author Endre Stølsvik 2021-09-26 19:33 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_StandardExampleMatsFlow {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @BeforeClass
    public static void setupAllEndpoints() {
        MatsFactory matsFactory = MATS.getMatsFactory();

        EndpointA.setupEndpoint(matsFactory);
        EndpointB.setupEndpoint(matsFactory);
        EndpointC.setupEndpoint(matsFactory);
        EndpointD.setupEndpoint(matsFactory);
        Terminator.setupTerminator(matsFactory);
    }

    /**
     * Runs the code as on the illustration in Mats' README.md, from an Initiator which targets the Reply to a
     * Terminator.
     */
    @Test
    public void testCalculation_UsingTerminator_and_MatsTestLatch() throws InterruptedException {
        MatsInitiator matsInitiator = MATS.getMatsInitiator();

        Initiator.initiateAndWaitForReply(matsInitiator);
    }

    /**
     * Invokes "EndpointA" using the {@link MatsFuturizer}. The Futurizer has an onboard terminator which receives the
     * reply from EndpointA and resolves the returned future. This is a more realistic way to interface with the
     * asynchronous Mats fabric from e.g. a synchronous HTTP endpoint like a Servlet or Spring {@code @RequestMapping}.
     */
    @Test
    public void testCalculation_UsingMatsFuturizer() throws InterruptedException, ExecutionException, TimeoutException {
        // :: ARRANGE
        MatsFuturizer matsFuturizer = MATS.getMatsFuturizer();
        // Create Request
        _EndpointARequestDTO request = _EndpointARequestDTO.from(3, 4, 5, 6, 7);

        // :: ACT
        // "Futurize" a Request to EndpointA
        CompletableFuture<Reply<_EndpointAReplyDTO>> future = matsFuturizer.futurizeNonessential(
                "TraceId_" + Long.toString(ThreadLocalRandom.current().nextLong(), 36),
                "testCalculation_UsingMatsFuturizer",
                "EndpointA",
                _EndpointAReplyDTO.class,
                request);
        // .. block here till we get the Reply from invoked EndpointA (or timeout)
        Reply<_EndpointAReplyDTO> reply = future.get(10, TimeUnit.SECONDS);

        // :: ASSERT
        Assert.assertEquals(3d * 4d - ((5d / 6d) + 7d), reply.getReply().result, 0);
    }
}
