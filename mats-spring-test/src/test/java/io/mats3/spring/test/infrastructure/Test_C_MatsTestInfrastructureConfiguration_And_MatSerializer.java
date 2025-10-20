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
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.spring.test.infrastructure.Test_C_MatsTestInfrastructureConfiguration_And_MatSerializer.TestConfiguration;
import io.mats3.test.MatsTestLatch;
import io.mats3.util.MatsFuturizer;

/**
 * Tests that if we make a {@link MatsSerializer} in the Spring Context in the test, the
 * {@link MatsTestInfrastructureConfiguration} will pick it up
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { TestConfiguration.class, MatsTestInfrastructureConfiguration.class })
public class Test_C_MatsTestInfrastructureConfiguration_And_MatSerializer {

    private static MatsSerializer _matsSerializer_Configuration;

    /**
     * Create a {@link MatsSerializer}, which shall be picked up by {@link MatsTestInfrastructureConfiguration}.
     */
    protected static class TestConfiguration {
        @Bean
        public MatsSerializer matsSerializer() {
            // Make specific MatsSerializer (in Spring Context, which should be picked up).
            _matsSerializer_Configuration = MatsSerializerJson.create();
            return _matsSerializer_Configuration;
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
    public void assertMatsSerializerInjected() {
        Assert.assertNotNull("MatsSerializer should be in Spring context", _matsSerializer);
    }

    @Test
    public void assertThatNoDataSourceIsCreated() {
        Assert.assertNull("DataSource should not be in Spring context", _dataSource.getIfAvailable());
    }

    @Test
    public void assert_Specific_MatsSerializer_was_used_to_make_MatsFactory() {
        // Check that the injected MatsSerializer it is the one that should have been constructed in the Configuration
        Assert.assertSame(_matsSerializer_Configuration, _matsSerializer);

        Common.assertSameMatsSerializerInMatsFactory(_matsFactory, _matsSerializer);
    }
}
