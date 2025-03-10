package io.mats3.spring.test.infrastructure;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.TestH2DataSource;
import io.mats3.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} and a {@link DataSource} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_A_MatsTestContext_And_MS_plus_DS {

    private static MatsSerializer _matsSerializer_Configuration;
    private static TestH2DataSource _h2DataSource_Configuration;

    /**
     * Create a {@link MatsSerializer} and a {@link DataSource}, which shall be picked up by
     * {@link MatsTestInfrastructureConfiguration}.
     */
    @Configuration
    protected static class TestConfiguration {
        @Bean
        public MatsSerializer matsSerializer() {
            // Make specific MatsSerializer (in Spring Context, which should be picked up).
            _matsSerializer_Configuration = MatsSerializerJson.create();
            return _matsSerializer_Configuration;
        }

        @Bean
        public TestH2DataSource specificH2DataSource() {
            _h2DataSource_Configuration = TestH2DataSource.createStandard();
            return _h2DataSource_Configuration;
        }
    }

    // The Mats Test Infrastructure

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsTestLatch _matsTestLatch;

    // From @Configuration

    @Inject
    private MatsSerializer _matsSerializer;

    @Inject
    private DataSource _dataSource;

    @Test
    public void assertMatsInfrastructureInjected() {
        Assert.assertNotNull("MatsFactory should be in Spring context", _matsFactory);
        Assert.assertNotNull("MatsInitiator should be in Spring context", _matsInitiator);
        Assert.assertNotNull("MatsFuturizer should be in Spring context", _matsFuturizer);
        Assert.assertNotNull("MatsTestLatch should be in Spring context", _matsTestLatch);
    }

    @Test
    public void assertDataSourceInjected() {
        Assert.assertNotNull("DataSource should be in Spring context", _dataSource);
    }

    @Test
    public void assert_Specific_MatsSerializer_was_used_to_make_MatsFactory() {
        // Check that the injected MatsSerializer it is the one that should have been constructed in the Configuration
        Assert.assertSame(_matsSerializer_Configuration, _matsSerializer);

        Common.assertSameMatsSerializerInMatsFactory(_matsFactory, _matsSerializer);
    }

    @Test
    public void assert_Specific_DataSource_was_used_to_make_MatsFactory() {
        // Check that the injected DataSource it is the one that should have been constructed in the Configuration
        Assert.assertSame(_h2DataSource_Configuration, _dataSource);

        Common.assertSameDataSourceInMatsFactory(_matsFactory, _dataSource);
    }
}
