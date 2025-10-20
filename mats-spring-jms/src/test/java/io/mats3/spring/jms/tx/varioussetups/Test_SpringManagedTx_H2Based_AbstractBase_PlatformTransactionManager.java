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

package io.mats3.spring.jms.tx.varioussetups;

import jakarta.jms.ConnectionFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;
import io.mats3.test.MatsTestFactory;

/**
 * Abstract test for Spring DB Transaction management, creating a MatsFactory using a PlatformTransactionManager,
 * subclasses specifies how that is created and which type it is (DataSourceTxMgr or HibernateTxMgr).
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
public abstract class Test_SpringManagedTx_H2Based_AbstractBase_PlatformTransactionManager extends
        Test_SpringManagedTx_H2Based_AbstractBase {
    @Configuration
    @EnableMats
    static abstract class SpringConfiguration_Abstract_PlatformTransactionManager
            extends SpringConfiguration_AbstractBase {
        @Bean
        protected MatsFactory createMatsFactory(PlatformTransactionManager platformTransactionManager,
                ConnectionFactory connectionFactory, MatsSerializer matsSerializer) {

            // Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
            JmsMatsJmsSessionHandler sessionPool = JmsMatsJmsSessionHandler_Pooling.create(connectionFactory);
            JmsMatsTransactionManager txMgrSpring = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                    platformTransactionManager);

            JmsMatsFactory matsFactory = JmsMatsFactory.createMatsFactory(this.getClass().getSimpleName(),
                    "*testing*", sessionPool, txMgrSpring, matsSerializer);
            // For the MULTIPLE test scenario, it makes sense to test concurrency, so we go for 5.
            matsFactory.getFactoryConfig().setConcurrency(5);
            // Set standard test max delivery attempts
            matsFactory.setMatsManagedDlqDivert(MatsTestFactory.TEST_MAX_DELIVERY_ATTEMPTS);
            return matsFactory;
        }
    }
}
