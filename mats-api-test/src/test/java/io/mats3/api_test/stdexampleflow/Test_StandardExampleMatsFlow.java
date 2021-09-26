package io.mats3.api_test.stdexampleflow;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.test.junit.Rule_Mats;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Runs a test using the Endpoint A-D + Terminator and Initiator presented in the illustration in Mats's README.md.
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

    @Test
    public void testCalculation1() throws InterruptedException {
        MatsInitiator initiator = MATS.getMatsInitiator();
        Initiator.initiateAndWaitForReply(initiator);
    }
}
