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

package io.mats3.spring.test.infrastructure;

import javax.sql.DataSource;

import org.junit.Assert;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsTransactionManager;
import io.mats3.serial.MatsSerializer;
import io.mats3.spring.jms.tx.JmsMatsTransactionManager_JmsAndSpringManagedSqlTx;

public class Common {
    static void assertSameDataSourceInMatsFactory(MatsFactory matsFactory, DataSource dataSource) {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = matsFactory.unwrapFully();

        if (!(unWrappedMatsFactory instanceof JmsMatsFactory)) {
            throw new AssertionError("The unwrapped MatsFactory was no JmsMatsFactory [" + unWrappedMatsFactory + "].");
        }

        // Cast it to JmsMatsFactory
        JmsMatsFactory jmsMatsFactory = (JmsMatsFactory) unWrappedMatsFactory;

        // Fetch the transaction manager
        JmsMatsTransactionManager jmsMatsTransactionManager;
        jmsMatsTransactionManager = jmsMatsFactory.getJmsMatsTransactionManager();
        if (!(jmsMatsTransactionManager instanceof JmsMatsTransactionManager_JmsAndSpringManagedSqlTx)) {
            throw new AssertionError("The JmsMatsTransactionManager in the MatsFactory was not of"
                    + " expected type. [" + jmsMatsTransactionManager + "], of class [" + jmsMatsTransactionManager
                            .getClass().getName() + "].");
        }

        // Cast it
        JmsMatsTransactionManager_JmsAndSpringManagedSqlTx matsSpringTx = (JmsMatsTransactionManager_JmsAndSpringManagedSqlTx) jmsMatsTransactionManager;

        // Get the DataSource it is using
        DataSource dataSourceFromTxMgr = matsSpringTx.getDataSource();

        // This should be wrapped by JmsMatsTransactionManager_JmsAndSpringManagedSqlTx.wrapLazyConnectionDatasource()
        // .. therefore, it should NOT be same instance as the on in the Spring context
        Assert.assertNotSame(dataSource, dataSourceFromTxMgr);

        // However, if we get the /unwrapped/ DataSource, it SHALL be same instance as the one the Spring context
        DataSource unwrappedDataSource = matsSpringTx.getDataSourceUnwrapped();
        Assert.assertSame(dataSource, unwrappedDataSource);
    }

    static void assertSameMatsSerializerInMatsFactory(MatsFactory matsFactory,
            MatsSerializer matsSerializer) {
        // NOTE: The injected MatsFactory will be wrapped by TestSpringMatsFactoryProvider.

        // Unwrap fully
        MatsFactory unWrappedMatsFactory = matsFactory.unwrapFully();

        if (!(unWrappedMatsFactory instanceof JmsMatsFactory)) {
            throw new AssertionError("The unwrapped MatsFactory was no JmsMatsFactory [" + unWrappedMatsFactory + "].");
        }

        // Cast it to JmsMatsFactory
        JmsMatsFactory jmsMatsFactory = (JmsMatsFactory) unWrappedMatsFactory;

        // Fetch the MatsSerializer
        MatsSerializer matsSerializerFromMatsFactory = jmsMatsFactory.getMatsSerializer();

        // Assert that the MatsSerializer in the MatsFactory is the same that we made in the Spring Context.
        Assert.assertSame(matsSerializer, matsSerializerFromMatsFactory);
    }

}
