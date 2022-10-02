package io.mats3.api_test.concurrency;

import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.MatsStage;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that the logic with default concurrency and setting specific concurrency works as expected.
 *
 * @author Endre Stølsvik 2022-10-01 18:17 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_SettingConcurrency {
    @ClassRule
    public static Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT1 = MatsTestHelp.endpointId("endpoint1");
    private static final String ENDPOINT2 = MatsTestHelp.endpointId("endpoint2");

    @BeforeClass
    public static void setupEndpoints() {
        // These are not used for anything, outside of setting concurrency and reading it back.

        // NOTICE! We do not need the factory to start the StageProcessors in these tests.
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

    @Before
    public void resetAllToDefault() {
        // Set Factory to default
        MATS.getMatsFactory().getFactoryConfig().setConcurrency(0);
        MATS.getMatsFactory().getFactoryConfig().setInteractiveConcurrency(0);

        // Set all of Endpoint1 to default
        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        endpoint1.getEndpointConfig().setConcurrency(0);
        endpoint1.getEndpointConfig().setInteractiveConcurrency(0);
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            stage.getStageConfig().setConcurrency(0);
            stage.getStageConfig().setInteractiveConcurrency(0);
        }

        // Set all of Endpoint2 to default
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        endpoint2.getEndpointConfig().setConcurrency(0);
        endpoint2.getEndpointConfig().setInteractiveConcurrency(0);
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            stage.getStageConfig().setConcurrency(0);
            stage.getStageConfig().setInteractiveConcurrency(0);
        }
    }

    @Test
    public void checkDefaults() {
        // Get number of cpus - default should be 2 x this.
        int numCpus = Runtime.getRuntime().availableProcessors();
        int defaultConcurrency = numCpus * 2;

        // :: NO "ARRANGE"!

        // :: ASSERT

        // Default should be concurrency for everything
        Assert.assertEquals(defaultConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(defaultConcurrency, MATS.getMatsFactory().getFactoryConfig().getInteractiveConcurrency());

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        Assert.assertEquals(defaultConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(defaultConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            Assert.assertEquals(defaultConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(defaultConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(defaultConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(defaultConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(defaultConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(defaultConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void setFactoryNormalConcurrencyOnly() {
        int systemWideConcurrency = 42;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(systemWideConcurrency);

        // :: ASSERT
        // This should be concurrency for everything
        Assert.assertEquals(systemWideConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(systemWideConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        Assert.assertEquals(systemWideConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(systemWideConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            Assert.assertEquals(systemWideConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(systemWideConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(systemWideConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(systemWideConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(systemWideConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(systemWideConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void setFactoryNormalAndInteractive() {
        int factoryNormalConcurrency = 43;
        int factoryInteractiveConcurrency = 89;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);
        MATS.getMatsFactory().getFactoryConfig().setInteractiveConcurrency(factoryInteractiveConcurrency);

        // :: ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        Assert.assertEquals(factoryNormalConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(factoryInteractiveConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(factoryNormalConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(factoryInteractiveConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void overrideEndpoints_Normal() {
        int factoryNormalConcurrency = 44;

        int endpoint1Concurrency = 89;
        int endpoint2Concurrency = 123;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        endpoint1.getEndpointConfig().setConcurrency(endpoint1Concurrency);
        endpoint2.getEndpointConfig().setConcurrency(endpoint2Concurrency);

        // :: ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        Assert.assertEquals(endpoint1Concurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint1Concurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            Assert.assertEquals(endpoint1Concurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(endpoint1Concurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        Assert.assertEquals(endpoint2Concurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint2Concurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(endpoint2Concurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(endpoint2Concurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void overrideEndpoints_NormalAndInteractive() {
        int factoryNormalConcurrency = 45;

        int endpoint1NormalConcurrency = 37;
        int endpoint1InteractiveConcurrency = 58;
        int endpoint2Concurrency = 123;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        endpoint1.getEndpointConfig().setConcurrency(endpoint1NormalConcurrency);
        endpoint1.getEndpointConfig().setInteractiveConcurrency(endpoint1InteractiveConcurrency);
        endpoint2.getEndpointConfig().setConcurrency(endpoint2Concurrency);

        // :: ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        Assert.assertEquals(endpoint1NormalConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint1InteractiveConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint1.getStages()) {
            Assert.assertEquals(endpoint1NormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(endpoint1InteractiveConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }

        Assert.assertEquals(endpoint2Concurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint2Concurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(endpoint2Concurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(endpoint2Concurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void overrideEndpoints_AndStages_NormalAndInteractive() {
        int factoryNormalConcurrency = 46;

        int endpoint1NormalConcurrency = 37;
        int endpoint1InteractiveConcurrency = 58;
        int ep1stage0Normal = 1000;
        int ep1stage1Normal = 2000;
        int ep1stage1Interactive = 2001;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();
        endpoint1.getEndpointConfig().setConcurrency(endpoint1NormalConcurrency);
        endpoint1.getEndpointConfig().setInteractiveConcurrency(endpoint1InteractiveConcurrency);

        List<? extends MatsStage<?, ?, ?>> stages = endpoint1.getStages();
        MatsStage<?, ?, ?> matsStage0 = stages.get(0);
        MatsStage<?, ?, ?> matsStage1 = stages.get(1);

        matsStage0.getStageConfig().setConcurrency(ep1stage0Normal);
        matsStage1.getStageConfig().setConcurrency(ep1stage1Normal);
        matsStage1.getStageConfig().setInteractiveConcurrency(ep1stage1Interactive);

        // :: ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        Assert.assertEquals(endpoint1NormalConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(endpoint1InteractiveConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage0Normal, matsStage0.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage0Normal, matsStage0.getStageConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage1Normal, matsStage1.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage1Interactive, matsStage1.getStageConfig().getInteractiveConcurrency());

        // Endpoint 2
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(factoryNormalConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryNormalConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }

    @Test
    public void overrideStages_NormalAndInteractive() {
        int factoryNormalConcurrency = 47;
        int factoryInteractiveConcurrency = 88;

        int ep1stage0Normal = 1010;
        int ep1stage1Normal = 2010;
        int ep1stage1Interactive = 2011;

        // :: ARRANGE & ACT

        MATS.getMatsFactory().getFactoryConfig().setConcurrency(factoryNormalConcurrency);
        MATS.getMatsFactory().getFactoryConfig().setInteractiveConcurrency(factoryInteractiveConcurrency);

        MatsEndpoint<?, ?> endpoint1 = MATS.getMatsFactory().getEndpoint(ENDPOINT1).get();

        List<? extends MatsStage<?, ?, ?>> stages = endpoint1.getStages();
        MatsStage<?, ?, ?> matsStage0 = stages.get(0);
        MatsStage<?, ?, ?> matsStage1 = stages.get(1);

        matsStage0.getStageConfig().setConcurrency(ep1stage0Normal);
        matsStage1.getStageConfig().setConcurrency(ep1stage1Normal);
        matsStage1.getStageConfig().setInteractiveConcurrency(ep1stage1Interactive);

        // :: ASSERT

        Assert.assertEquals(factoryNormalConcurrency, MATS.getMatsFactory().getFactoryConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, MATS.getMatsFactory().getFactoryConfig()
                .getInteractiveConcurrency());

        Assert.assertEquals(factoryNormalConcurrency, endpoint1.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, endpoint1.getEndpointConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage0Normal, matsStage0.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage0Normal, matsStage0.getStageConfig().getInteractiveConcurrency());

        Assert.assertEquals(ep1stage1Normal, matsStage1.getStageConfig().getConcurrency());
        Assert.assertEquals(ep1stage1Interactive, matsStage1.getStageConfig().getInteractiveConcurrency());

        // Endpoint 2
        MatsEndpoint<?, ?> endpoint2 = MATS.getMatsFactory().getEndpoint(ENDPOINT2).get();
        Assert.assertEquals(factoryNormalConcurrency, endpoint2.getEndpointConfig().getConcurrency());
        Assert.assertEquals(factoryInteractiveConcurrency, endpoint2.getEndpointConfig().getInteractiveConcurrency());
        for (MatsStage<?, ?, ?> stage : endpoint2.getStages()) {
            Assert.assertEquals(factoryNormalConcurrency, stage.getStageConfig().getConcurrency());
            Assert.assertEquals(factoryInteractiveConcurrency, stage.getStageConfig().getInteractiveConcurrency());
        }
    }
}
