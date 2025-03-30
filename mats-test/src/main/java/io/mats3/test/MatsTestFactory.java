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

package io.mats3.test;

import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.broker.MatsTestBroker;

/**
 * Convenience solution for quickly creating a {@link AutoCloseable} MatsFactory configured for testing purposes (max
 * delivery:2, concurrency:2, names), backed by the in-vm broker from MatsTestBroker - which is also closed when this
 * MatsFactory is closed (but note the method {@link #createWithBroker(MatsTestBroker, MatsSerializer, DataSource)}).
 *
 * @author Endre Stølsvik 2023-09-02 23:51 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface MatsTestFactory extends AutoCloseable, MatsFactory {

    /**
     * The total number of attempted deliveries when in test-mode: 2. It does not make sense to redeliver a bunch of
     * times, with exponential fallback and whatnot, in test-scenarios. If you in a test want to check that a specific
     * message DLQs, then it would suffice with 1 attempt. But to get a tad more realistic, we'll run with one extra
     * attempt (which also suites the Mats3 own tests better, to actually ensure that the same message is redelivered).
     */
    int TEST_MAX_DELIVERY_ATTEMPTS = 2;

    /**
     * For most test scenarios, it really makes little meaning to have a concurrency of more than 1 - unless explicitly
     * testing Mats' handling of concurrency. However, due to some testing scenarios might come to rely on such
     * sequential processing, we set it to 2, to try to weed out such dependencies - hopefully tests will (at least
     * occasionally) fail by two consumers each getting a message and thus processing them concurrently.
     */
    int TEST_CONCURRENCY = 2;

    /**
     * No-args convenience to get a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, backed by an in-vm broker
     * from gotten from default {@link MatsTestBroker#create()} and using {@link MatsSerializerJson}. When the returned
     * MatsFactory's close() method is invoked, the MatsTestBroker is also closed. (Note that the stop(graceful) method
     * does <b>not</b> close the MatsTestBroker.)
     *
     * @return a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, backed by an in-vm broker from MatsTestBroker.
     */
    static MatsTestFactory create() {
        return create(MatsSerializerJson.create());
    }

    /**
     * Creates an {@link AutoCloseable}, JMS-tx only MatsFactory for testing purposes, backed by the in-vm broker from a
     * MatsTestBroker and using {@link MatsSerializerJson}. When the returned MatsFactory's close() method is invoked,
     * the MatsTestBroker is also closed. (Note that the stop(graceful) method does <b>not</b> close the
     * MatsTestBroker.)
     *
     * @param matsSerializer
     *            the MatsSerializer to use
     *
     * @return a simple, {@link AutoCloseable}, JMS-tx only MatsFactory, using the provided MatsSerializer, backed by an
     *         in-vm broker from the supplied MatsTestBroker.
     */
    static MatsTestFactory create(MatsSerializer matsSerializer) {
        return create(matsSerializer, null);
    }

    /**
     * Creates a wrapped {@link AutoCloseable}, MS-only or JMS-plus-JDBC (if dataSource arg is non-null) MatsFactory for
     * testing purposes, backed by the in-vm broker from a {@link MatsTestBroker} and using {@link MatsSerializerJson}.
     * When the returned MatsFactory's close() method is invoked, the MatsTestBroker is also closed. (Note that the
     * stop(graceful) method does <b>not</b> close the MatsTestBroker.)
     *
     * @param matsSerializer
     *            the MatsSerializer to use
     * @param dataSource
     *            the DataSource to use, or {@code null} if no DataSource should be used.
     * @return a simple, {@link AutoCloseable}, JMS-only or JMS-plus-JDBC transacted MatsFactory, using the provided
     *         MatsSerializer, backed by an in-vm broker from the supplied MatsTestBroker.
     */
    static MatsTestFactory create(MatsSerializer matsSerializer, DataSource dataSource) {
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory matsFactory = createWithBroker(matsTestBroker, matsSerializer, dataSource);

        return new MatsTestFactoryImpl(matsFactory, matsTestBroker);
    }

    /**
     * Creates a plain MatsFactory using the specified {@link MatsTestBroker}, configured for testing as the other
     * methods, but without the AutoCloseable wrapping - which then also do not take down the MatsTestBroker when
     * closed.
     */
    static MatsFactory createWithBroker(MatsTestBroker matsTestBroker) {
        return createWithBroker(matsTestBroker, MatsSerializerJson.create(), null);
    }

    /**
     * Creates a plain MatsFactory using the specified {@link MatsTestBroker}, configured for testing as the other
     * methods, but without the AutoCloseable wrapping - which then also do not take down the MatsTestBroker when
     * closed. Argument 'dataSource' can be {@code null}, and is used to determine if this MatsFactory should be
     * JMS-only or JMS+JDBC tx managed.
     */
    static MatsFactory createWithBroker(MatsTestBroker matsTestBroker, MatsSerializer matsSerializer,
            DataSource dataSource) {
        if (matsSerializer == null) {
            throw new NullPointerException("matsSerializer");
        }
        JmsMatsJmsSessionHandler sessionHandler = JmsMatsJmsSessionHandler_Pooling
                .create(matsTestBroker.getConnectionFactory());

        JmsMatsFactory matsFactory;
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
        matsFactory.getFactoryConfig().setName(MatsTestFactory.class.getSimpleName() + "_MF#"
                + MatsTestFactoryImpl.__instanceCounter.getAndIncrement());

        // Reduce number of redeliveries
        matsFactory.setMatsManagedDlqDivert(TEST_MAX_DELIVERY_ATTEMPTS);

        // Reduce concurrency
        matsFactory.getFactoryConfig().setConcurrency(TEST_CONCURRENCY);
        return matsFactory;
    }

    @Override
    void close();

    class MatsTestFactoryImpl extends MatsFactoryWrapper implements MatsTestFactory {
        private static final AtomicInteger __instanceCounter = new AtomicInteger(0);
        private static final Logger log = LoggerFactory.getLogger(MatsTestFactory.class);

        private final MatsTestBroker _matsTestBroker;

        public MatsTestFactoryImpl(MatsFactory matsFactory, MatsTestBroker matsTestBroker) {
            setWrappee(matsFactory);
            _matsTestBroker = matsTestBroker;
            log.info("MatsTestFactory created, with MatsFactory [" + matsFactory + "], and MatsTestBroker [" +
                    matsTestBroker + "].");
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
            log.info("MatsTestFactory closing: Stopping wrapped MatsFactory [" + unwrap() + "].");
            super.close();
            log.info("MatsTestFactory stopped, now closing MatsTestBroker [" + _matsTestBroker + "].");
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
