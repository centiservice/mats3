package io.mats3.api_test.concurrency;

import org.junit.BeforeClass;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
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
 * [Initiator] x 1, firing off NUM_MESSAGES requests.
 *     [Service S0 (init)] x CONCURRENCY StageProcessors (sleeping PROCESSING_TIME / 2 ms) - next
 *     [Service S1 (last)] x CONCURRENCY / 2 StageProcessors (sleeping PROCESSING_TIME / 4 ms) - reply
 * [Terminator] x 1 StageProcessor, getting all the NUM_MESSAGES replies, counting down a latch.
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ConcurrencyPerStage extends ATest_AbstractConcurrency {

    @BeforeClass
    public static void setupService() {
        // :: Configuring endpoint to use half as concurrency.
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(ENDPOINT, DataTO.class, StateTO.class,
                (endpointConfig) -> {
                    endpointConfig.setConcurrency(CONCURRENCY / 2);
                });

        // ::: TWO STAGES

        // :: 1. Configuring this stage to use the large concurrency.
        ep.stage(DataTO.class,
                (stageConfig) -> {
                    stageConfig.setConcurrency(CONCURRENCY);
                } ,
                (context, state, dto) -> {
                    // Emulate some lengthy processing...
                    MatsTestHelp.takeNap(PROCESSING_TIME / 2); // Half of total
                    context.next(new DataTO(dto.number * 2, dto.string + ":InitialStage"));
                });
        // :: 2. This stage uses the Endpoint's configured half concurrency.
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
