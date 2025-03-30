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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Testing Spring DB Transaction management, using DataSourceTransactionManager where the DataSource is wrapped only for
 * the TransactionManager - for the other users taking DataSource from Spring Context (JdbcTemplate etc), it is not
 * wrapped.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_DataSourceTransactionaManager_WrappedDsOnlyForTxMgr
        extends Test_SpringManagedTx_H2Based_DataSourceTransactionaManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_DsTxMgr_WrappedDsOnlyForTxMgr extends SpringConfiguration_DataSourceTransactionManager {
        /**
         * This ensures that ONLY the TransactionManager gets the wrapped DataSource.
         */
        @Bean
        @Override
        PlatformTransactionManager createDataSourceTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(JmsMatsTransactionManager_JmsAndSpringManagedSqlTx
                    .wrapLazyConnectionDatasource(dataSource));
        }
    }
}
