package io.mats3.spring.jms.tx.varioussetups;

import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Testing Spring DB Transaction management, using DataSourceTransactionManager where the DataSource is wrapped before
 * put into Spring context.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_DataSourceTransactionaManager_WrappedDsInSpringContext
        extends Test_SpringManagedTx_H2Based_DataSourceTransactionaManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_DsTxMgr_WrappedDsInSpringContext extends SpringConfiguration_DataSourceTxMgr {
        /**
         * This ensures that both the JdbcTemplate etc, AND the TransactionManager, gets the wrapped DataSource.
         */
        @Override
        protected DataSource optionallyWrapDataSource(DataSource dataSource) {
            return JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.wrapLazyConnectionDatasource(dataSource);
        }
    }
}
