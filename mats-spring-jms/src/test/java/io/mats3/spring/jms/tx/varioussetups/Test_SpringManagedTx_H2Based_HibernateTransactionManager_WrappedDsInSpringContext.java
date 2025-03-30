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

import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Testing Spring DB Transaction management, using HibernateTransactionManager where the DataSource is wrapped before
 * put into Spring context.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_HibernateTransactionManager_WrappedDsInSpringContext
        extends Test_SpringManagedTx_H2Based_HibernateTransactionManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_HibernateTxMgr_WrappedDsInSpringContext extends SpringConfiguration_HibernateTxMgr {
        /**
         * This ensures that both the JdbcTemplate etc, AND the TransactionManager, gets the wrapped DataSource.
         */
        @Override
        protected DataSource optionallyWrapDataSource(DataSource dataSource) {
            return JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.wrapLazyConnectionDatasource(dataSource);
        }
    }
}
