package io.mats3.api_test.concurrency;

import java.util.List;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsStage;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Single-test copy of {@link Test_SettingConcurrency}, but where we actually assert that the Stages actually pick up
 * these settings by creating the correct number of StageProcessors.
 *
 * @author Endre Stølsvik 2022-10-02 12:16 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_SettingConcurrencyAndAssertStageProcessors {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT1 = MatsTestHelp.endpointId("endpoint1");
    private static final String ENDPOINT2 = MatsTestHelp.endpointId("endpoint2");

    @BeforeClass
    public static void setupEndpoints() {
        // These are not used for anything, outside of setting concurrency, reading it back, and checking threads

        // NOTICE! Don't start factory until we've set the concurrency values.
        MATS.getMatsFactory().holdEndpointsUntilFactoryIsStarted();

        // :: Endpoint 1
        MatsEndpoint<DataTO, StateTO> multi1 = MATS.getMatsFactory().staged(ENDPOINT1, DataTO.class, StateTO.class);
        multi1.stage(DataTO.class, (ctx, state, msg) -> {
        });
        multi1.lastStage(DataTO.class, (ctx, state, msg) -> null);

        // :: Endpoint 2
        MatsEndpoint<DataTO, StateTO> multi2 = MATS.getMatsFactory().staged(ENDPOINT2, DataTO.class, StateTO.class);
        multi2.stage(DataTO.class, (ctx, state, msg) -> {
        });
        multi2.lastStage(DataTO.class, (ctx, state, msg) -> null);
    }

    @Test
    public void actuallyAssertStageProcessorCounts() throws InterruptedException {
        int factoryNormalConcurrency = 2;
        int factoryInteractiveConcurrency = 3;

        int endpoint1NormalConcurrency = 4;
        int endpoint1InteractiveConcurrency = 5;
        int ep1stage0Normal = 6;
        int ep1stage1Normal = 7;
        int ep1stage1Interactive = 8;

        // :: ARRANGE

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);
        MATS.getMatsFactory().getFactoryConfig().setInteractiveConcurrency(factoryInteractiveConcurrency);

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        endpoint1.getEndpointConfig().setConcurrency(endpoint1NormalConcurrency);
        endpoint1.getEndpointConfig().setInteractiveConcurrency(endpoint1InteractiveConcurrency);

        List<? extends MatsStage<?, ?, ?>> stages = endpoint1.getStages();
        MatsStage<?, ?, ?> matsEndpoint1Stage0 = stages.get(0);
        MatsStage<?, ?, ?> matsEndpoint1Stage1 = stages.get(1);

        matsEndpoint1Stage0.getStageConfig().setConcurrency(ep1stage0Normal);
        matsEndpoint1Stage1.getStageConfig().setConcurrency(ep1stage1Normal);
        matsEndpoint1Stage1.getStageConfig().setInteractiveConcurrency(ep1stage1Interactive);

        // :: PRELIMINARY ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        Assert.assertEquals(endpoint1NormalConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint1InteractiveConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage0Normal, matsEndpoint1Stage0.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage0Normal, matsEndpoint1Stage0.getStageConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage1Normal, matsEndpoint1Stage1.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage1Interactive, matsEndpoint1Stage1.getStageConfig().getInteractiveConcurrency());

        // Endpoint 2
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(factoryNormalConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(factoryInteractiveConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        // :: ACT

        // Now start the MatsFactory
        MATS.getMatsFactory().start();

        // NOTICE: The factory.start() have already ensured that all stages have entered their receive-loops, i.e.
        // at least one thread running.

        // ----- We are now ensures that at least 1 thread is consuming for each stage. But we need them all.

        // Give it 1 second more to get the rest of threads running.
        int chillMillis = 1000;
        log.info("##### Waiting [" + chillMillis + "] ms more to get all threads up and running.");
        Thread.sleep(chillMillis);

        // :: ASSERT

        stages = endpoint2.getStages();
        MatsStage<?, ?, ?> matsEndpoint2Stage0 = stages.get(0);
        MatsStage<?, ?, ?> matsEndpoint2Stage1 = stages.get(1);

        // -- Endpoint 1 has specific for each stage

        // - Stage 0 has only normal set, and interactive should thus be same, thus "x 2"
        Assert.assertEquals(ep1stage0Normal * 2, matsEndpoint1Stage0.getStageConfig().getRunningStageProcessors());
        // - Stage 1 has both normal and interactive set.
        Assert.assertEquals(ep1stage1Normal + ep1stage1Interactive, matsEndpoint1Stage1.getStageConfig()
                .getRunningStageProcessors());

        // Endpoint 2 uses settings from factory

        Assert.assertEquals(factoryNormalConcurrency + factoryInteractiveConcurrency, matsEndpoint2Stage0
                .getStageConfig().getRunningStageProcessors());
        Assert.assertEquals(factoryNormalConcurrency + factoryInteractiveConcurrency, matsEndpoint2Stage1
                .getStageConfig().getRunningStageProcessors());

    }

}
