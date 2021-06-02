package io.mats3.lib_test.concurrency;

import org.junit.BeforeClass;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;

/**
 * Very similar to {@link Test_ConcurrencyPerStage}, but with two stages for the service, where the first has 8
 * processors (sleeping 250ms), and the next has 4 (sleeping 125ms) - the total time for 8 messages through the service
 * should then be a tad over 500 ms, not (a little over) 2000 ms as if there was only 1 processor for each stage (the
 * pipelining with two stages reduces it from 4 seconds to 2). Implicitly tests "lambdaconfig" per stage (as well as for
 * endpoint).
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator] x 1, firing off 8 requests.
 *     [Service S0 (init)] x 8 StageProcessors (sleeping 250 ms) - next
 *     [Service S1 (last)] x 4 StageProcessors (sleeping 125 ms) - reply
 * [Terminator] x 1 StageProcessor, getting all the 8 replies, counting down a 8-latch.
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ConcurrencyPerStage extends ATest_AbstractConcurrency {

    @BeforeClass
    public static void setupService() {
        // :: Configuring endpoint to use 4 as concurrency.
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class,
                (endpointConfig) -> {
                    endpointConfig.setConcurrency(CONCURRENCY_TEST / 2);
                });
        // :: Configuring this stage to override concurrency from 4 to 8.
        ep.stage(DataTO.class,
                (stageConfig) -> {
                    stageConfig.setConcurrency(CONCURRENCY_TEST);
                } ,
                (context, state, dto) -> {
                    // Emulate some lengthy processing...
                    MatsTestHelp.takeNap(PROCESSING_TIME / 2); // Half of total
                    context.next(new DataTO(dto.number * 2, dto.string + ":InitialStage"));
                });
        // :: This stage uses the Endpoint's configured concurrency of 4.
        ep.lastStage(DataTO.class,
                (context, state, dto) -> {
                    // Emulate some lengthy processing...
                    MatsTestHelp.takeNap(PROCESSING_TIME / (2 * 2)); // Half of total, and half number of procs of CONCURRENCY_TEST
                    return new DataTO(dto.number * 3, dto.string + ":FromService:" + ((int) dto.number / 2));
                });
    }

    @Test
    public void doTest() throws InterruptedException {
        performTest(6, "TheAnswer:InitialStage:FromService:");
    }
}
