package io.mats3.spring.jms.tx.varioussetups;

import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.spring.EnableMats;

/**
 * Testing Spring DB Transaction management, using DataSourceTransactionManager
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_DataSourceTransactionaManager
        extends Test_SpringManagedTx_H2Based_Abstract_PlatformTransactionManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_DataSourceTxMgr extends SpringConfiguration_AbstractPlatformTransactionManager {
        @Bean
        PlatformTransactionManager createDataSourceTransactionaManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
