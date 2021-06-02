package io.mats3.spring.test.infrastructure;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.spring.test.MatsTestInfrastructureDbConfiguration;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.TestH2DataSource;
import io.mats3.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { MatsTestInfrastructureDbConfiguration.class })
public class Test_D_MatsTestInfrastructureDbConfiguration {

    // The Mats Test Infrastructure, with DataSource

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsTestLatch _matsTestLatch;

    @Inject
    private TestH2DataSource _dataSource;

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
    public void assert_Same_DataSource_was_used_to_make_MatsFactory() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        Common.assertSameDataSourceInMatsFactory(_matsFactory, _dataSource);
    }
}
