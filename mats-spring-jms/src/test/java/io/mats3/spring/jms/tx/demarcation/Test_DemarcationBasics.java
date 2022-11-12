package io.mats3.spring.jms.tx.demarcation;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.InfrastructureProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.ContextLocal;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;
import io.mats3.spring.jms.tx.SpringTestDataTO;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.TestH2DataSource;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapper;
import io.mats3.util.wrappers.DeferredConnectionProxyDataSourceWrapper.DeferredConnectionProxy;

/**
 * Mats Demarcation Basics: Checks properties of initiations within a stage, and within an initiation, both "REQUIRED"
 * and "REQUIRES_NEW" type invocations. Checks whether the Connection "employed" flag works.
 *
 * @author Endre Stølsvik 2021-01-18 21:35 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class Test_DemarcationBasics {
    private static final Logger log = MatsTestHelp.getClassLogger();

    public static final String SIMPLE_SINGLE_STAGE_ENDPOINT = "test.DummyLeafService";

    public static final String TERMINATOR_DEMARCATION_BASICS = "test.Terminator.DemarcationBasics";

    @Configuration
    @EnableTransactionManagement
    public static class TestConfiguration {
        /**
         * Note: The DataSource that will be put in the Spring context is actually an ordinary DataSource (no magic,
         * except the Mats Test tooling). Specifically, it is NOT proxied with Mats' "magic lazy proxy".
         */
        @Bean
        TestH2DataSource testH2DataSource() {
            TestH2DataSource standard = TestH2DataSource.createStandard();
            standard.createDataTable();
            return standard;
        }

        /**
         * Note: The DataSource given to the {@link DataSourceTransactionManager} is proxied with Mats' "magic lazy
         * proxy".
         */
        @Bean
        PlatformTransactionManager createDataSourceTransactionaManager(DataSource dataSource) {
            // NOTICE! THIS IS IMPORTANT! We wrap the DataSource with the "magic lazy proxy" of Mats.
            DataSource magicLazyProxied = JmsMatsTransactionManager_JmsAndSpringManagedSqlTx
                    .wrapLazyConnectionDatasource(dataSource);
            // .. and use this when we create the DSTM which Mats and Spring tools will get.
            return new DataSourceTransactionManager(magicLazyProxied);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SimpleJdbcInsert simpleJdbcInsert(DataSource dataSource) {
            return new SimpleJdbcInsert(dataSource).withTableName("datatable");
        }

        @Inject
        private DataSource _dataSource;

        @Inject
        private MatsFactory _matsFactory;

        @Inject
        private MatsTestLatch _latch;

        @Inject
        private MatsFuturizer _matsFuturizer;

        @MatsMapping(SIMPLE_SINGLE_STAGE_ENDPOINT)
        String simpleService(String incoming) {
            return incoming + ":DummyLeafService";
        }

        @MatsMapping(TERMINATOR_DEMARCATION_BASICS)
        void matsTerminator(ProcessContext<Void> context, @Dto SpringTestDataTO msg) {

            // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
            doFuturizerCall();

            // :: ProcessContext and MatsInitiate

            // Assert that we DO have ProcessContext in ContextLocal
            // Point is: We're within a ProcessContext - not an Initiation
            assert_ContextLocal_has_ProcessContext_butNot_MatsInitiate();

            // :: SQL Connections
            Connection connectionFromDataSourceUtils_outside = checkSqlConnection(context, _dataSource);

            Thread thread_outside = Thread.currentThread();

            // ::: Initiates

            // :: "SUB HOISTING INITIATE": Using DefaultInitiator, do an initiate
            // NOTE! NOTHING should have changed when doing this, as it is LITERALLY just the same as doing
            // processContext.initiate(init ->...)
            _matsFactory.getDefaultInitiator().initiateUnchecked(init -> {
                // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
                doFuturizerCall();

                // :: Should be same Thread
                Assert.assertSame(thread_outside, Thread.currentThread());

                // :: Should have ProcessContext, but not MatsInitiate
                // Point is: We're within a Stage, and "riding" on the ProcessContext's MatsInitiate.
                assert_ContextLocal_has_ProcessContext_butNot_MatsInitiate();

                // :: SQL Connections
                Connection connectionFromDataSourceUtils_defaultInit = checkSqlConnection(context, _dataSource);

                // BIG POINT:
                // Assert that this Connection IS THE SAME as the one on the outside
                Assert.assertSame(connectionFromDataSourceUtils_outside, connectionFromDataSourceUtils_defaultInit);

                // :: "SUB-SUB HOISTING INITIATE": Sub-sub initiation, using default initiator
                _matsFactory.getDefaultInitiator().initiateUnchecked(init2 -> {
                    // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
                    doFuturizerCall();

                    // :: Should be same Thread
                    Assert.assertSame(thread_outside, Thread.currentThread());

                    // :: Should have ProcessContext, but not MatsInitiate
                    // Point is: We're within a Stage, and "riding" on the ProcessContext's MatsInitiate.
                    assert_ContextLocal_has_ProcessContext_butNot_MatsInitiate();

                    // :: SQL Connections
                    Connection connectionFromDataSourceUtils_subDefaultInit = checkSqlConnection(context, _dataSource);

                    // BIG POINT:
                    // Assert that this Connection IS THE SAME as the one on the outside
                    Assert.assertSame(connectionFromDataSourceUtils_outside,
                            connectionFromDataSourceUtils_subDefaultInit);
                });
            });

            // :: "SUB INITIATE": Using a non-default initiator, do an initiate
            // Also Hoisting and Sub-Sub-Initiate
            // NOTE! Pretty much everything should have changed when doing this, as it is very much the same as
            // standing completely on the outside, doing an initiation (the current implementation is actually forking
            // the entire initiation off to a new Thread!)
            String nonDefaultInitiatorName = "sub" + Math.random();
            _matsFactory.getOrCreateInitiator(nonDefaultInitiatorName).initiateUnchecked(init -> {
                // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
                doFuturizerCall();

                Thread thread_nonDefaultInitiator = Thread.currentThread();

                // :: Should NOT be same Thread
                Assert.assertNotSame(thread_outside, thread_nonDefaultInitiator);

                // :: Should have MatsInitiate but not ProcessContext
                // Point is: We're within a MatsInitiate now - forked out of the Stage's ProcessContext.
                assert_ContextLocal_has_MatsInitiate_butNot_ProcessContext();

                // :: SQL Connections
                Connection connectionFromDataSourceUtils_nonDefault = checkSqlConnection(context, _dataSource);

                // BIG POINT:
                // Assert that this Connection is *NOT* THE SAME as the one on the outside
                Assert.assertNotSame(connectionFromDataSourceUtils_outside, connectionFromDataSourceUtils_nonDefault);

                // :: "SUB-SUB HOISTING": Sub-sub initiation, using default initiator
                _matsFactory.getDefaultInitiator().initiateUnchecked(init2 -> {
                    // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
                    doFuturizerCall();

                    // :: Should be same Thread as "parent"
                    Assert.assertSame(thread_nonDefaultInitiator, Thread.currentThread());

                    // :: Should have MatsInitiate but not ProcessContext
                    // Point is: We're within a MatsInitiate now - forked out of the Stage's ProcessContext.
                    assert_ContextLocal_has_MatsInitiate_butNot_ProcessContext();

                    // :: SQL Connections
                    Connection connectionFromDataSourceUtils_subNonDefault = checkSqlConnection(context, _dataSource);

                    // BIG POINT:
                    // Assert that this Connection IS THE SAME as the "parent"
                    Assert.assertSame(connectionFromDataSourceUtils_nonDefault,
                            connectionFromDataSourceUtils_subNonDefault);
                });

                // :: "SUB-SUB INITIATE", using the same-named /non-default/ MatsInitiator
                _matsFactory.getOrCreateInitiator(nonDefaultInitiatorName).initiateUnchecked(init2 -> {
                    // :: Perform a futurizer call and wait for result (Don't ever do this "in production"!)
                    doFuturizerCall();

                    // :: Should NOT be same Thread
                    Assert.assertNotSame(thread_outside, Thread.currentThread());
                    // .. and not the same thread as the "parent" either
                    Assert.assertNotSame(thread_nonDefaultInitiator, Thread.currentThread());

                    // :: Should have MatsInitiate but not ProcessContext
                    // Point is: We're within a MatsInitiate now - forked out (twice) of the Stage's ProcessContext.
                    assert_ContextLocal_has_MatsInitiate_butNot_ProcessContext();

                    // :: SQL Connections
                    Connection connectionFromDataSourceUtils_subNonDefault = checkSqlConnection(context, _dataSource);

                    // BIG POINT:
                    // Assert that this Connection is *NOT* THE SAME as the one on the outside
                    Assert.assertNotSame(connectionFromDataSourceUtils_outside,
                            connectionFromDataSourceUtils_subNonDefault);
                    // .. and not the same as the one in "parent" either
                    Assert.assertNotSame(connectionFromDataSourceUtils_nonDefault,
                            connectionFromDataSourceUtils_subNonDefault);
                });
            });

            _latch.resolve(null, msg);
        }

        /**
         * This works since the MatsFuturizer uses its own non-default MatsInitiator, and thus the initiate call that
         * the futurizer performs always ends up on a "REQUIRES_NEW"-style Mats demarcation (i.e. running in an
         * independent Thread).
         */
        private void doFuturizerCall() {
            _matsFuturizer.futurize(
                    MatsTestHelp.traceId(),
                    MatsTestHelp.from("TheFuture"),
                    SIMPLE_SINGLE_STAGE_ENDPOINT,
                    5, TimeUnit.SECONDS,
                    Void.class,
                    "TheRequest",
                    MatsInitiate::interactive)
                    /* wait for it */
                    .join();
        }

        private static void assert_ContextLocal_has_ProcessContext_butNot_MatsInitiate() {
            // Assert that we DO have ProcessContext in ContextLocal
            @SuppressWarnings("rawtypes")
            Optional<ProcessContext> processContextOptional_defaultInit = ContextLocal.getAttribute(
                    ProcessContext.class);
            Assert.assertTrue("ProcessContext SHALL be be present in ContextLocal",
                    processContextOptional_defaultInit.isPresent());

            // Assert that we do NOT have MatsInitiate in ContextLocal (deficiency per now, 2020-01-19)
            Optional<MatsInitiate> matsInitiateOptional_defaultInit = ContextLocal.getAttribute(MatsInitiate.class);
            Assert.assertFalse("MatsInitiate shall NOT be present in ContextLocal", matsInitiateOptional_defaultInit
                    .isPresent());
        }

        private static void assert_ContextLocal_has_MatsInitiate_butNot_ProcessContext() {
            // Assert that we do NOT have ProcessContext in ContextLocal
            @SuppressWarnings("rawtypes")
            Optional<ProcessContext> processContextOptional_separateInit = ContextLocal.getAttribute(
                    ProcessContext.class);
            Assert.assertFalse("ProcessContext shall NOT be be present in ContextLocal",
                    processContextOptional_separateInit.isPresent());

            // Assert that we DO have MatsInitiate in ContextLocal
            Optional<MatsInitiate> matsInitiateOptional_separateInit = ContextLocal.getAttribute(
                    MatsInitiate.class);
            Assert.assertTrue("MatsInitiate SHALL be present in ContextLocal", matsInitiateOptional_separateInit
                    .isPresent());
        }

        private static Connection checkSqlConnection(ProcessContext<Void> context, DataSource dataSource) {
            // We have not employed the SQL Connection yet
            Supplier<Boolean> connectionEmployed = getConnectionEmployedStateSupplierFromContextLocal();
            Assert.assertFalse(connectionEmployed.get());

            // Get the SQL Connection via ProcessContext
            Optional<Connection> optionalConnectionAttribute = context.getAttribute(Connection.class);
            if (!optionalConnectionAttribute.isPresent()) {
                throw new AssertionError("Missing context.getAttribute(Connection.class).");
            }
            Connection connectionFromContextAttribute = optionalConnectionAttribute.get();
            // .. it should be a proxy
            Assert.assertFalse("Should still be thin proxy w/o actual connection", isActualConnection(connectionFromContextAttribute));
            log.info(TERMINATOR_DEMARCATION_BASICS + ": context.getAttribute(Connection.class): "
                    + connectionFromContextAttribute);
            // .. but still not /employed/
            Assert.assertFalse(connectionEmployed.get());

            // Get the SQL Connection through DataSourceUtils
            Connection connectionFromDataSourceUtils = DataSourceUtils.getConnection(dataSource);
            // .. it should be a proxy
            Assert.assertFalse("Should still be thin proxy w/o actual connection", isActualConnection(connectionFromDataSourceUtils));
            // .. AND it should be the same Connection (instance/object) as from ProcessContext
            Assert.assertSame(connectionFromContextAttribute, connectionFromDataSourceUtils);
            // .. and still not /employed/
            Assert.assertFalse(connectionEmployed.get());
            return connectionFromDataSourceUtils;
        }

    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Supplier<Boolean> getConnectionEmployedStateSupplierFromContextLocal() {
        Optional<Supplier> attribute = ContextLocal.getAttribute(Supplier.class,
                JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER);
        if (!attribute.isPresent()) {
            throw new AssertionError("Missing in ContextLocal: CONTEXT_LOCAL_KEY_CONNECTION_EMPLOYED_STATE_SUPPLIER");
        }
        return attribute.get();
    }

    private static boolean isActualConnection(DataSource dataSource) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean isProxy = isActualConnection(connection);
        DataSourceUtils.releaseConnection(connection, dataSource);
        return isProxy;
    }

    private static boolean isActualConnection(Connection connection) {
        // :: First run a blood-hack using toString, which Spring's LazyConnectionDataSource and
        // DeferredFetchConnectionProxyDataSourceWrapper sets to known values:
        String connectionToString = connection.toString();
        boolean isActualConnectionFromToString = !(connectionToString.startsWith("Lazy Connection proxy")
                || connectionToString.contains("WITHOUT actual"));
        log.info("CONNECTION-CHECK from toString : Connection is " + (isActualConnectionFromToString ? "ACTUAL"
                : "still only thin PROXY") + "! conn:" + connectionToString);

        // ?: Does the Connection implement ConnectionWrapper (the DeferredFetchConnectionProxyDataSourceWrapper does)
        if (connection instanceof DeferredConnectionProxy) {
            // -> Yes, so cast it and check directly.
            boolean isActualConnectionFromInterface = ((DeferredConnectionProxy) connection).isActualConnectionRetrieved();
            log.info("CONNECTION-CHECK from interface: Connection is " + (isActualConnectionFromInterface ? "ACTUAL"
                    : "still only thin PROXY") + "! conn:" + connectionToString);

            Assert.assertEquals(isActualConnectionFromInterface, isActualConnectionFromToString);
        }

        return isActualConnectionFromToString;
    }

    @Inject
    private MatsTestLatch _latch;

    @Inject
    private TestService _testService;

    @Inject
    private TestH2DataSource _testH2DataSource;

    @Inject
    private MatsFactory _matsFactory;

    @Test
    public void testBasicPropertiesOfStageAndInitiationsAndHoistingAndSubInitiations() {
        _matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId(MatsTestHelp.traceId())
                .from(MatsTestHelp.from("testBasicPropertiesOfStageAndInitiationsAndHoistingAndSubInitiations"))
                .to(TERMINATOR_DEMARCATION_BASICS)
                .send(new SpringTestDataTO()));
        _latch.waitForResult();
    }

    // ===============================================================================================
    // ===== Testing basic Spring transactional infrastructure in conjunction with the proxying. =====
    // ===============================================================================================

    @Configuration
    @Service
    public static class TestService {
        @Inject
        private SimpleJdbcInsert _simpleJdbcInsert;

        @Inject
        private DataSource _dataSource;

        /**
         * This is not {@literal @Transactional}, and will thus NOT use the DSTM in Spring, and thus will get its
         * connection directly from the DataSource in the Spring context, which is not proxied with the "magic lazy
         * proxy".
         */
        public void insertRowNonTransactional() {
            log.info("ENDPOINT-METHOD: insertRowNonTransactional()");
            // This is no magic lazy proxy.
            Assert.assertTrue(isActualConnection(_dataSource));
            _simpleJdbcInsert.execute(Collections.singletonMap("data", "insertRowNonTransactional"));
        }

        /**
         * This is not {@literal @Transactional}, and will thus NOT use the DSTM in Spring, and thus will get its
         * connection directly from the DataSource in the Spring context, which is not proxied with the "magic lazy
         * proxy".
         */
        public void insertRowNonTransactionalThrow() {
            log.info("ENDPOINT-METHOD: insertRowNonTransactionalThrow()");
            // This is no magic lazy proxy.
            Assert.assertTrue(isActualConnection(_dataSource));
            _simpleJdbcInsert.execute(Collections.singletonMap("data", "insertRowNonTransactionalThrow"));
            throw new RuntimeException();
        }

        /**
         * Forces init of {@link DeferredConnectionProxyDataSourceWrapper}, which
         * needs an initial connection to retrieve the values of a "clean Connection" from the DB pool.
         */
        @Transactional
        public void forceInitializationOfDeferredFetchConnectionProxyDataSourceWrapper() throws SQLException {
            log.info("ENDPOINT-METHOD: Force init of DeferredFetchConnectionProxyDataSourceWrapper");
            // Fetches the Connection through DataSourceUtils, to ensure that we get it from the lazy-DS-wrapper
            Connection con = DataSourceUtils.getConnection(_dataSource);
            // Run a method that forces initialization, prepareStatement(..) is one of those.
            con.prepareStatement("SELECT 1").close();
            DataSourceUtils.releaseConnection(con, _dataSource);
        }

        /**
         * This method is {@literal @Transactional}, and will therefore be managed by the DSTM in Spring. Even though we
         * here employ the DataSource residing in Spring context - exactly as with {@link #insertRowNonTransactional()}
         * - it will still pick up the DataSource contained in the DSTM, which is proxied with Mats' "magic lazy proxy".
         * (This happens because the proxy implements the {@link InfrastructureProxy}, and the "equals" logic of
         * Spring's DataSource resolution thus realizes that what we <i>really</i> want, is a Connection from the
         * "equal" DataSource in the DSTM, which is the "magic lazy proxy").
         */
        @Transactional
        public void insertRowTransactional() {
            log.info("ENDPOINT-METHOD: insertRowTransactional()");
            // ::This actually ends up being a "magic lazy proxy", even though we supply the DataSource directly from
            // the Spring context, which is not proxied.
            // - At this point the lazy proxy has not gotten the connection yet.
            Assert.assertFalse(isActualConnection(_dataSource));
            _simpleJdbcInsert.execute(Collections.singletonMap("data", "insertRowTransactional"));
            // - While at this point the lazy proxy HAS gotten the connection!
            Assert.assertTrue(isActualConnection(_dataSource));
        }

        /**
         * Read JavaDoc at {@link #insertRowTransactional()}.
         *
         * <b>This should rollback, and thus not insert the row anyway</b>
         */
        @Transactional
        public void insertRowTransactionalThrow() {
            log.info("ENDPOINT-METHOD: insertRowTransactionalThrow()");
            // ::This actually ends up being a "magic lazy proxy", even though we supply the DataSource directly from
            // the Spring context, which is not proxied.
            // - At this point the lazy proxy has not gotten the connection yet.
            Assert.assertFalse(isActualConnection(_dataSource));
            _simpleJdbcInsert.execute(Collections.singletonMap("data", "insertRowTransactionalThrow"));
            // - While at this point the lazy proxy HAS gotten the connection!
            Assert.assertTrue(isActualConnection(_dataSource));
            throw new RuntimeException();
        }
    }

    @Test
    public void testBasicSpringTransactionalLogic() throws SQLException {
        // :: Non-transactional
        // Standard insert
        _testService.insertRowNonTransactional();
        try {
            // Since this isn't transactional, the insert will have happened before the throw.
            _testService.insertRowNonTransactionalThrow();
        }
        catch (RuntimeException e) {
            /* no-op */
        }

        // Force init of DeferredFetchConnectionProxyDataSourceWrapper
        _testService.forceInitializationOfDeferredFetchConnectionProxyDataSourceWrapper();

        // :: Transactional
        // Standard insert
        _testService.insertRowTransactional();
        try {
            // This should rollback, and thus the insert will "not have happened".
            _testService.insertRowTransactionalThrow();
        }
        catch (RuntimeException e) {
            /* no-op */
        }

        List<String> dataFromDataTable = _testH2DataSource.getDataFromDataTable();

        // NOTICE!! The "insertRowTransactionalThrow" should NOT be here, as it threw, and Spring will then rollback.
        Assert.assertEquals(
                Arrays.asList("insertRowNonTransactional", "insertRowNonTransactionalThrow", "insertRowTransactional"),
                dataFromDataTable);
    }
}
