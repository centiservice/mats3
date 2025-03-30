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

package io.mats3.util.wrappers;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import org.h2.jdbc.JdbcConnection;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.TestH2DataSource;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapper.DeferredConnectionProxy;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapperWithLoggingCallback.MethodInvoked;

/**
 * Testing the Wrapper.
 *
 * @author Endre Stølsvik 2021-01-28 23:15 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_DeferredConnectionProxyDataSourceWrapper {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @Test
    public void shouldNotTriggerActualConnectionRetrieval() throws SQLException {
        TestH2DataSource dataSource = TestH2DataSource.createInMemoryRandom();
        DeferredConnectionProxyDataSourceWrapper wrapper = DeferredConnectionProxyDataSourceWrapper.wrap(dataSource);
        DeferredConnectionProxy connection = wrapper.getConnection();
        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                connection.isActualConnectionRetrieved());

        String toString = connection.toString();
        log.info("The Connection's toString right after being gotten: " + toString);
        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                connection.isActualConnectionRetrieved());
        Assert.assertTrue("Connection.toString should say that it has not been retrieved yet.",
                toString.contains("WITHOUT actual Connection"));

        Assert.assertEquals("Connection should equal itself", connection, connection);
        Assert.assertEquals("HashCode should be identity", System.identityHashCode(connection), connection.hashCode());

        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                connection.isActualConnectionRetrieved());

        dataSource.close();
    }

    @Test
    public void gettingTransactionIsolationShouldRetrieveConnection() throws SQLException {
        TestH2DataSource h2DataSource = TestH2DataSource.createInMemoryRandom();
        DeferredConnectionProxyDataSourceWrapperWithLoggingCallback wrapper = new DeferredConnectionProxyDataSourceWrapperWithLoggingCallback(
                h2DataSource);

        // Get a Connection from the Wrapper.
        DeferredConnectionProxy connectionProxy = wrapper.getConnection();
        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                connectionProxy.isActualConnectionRetrieved());

        // Get Connection directly from DataSource
        Connection connectionDirect = h2DataSource.getConnection();
        int transactionIsolationDirect = connectionDirect.getTransactionIsolation();

        // .. obviously
        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                connectionProxy.isActualConnectionRetrieved());
        Assert.assertNotEquals("A Connection directly from DataSource should most definitely not be equals"
                + " to a DeferredConnectionProxy from the wrapped DataSource", connectionDirect, connectionProxy);

        // Getting TransactionIsolation should trigger retrieve, as we haven't yet found default trans props.
        int transactionIsolationProxy = connectionProxy.getTransactionIsolation();
        Assert.assertTrue("The actualConnectionWasRetrieved should now have been run.", wrapper
                .wasActualConnectionRetrieved());
        Assert.assertTrue("Actual Connection SHOULD have been retrieved now.",
                connectionProxy.isActualConnectionRetrieved());

        // Just throw in the check for toString after retrieved.
        String toString = connectionProxy.toString();
        Assert.assertTrue("Connection.toString should say that it HAS been retrieved now.",
                toString.contains("WITH actual Connection"));

        Assert.assertNotEquals("The underlying Connection in the Proxy should be a different Connection than"
                + " the one we got directly.", connectionDirect, connectionProxy.unwrap());

        Assert.assertEquals("TransactionIsolation from a Connection directly from DataSource should"
                + " be same as from Wrapper DataSource", transactionIsolationDirect, transactionIsolationProxy);

        // Get ANOTHER Connection from Wrapper
        wrapper.clearWasActualConnectionRetrieved();
        DeferredConnectionProxy anotherConnectionProxy = wrapper.getConnection();
        // This time, getting TransactionIsolation should NOT trigger retrieve, as we've gotten default trans props.
        int transactionIsolationAnotherProxy = anotherConnectionProxy.getTransactionIsolation();
        Assert.assertFalse("The actualConnectionWasRetrieved should NOT have run again.",
                wrapper.wasActualConnectionRetrieved());
        Assert.assertFalse("Actual Connection should not have been retrieved yet.",
                anotherConnectionProxy.isActualConnectionRetrieved());

        Assert.assertEquals("TransactionIsolation from a Connection directly from DataSource should"
                + " be same as from Wrapper DataSource", transactionIsolationDirect, transactionIsolationAnotherProxy);

        h2DataSource.close();
    }

    @Test
    public void testingAnswerByProxyVsAnswerByActualConnection() throws SQLException {
        TestH2DataSource h2DataSource = TestH2DataSource.createInMemoryRandom();
        DeferredConnectionProxyDataSourceWrapperWithLoggingCallback wrapper = new DeferredConnectionProxyDataSourceWrapperWithLoggingCallback(
                h2DataSource);
        DeferredConnectionProxy connectionProxy = wrapper.getConnection();

        // Invoke isWrapperFor(its own class -> DeferredConnectionProxy)
        boolean wrapperForItsOwnClass = connectionProxy.isWrapperFor(DeferredConnectionProxy.class);
        Assert.assertFalse("Should not have retrieved actual Connection yet",
                wrapper.wasActualConnectionRetrieved());
        // :: Assert how that method was invoked
        Assert.assertTrue("DeferredConnectionProxy should be wrapper for its own class.",
                wrapperForItsOwnClass);
        MethodInvoked methodInvoked = wrapper.getMethodsInvoked().get(0);
        Assert.assertEquals("The method invoked should be 'isWrapperFor'",
                "isWrapperFor", methodInvoked.getMethod().getName());
        Assert.assertSame("The instance should be the connectionProxy",
                connectionProxy, methodInvoked.getConnectionProxy());
        Assert.assertEquals("The argument should be 'DeferredConnectionProxy.class'",
                DeferredConnectionProxy.class, methodInvoked.getArgs()[0]);
        Assert.assertNull("The actualConnection should be null",
                methodInvoked.getActualConnection());
        Assert.assertTrue("The result should be true",
                (Boolean) methodInvoked.getResult());
        Assert.assertTrue("It should be the proxy that answered",
                methodInvoked.isAnsweredByProxy());

        wrapper.clearWasActualConnectionRetrieved();
        wrapper.clearMethodsInvoked();

        // Invoke isWrapperFor(<H2DataBase>.JdbcConnection)
        boolean wrapperForH2 = connectionProxy.isWrapperFor(JdbcConnection.class);
        Assert.assertTrue("Should now have retrieved actual Connection",
                wrapper.wasActualConnectionRetrieved());
        Assert.assertTrue("DeferredConnectionProxy should be wrapper for JdbcConnection.",
                wrapperForH2);
        // Assert how that method was invoked
        MethodInvoked methodInvoked2 = wrapper.getMethodsInvoked().get(0);
        Assert.assertEquals("The method invoked should be 'isWrapperFor'",
                "isWrapperFor", methodInvoked2.getMethod().getName());
        Assert.assertSame("The instance should be the connectionProxy",
                connectionProxy, methodInvoked2.getConnectionProxy());
        Assert.assertEquals("The argument should be 'JdbcConnection.class'",
                JdbcConnection.class, methodInvoked2.getArgs()[0]);
        Assert.assertNotNull("The actualConnection should NOT be null",
                methodInvoked2.getActualConnection());
        Assert.assertSame("The wrapped Connection should be the one we got in the callback method",
                connectionProxy.unwrap(), methodInvoked2.getActualConnection());
        Assert.assertSame("The wrapped Connection should be the one we got in the notification",
                connectionProxy.unwrap(), wrapper.getActualConnectionRetrieved());
        Assert.assertTrue("The result should be true",
                (Boolean) methodInvoked2.getResult());
        Assert.assertFalse("It should be the ACTUAL Connection that answered",
                methodInvoked2.isAnsweredByProxy());

        wrapper.clearWasActualConnectionRetrieved();
        wrapper.clearMethodsInvoked();

        // Invoke isWrapperFor(MatsFactory)
        boolean wrapperForMatsFactory = connectionProxy.isWrapperFor(MatsFactory.class);
        Assert.assertFalse("Should NOT need to retrieved actual Connection again",
                wrapper.wasActualConnectionRetrieved());
        Assert.assertFalse("DeferredConnectionProxy should NOT be wrapper for MatsFactory.",
                wrapperForMatsFactory);
        // Assert how that method was invoked
        MethodInvoked methodInvoked3 = wrapper.getMethodsInvoked().get(0);
        Assert.assertEquals("The method invoked should be 'isWrapperFor'",
                "isWrapperFor", methodInvoked3.getMethod().getName());
        Assert.assertSame("The instance should be the connectionProxy",
                connectionProxy, methodInvoked3.getConnectionProxy());
        Assert.assertEquals("The argument should be 'MatsFactory.class'",
                MatsFactory.class, methodInvoked3.getArgs()[0]);
        Assert.assertNotNull("The actualConnection should NOT be null",
                methodInvoked3.getActualConnection());
        Assert.assertSame("The wrapped Connection should be the one we got in the callback method",
                connectionProxy.unwrap(), methodInvoked2.getActualConnection());
        Assert.assertFalse("The result should be false",
                (Boolean) methodInvoked3.getResult());
        Assert.assertFalse("It should be the ACTUAL Connection that answered",
                methodInvoked3.isAnsweredByProxy());

        h2DataSource.close();
    }

    @Test
    public void closeAndMethodsAnsweredByProxy() throws SQLException {
        TestH2DataSource h2DataSource = TestH2DataSource.createInMemoryRandom();
        DeferredConnectionProxyDataSourceWrapperWithLoggingCallback wrapper = new DeferredConnectionProxyDataSourceWrapperWithLoggingCallback(
                h2DataSource);
        DeferredConnectionProxy connectionProxy = wrapper.getConnection();

        // :: None of these should fetch the actual Connection:
        // Relies on first set, then get, since we haven't gotten default values yet on this DataSourceWrapper.
        connectionProxy.setAutoCommit(false);
        Assert.assertFalse(connectionProxy.getAutoCommit());
        connectionProxy.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        Assert.assertEquals(Connection.TRANSACTION_SERIALIZABLE, connectionProxy.getTransactionIsolation());
        connectionProxy.setReadOnly(true);
        Assert.assertTrue(connectionProxy.isReadOnly());
        connectionProxy.commit();
        connectionProxy.rollback();
        connectionProxy.getWarnings();
        connectionProxy.clearWarnings();

        Assert.assertFalse("Should NOT have retrieved the actual Connection",
                wrapper.wasActualConnectionRetrieved());

        Assert.assertFalse("Connection is not closed yet", connectionProxy.isClosed());

        // Double close is legal
        connectionProxy.close();
        Assert.assertTrue("Connection is now closed", connectionProxy.isClosed());
        connectionProxy.close();
        Assert.assertTrue("Connection is now closed", connectionProxy.isClosed());

        Assert.assertFalse("Should NOT have retrieved the actual Connection",
                wrapper.wasActualConnectionRetrieved());

        // :: NOT legal to call any "active" methods after close.
        Assert.assertThrows("Not legal to .setAutoCommit() after close",
                SQLException.class, () -> connectionProxy.setAutoCommit(false));
        Assert.assertThrows("Not legal to .getAutoCommit() after close",
                SQLException.class, connectionProxy::getAutoCommit);
        Assert.assertThrows("Not legal to .setTransactionIsolation() after close",
                SQLException.class, () -> connectionProxy.setTransactionIsolation(Connection.TRANSACTION_NONE));
        Assert.assertThrows("Not legal to .getTransactionIsolation() after close",
                SQLException.class, connectionProxy::getTransactionIsolation);
        Assert.assertThrows("Not legal to .setAutoCommit() after close",
                SQLException.class, () -> connectionProxy.setReadOnly(true));
        Assert.assertThrows("Not legal to .isReadOnly() after close",
                SQLException.class, connectionProxy::isReadOnly);
        Assert.assertThrows("Not legal to .commit() after close",
                SQLException.class, connectionProxy::commit);
        Assert.assertThrows("Not legal to .rollback() after close",
                SQLException.class, connectionProxy::rollback);
        Assert.assertThrows("Not legal to .getWarnings() after close",
                SQLException.class, connectionProxy::getWarnings);
        Assert.assertThrows("Not legal to .clearWarnings() after close",
                SQLException.class, connectionProxy::clearWarnings);

        Assert.assertFalse("Should NOT have retrieved the actual Connection",
                wrapper.wasActualConnectionRetrieved());

        h2DataSource.close();
    }

    @Test
    public void insertUsingPreparedStatement() throws SQLException {
        TestH2DataSource h2DataSource = TestH2DataSource.createInMemoryRandom();
        DeferredConnectionProxyDataSourceWrapperWithLoggingCallback wrapper = new DeferredConnectionProxyDataSourceWrapperWithLoggingCallback(
                h2DataSource);
        DeferredConnectionProxy connectionProxy = wrapper.getConnection();

        TestH2DataSource.createDataTable(connectionProxy);

        Connection actualConnection = connectionProxy.unwrap();
        // :: Check close status on the actual Connection from proxy
        Assert.assertFalse(actualConnection.isClosed());

        Assert.assertTrue("Should definitely have retrieved the Connection",
                wrapper.wasActualConnectionRetrieved());

        TestH2DataSource.insertDataIntoDataTable(connectionProxy, "EndreTester");
        List<String> dataFromDataTable = TestH2DataSource.getDataFromDataTable(connectionProxy);

        Assert.assertEquals(Collections.singletonList("EndreTester"), dataFromDataTable);

        // :: Close the Connection (via Proxy)
        // Clear method logging, so that we can check
        wrapper.clearMethodsInvoked();
        connectionProxy.close();
        Assert.assertTrue(connectionProxy.isClosed());

        MethodInvoked closeInvoked = wrapper.getMethodsInvoked().get(0);
        Assert.assertEquals("close", closeInvoked.getMethod().getName());
        Assert.assertFalse("The close call should NOT have been answered by proxy",
                closeInvoked.isAnsweredByProxy());

        // :: Check close status on the actual Connection from proxy
        Assert.assertTrue(actualConnection.isClosed());

        h2DataSource.close();
    }
}
