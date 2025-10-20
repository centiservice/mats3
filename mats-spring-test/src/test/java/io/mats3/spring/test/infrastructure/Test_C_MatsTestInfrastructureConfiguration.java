/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.spring.test.infrastructure;

import jakarta.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.MatsFactory.MatsWrapper;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.test.MatsTestLatch;
import io.mats3.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { MatsTestInfrastructureConfiguration.class })
public class Test_C_MatsTestInfrastructureConfiguration {

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsFuturizer _matsFuturizer;

    @Inject
    private MatsTestLatch _matsTestLatch;

    // Optionally depend on DataSource - IT SHALL NOT BE HERE!
    @Inject
    protected ObjectProvider<DataSource> _dataSource;

    @Test
    public void assertMatsInfrastructureInjected() {
        Assert.assertNotNull("MatsFactory should be in Spring context", _matsFactory);
        Assert.assertNotNull("MatsInitiator should be in Spring context", _matsInitiator);
        Assert.assertNotNull("MatsFuturizer should be in Spring context", _matsFuturizer);
        Assert.assertNotNull("MatsTestLatch should be in Spring context", _matsTestLatch);
    }

    @Test
    public void assertThatNoDataSourceIsCreated() {
        Assert.assertNull("DataSource should not be in Spring context", _dataSource.getIfAvailable());
    }

    @Test
    public void test_MatsFactory_wrapped_from_TestSpringMatsFactoryProvider() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // The injected should be a wrapper, NOT implementation.
        Assert.assertTrue(_matsFactory instanceof MatsWrapper);
        Assert.assertFalse(_matsFactory instanceof JmsMatsFactory);

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = _matsFactory.unwrapFully();
        // The unwrapped instance should not be the same as the wrapper
        Assert.assertNotSame(_matsFactory, unWrappedMatsFactory);

        // The unwrapped instance should be the implementation, NOT a wrapper
        Assert.assertFalse(unWrappedMatsFactory instanceof MatsWrapper);
        Assert.assertTrue(unWrappedMatsFactory instanceof JmsMatsFactory);
    }

    @Test
    public void test_double_wrapping_of_MatsFactory() {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // Wrap the injected MatsFactory again - so now it is double-wrapped
        MatsFactory doubleWrapped = new MatsFactoryWrapper(_matsFactory);

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = doubleWrapped.unwrapFully();
        // The unwrapped instance should not be the same as the wrapper
        Assert.assertNotSame(doubleWrapped, unWrappedMatsFactory);

        // The unwrapped instance should be the implementation, NOT a wrapper
        Assert.assertFalse(unWrappedMatsFactory instanceof MatsWrapper);
        Assert.assertTrue(unWrappedMatsFactory instanceof JmsMatsFactory);
    }
}
