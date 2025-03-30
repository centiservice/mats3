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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.sql.DataSource;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.spring.EnableMats;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.jms.tx.SpringTestDataTO;
import io.mats3.spring.jms.tx.SpringTestStateTO;
import io.mats3.spring.jms.tx.varioussetups.Test_SpringManagedTx_H2Based_AbstractBase_PlatformTransactionManager.SpringConfiguration_Abstract_PlatformTransactionManager;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.RandomString;

/**
 * Testing Spring DB Transaction management, using HibernateTransactionManager - also including tests using all of
 * Hibernate/JPA, Spring JDBC and Plain JDBC in a single Stage, all being tx-managed from the sole
 * HibernateTransactionManager.
 *
 * @author Endre Stølsvik 2020-06-05 00:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_SpringManagedTx_H2Based_HibernateTransactionManager
        extends Test_SpringManagedTx_H2Based_AbstractBase_PlatformTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(
            Test_SpringManagedTx_H2Based_HibernateTransactionManager.class);

    public static final String ENDPOINT_HIBERNATE = "mats.spring.SpringManagedTx_H2Based_Hibernate";

    @Configuration
    @EnableMats
    static class SpringConfiguration_HibernateTxMgr
            extends SpringConfiguration_Abstract_PlatformTransactionManager {

        @Bean
        LocalSessionFactoryBean createHibernateSessionFactory(DataSource dataSource) {
            // This is a FactoryBean that creates a Hibernate SessionFactory working with Spring's HibernateTxMgr
            LocalSessionFactoryBean factory = new LocalSessionFactoryBean();
            // Setting the DataSource
            factory.setDataSource(dataSource);
            // Setting the single annotated Entity test class we have
            factory.setAnnotatedClasses(DataTableDbo.class);
            return factory;
        }

        @Bean
        HibernateTransactionManager createHibernateTransactionaManager(SessionFactory sessionFactory) {
            // Note: don't need to .setDataSource() since we use LocalSessionFactoryBean, read JavaDoc of said method.
            return new HibernateTransactionManager(sessionFactory);
        }

        @Inject
        private SessionFactory _sessionFactory;

        @Inject
        private DataSource _dataSource;

        /**
         * Setting up the single-stage endpoint that will store a row in the database using Hibernate, Spring JDBC and
         * Plain JDBC, but which will throw afterwards if the request DTO says so.
         */
        @MatsMapping(endpointId = ENDPOINT_HIBERNATE)
        public SpringTestDataTO springMatsSingleEndpoint_Hibernate(ProcessContext<SpringTestDataTO> context,
                SpringTestDataTO msg) {
            log.info("Incoming message for '" + ENDPOINT + "': DTO:[" + msg + "], context:\n" + context);

            // :: Insert row in database using Hibernate/JPA
            String valueHibernate = ENDPOINT_HIBERNATE + '[' + msg.string + "]-Hibernate";
            DataTableDbo data = new DataTableDbo(valueHibernate);
            // Getting current Hibernate Session (must not close it)
            Session currentSession = _sessionFactory.getCurrentSession();
            currentSession.save(data);

            // :: .. and also insert row using Spring JDBC
            String valueSpringJdbc = ENDPOINT_HIBERNATE + '[' + msg.string + "]-SpringJdbc";
            _jdbcTemplate.update("INSERT INTO datatable VALUES (?)", valueSpringJdbc);

            // :: .. and finally insert row using pure JDBC
            String valuePlainJdbc = ENDPOINT_HIBERNATE + '[' + msg.string + "]-PlainJdbc";
            // Note how we're using DataSourceUtils to get the Spring Managed Transactional Connection.
            // .. and do NOT close it afterwards, but use DataSourceUtils.releaseConnection instead
            // Notice how this is exactly like JdbcTemplate.execute() does it.
            Connection con = DataSourceUtils.getConnection(_dataSource);
            try {
                PreparedStatement stmt = con.prepareStatement("INSERT INTO datatable VALUES (?)");
                stmt.setString(1, valuePlainJdbc);
                stmt.execute();
                stmt.close();
                // NOTE: Must NOT close Connection, but can "release" it back using DataSourceUtils:
                // ("Release" does a close if outside Spring Managed TX, and does NOT close if inside a TX)
                DataSourceUtils.releaseConnection(con, _dataSource);
            }
            catch (SQLException e) {
                throw new RuntimeException(e);
            }

            // Assert that this is the same Connection instance that we would get from the ProcessContext
            Optional<Connection> contextAttributeConnection = context.getAttribute(Connection.class);
            Assert.assertSame(con, contextAttributeConnection.get());

            // ?: Are we instructed to throw now, thereby rolling back the above changes?
            if (msg.string.startsWith(THROW)) {
                // -> Yes, we should throw - and this should rollback all DB, eventually DLQing the message.
                log.info("Asked to throw RuntimeException, and that we do!");
                throw new RuntimeException("This RTE should make the SQL INSERT rollback!");
            }
            return new SpringTestDataTO(msg.number * 2, msg.string);
        }
    }

    /**
     * The Hibernate DBO class. <i>Shudder..</i>
     */
    @Entity
    @Table(name = "datatable")
    public static class DataTableDbo {
        @Id
        @Column(name = "data")
        private String data;

        public DataTableDbo() {
        }

        public DataTableDbo(String data) {
            this.data = data;
        }

        public void setData(String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }

        @Override
        public String toString() {
            return "DataTableDbo[data=" + data + "]";
        }
    }

    @Test
    public void test_Hibernate_Good() throws SQLException {
        SpringTestDataTO dto = new SpringTestDataTO(27, GOOD);
        String traceId = "testGood_TraceId:" + RandomString.randomCorrelationId();
        sendMessage(ENDPOINT_HIBERNATE, dto, traceId);

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(traceId, result.getContext().getTraceId());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string), result.getData());

        // :: Assert against the data from the database - it should be there!
        List<String> expected = new ArrayList<>(4);
        // Add in expected order based on "ORDER BY data"
        expected.add(TERMINATOR + '[' + GOOD + ']');
        expected.add(ENDPOINT_HIBERNATE + '[' + GOOD + "]-Hibernate");
        expected.add(ENDPOINT_HIBERNATE + '[' + GOOD + "]-PlainJdbc");
        expected.add(ENDPOINT_HIBERNATE + '[' + GOOD + "]-SpringJdbc");

        Assert.assertEquals(expected, getDataFromDataTable());
    }

    @Test
    public void test_Hibernate_ThrowsShouldRollback() throws SQLException {
        SpringTestDataTO dto = new SpringTestDataTO(13, THROW);
        String traceId = "testBad_TraceId:" + RandomString.randomCorrelationId();
        sendMessage(ENDPOINT_HIBERNATE, dto, traceId);

        // :: This should result in a DLQ, since the ENDPOINT_HIBERNATE throws.
        MatsMessageRepresentation dlqMessage = _matsTestBrokerInterface.getDlqMessage(ENDPOINT_HIBERNATE);
        // There should be a DLQ
        Assert.assertNotNull(dlqMessage);
        // The DTO and TraceId of the DLQ'ed message should be the one we sent.
        SpringTestDataTO dtoInDlq = dlqMessage.getIncomingMessage(SpringTestDataTO.class);
        Assert.assertEquals(dto, dtoInDlq);
        Assert.assertEquals(traceId, dlqMessage.getTraceId());

        // There should be zero rows in the database, since the RuntimeException should have rolled back processing
        // of ENDPOINT, and thus TERMINATOR should not have gotten a message either (and thus not inserted row).
        List<String> dataFromDatabase = getDataFromDataTable();
        Assert.assertEquals(0, dataFromDatabase.size());
    }
}
