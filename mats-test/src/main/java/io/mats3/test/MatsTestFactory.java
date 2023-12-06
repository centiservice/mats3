package io.mats3.test;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_PoolingSerial;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.broker.MatsTestBroker;

/**
 * Creates a {@link AutoCloseable} MatsFactory for testing purposes, backed by the in-vm broker from MatsTestBroker.
 *
 * @author Endre Stølsvik 2023-09-02 23:51 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsTestFactory extends AutoCloseable, MatsFactory {

    /**
     * No-args convenience to get a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, backed by an in-vm broker
     * from gotten from default {@link MatsTestBroker#create()} and using {@link MatsSerializerJson}. When the returned
     * MatsFactory's close() method is invoked, the MatsTestBroker is also closed. (Note that the stop(graceful) method
     * does <b>not</b> close the MatsTestBroker.)
     *
     * @return a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, backed by an in-vm broker from MatsTestBroker.
     */
    static MatsTestFactory create() {
        return create(MatsTestBroker.create(), null, MatsSerializerJson.create());
    }

    /**
     * @param matsTestBroker
     *            the MatsTestBroker from which to get the ConnectionFactory, and to close when this MatsFactory is
     *            closed.
     * @param matsSerializer
     *            the MatsSerializer to use
     *
     * @return a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, using the provided MatsSerializer, backed by an
     *         in-vm broker from the supplied MatsTestBroker.
     */
    static <Z> MatsTestFactory create(MatsTestBroker matsTestBroker, MatsSerializer<Z> matsSerializer) {
        return create(matsTestBroker, null, matsSerializer);
    }

    /**
     * @param matsTestBroker
     *            the MatsTestBroker from which to get the ConnectionFactory, and to close when this MatsFactory is
     *            closed.
     * @param dataSource
     *            the DataSource to use, or {@code null} if no DataSource should be used.
     * @param matsSerializer
     *            the MatsSerializer to use
     *
     * @return a simple, {@link AutoCloseable}, JMS-onoy or JMS-plus-JDBC transacted MatsFactory, using the provided
     *         MatsSerializer, backed by an in-vm broker from the supplied MatsTestBroker.
     * @param <Z>
     *            the type of the object that the MatsSerializer serializes to/from.
     */
    static <Z> MatsTestFactory create(MatsTestBroker matsTestBroker, DataSource dataSource,
            MatsSerializer<Z> matsSerializer) {

        JmsMatsJmsSessionHandler sessionHandler = JmsMatsJmsSessionHandler_PoolingSerial
                .create(matsTestBroker.getConnectionFactory());

        JmsMatsFactory<Z> matsFactory;
        if (dataSource == null) {
            // -> No DataSource
            // Create the JMS-only TransactionManager-backed JMS MatsFactory.
            matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(MatsTestFactory.class.getSimpleName()
                    + "_app_without_db", "*testing*", sessionHandler, matsSerializer);
        }
        else {
            // -> We have DataSource
            // Create the JMS and JDBC TransactionManager-backed JMS MatsFactory.
            matsFactory = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(MatsTestFactory.class.getSimpleName()
                    + "_app_with_db", "*testing*", sessionHandler, dataSource, matsSerializer);

        }

        // Set name
        matsFactory.getFactoryConfig().setName(MatsTestFactory.class.getSimpleName() + "_MF");

        /*
         * For most test scenarios, it really makes little meaning to have a concurrency of more than 1 - unless
         * explicitly testing Mats' handling of concurrency. However, due to some testing scenarios might come to rely
         * on such sequential processing, we set it to 2, to try to weed out such dependencies - hopefully tests will
         * (at least occasionally) fail by two consumers each getting a message and thus processing them concurrently.
         */
        matsFactory.getFactoryConfig().setConcurrency(2);

        return new MatsTestFactoryImpl(matsFactory, matsTestBroker);
    }

    @Override
    void close();

    class MatsTestFactoryImpl extends MatsFactoryWrapper implements MatsTestFactory {
        private static final Logger log = LoggerFactory.getLogger(MatsTestFactory.class);

        private final MatsTestBroker _matsTestBroker;

        public MatsTestFactoryImpl(MatsFactory matsFactory, MatsTestBroker matsTestBroker) {
            setWrappee(matsFactory);
            _matsTestBroker = matsTestBroker;
            log.info("MatsTestFactory created, with MatsFactory [" + matsFactory + "].");
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            log.info("MatsTestFactory stopping: Stopping wrapped MatsFactory [" + unwrap() + "].");
            return super.stop(gracefulShutdownMillis);
        }

        /**
         * Override close to also close MatsTestBroker.
         */
        @Override
        public void close() {
            log.info("MatsTestFactory closing: Closing wrapped MatsFactory [" + unwrap() + "].");
            super.close();
            log.info("MatsTestFactory closed, now closing MatsTestBroker [" + _matsTestBroker + "].");
            try {
                _matsTestBroker.close();
            }
            catch (Exception e) {
                throw new RuntimeException("Got problems closing MatsTestBroker.", e);
            }
            log.info("MatsTestFactory stopped - MatsTestBroker closed.");
        }
    }
}
