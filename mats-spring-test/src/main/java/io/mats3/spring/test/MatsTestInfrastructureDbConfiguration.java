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

package io.mats3.spring.test;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

import io.mats3.spring.EnableMats;
import io.mats3.spring.test.MatsTestInfrastructureDbConfiguration.MatsTestH2DataSourceConfiguration;
import io.mats3.test.TestH2DataSource;

/**
 * Same as {@link MatsTestInfrastructureConfiguration}, but includes a H2 DataSource, as configured by
 * {@link MatsTestH2DataSourceConfiguration}, which uses the {@link TestH2DataSource#createStandard()} convenience
 * method.
 * 
 * @author Endre Stølsvik - 2020-11 - http://endre.stolsvik.com
 */
@EnableMats
@Configuration
@Import(MatsTestH2DataSourceConfiguration.class)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class MatsTestInfrastructureDbConfiguration extends MatsTestInfrastructureConfiguration {

    @Configuration
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static class MatsTestH2DataSourceConfiguration {
        @Bean
        protected TestH2DataSource testH2DataSource() {
            return TestH2DataSource.createStandard();
        }
    }
}
