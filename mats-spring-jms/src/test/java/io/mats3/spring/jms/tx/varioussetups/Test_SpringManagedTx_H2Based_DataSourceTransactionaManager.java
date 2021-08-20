package io.mats3.spring.jms.tx.varioussetups;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;
import io.mats3.spring.jms.tx.varioussetups.Test_SpringManagedTx_H2Based_AbstractBase_PlatformTransactionManager.SpringConfiguration_Abstract_PlatformTransactionManager;

/**
 * Testing Spring DB Transaction management, using DataSourceTransactionManager
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_DataSourceTransactionaManager
        extends Test_SpringManagedTx_H2Based_AbstractBase_PlatformTransactionManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_DataSourceTransactionManager extends SpringConfiguration_Abstract_PlatformTransactionManager {
        @Bean
        PlatformTransactionManager createDataSourceTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
