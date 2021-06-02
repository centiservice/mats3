package io.mats3.spring.jms.tx.varioussetups;

import javax.sql.DataSource;

import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

/**
 * Testing Spring DB Transaction management, using HibernateTransactionManager where the DataSource is wrapped only for
 * the TransactionManager - for the other users taking DataSource from Spring Context (JdbcTemplate etc), it is not
 * wrapped.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_HibernateTransactionManager_WrappedDsOnlyForTxMgr
        extends Test_SpringManagedTx_H2Based_HibernateTransactionManager {
    @Configuration
    @EnableMats
    static class SpringConfiguration_Hibernate_WrappedDsOnlyForTxMgr extends SpringConfiguration_HibernateTxMgr {
        /**
         * This ensures that ONLY the TransactionManager gets the wrapped DataSource.
         */
        @Bean
        LocalSessionFactoryBean createHibernateSessionFactory(DataSource dataSource) {
            // This is a FactoryBean that creates a Hibernate SessionFactory working with Spring's HibernateTxMgr
            LocalSessionFactoryBean factory = new LocalSessionFactoryBean();
            // Setting the DataSource
            factory.setDataSource(
                    JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.wrapLazyConnectionDatasource(dataSource));
            // Setting the single annotated Entity test class we have
            factory.setAnnotatedClasses(DataTableDbo.class);
            return factory;
        }
    }
}
