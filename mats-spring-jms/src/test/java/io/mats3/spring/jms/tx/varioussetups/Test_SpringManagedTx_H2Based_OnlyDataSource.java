package io.mats3.spring.jms.tx.varioussetups;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Testing Spring DB Transaction management, supplying DataSource so that a DataSourceTransactionManager is made
 * internally.
 *
 * @author Endre Stølsvik 2019-05-06 21:35 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_OnlyDataSource extends Test_SpringManagedTx_H2Based_AbstractBase {

    private static final Logger log = LoggerFactory.getLogger(Test_SpringManagedTx_H2Based_OnlyDataSource.class);

    @Configuration
    @EnableMats
    static class SpringConfiguration_DataSource extends SpringConfiguration_AbstractBase {
        @Bean
        protected MatsFactory createMatsFactory(DataSource dataSource,
                ConnectionFactory connectionFactory, MatsSerializer matsSerializer) {
            // Create the JMS and Spring DataSourceTransactionManager-backed JMS MatsFactory.
            JmsMatsJmsSessionHandler sessionPooler = JmsMatsJmsSessionHandler_Pooling.create(connectionFactory);
            JmsMatsTransactionManager txMgrSpring = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.create(
                    dataSource);

            JmsMatsFactory matsFactory = JmsMatsFactory.createMatsFactory(this.getClass().getSimpleName(),
                    "*testing*", sessionPooler, txMgrSpring, matsSerializer);
            // For the MULTIPLE test scenario, it makes sense to test concurrency, so we go for 5.
            matsFactory.getFactoryConfig().setConcurrency(5);
            return matsFactory;
        }

    }
}
