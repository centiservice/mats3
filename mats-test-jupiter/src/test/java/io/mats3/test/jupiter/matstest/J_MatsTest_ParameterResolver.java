package io.mats3.test.jupiter.matstest;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.test.jupiter.MatsTest;
import io.mats3.util.MatsFuturizer;

/**
 * Test to demonstrate how to use the {@link MatsTest} annotation to inject a MatsFactory or MatsFuturizer into a test.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest(db = true)
class J_MatsTest_ParameterResolver {

    @Nested
    class MethodParameterInjection {

        @Test
        void testExtensionMatsAvailable(Extension_Mats extensionMats) {
            Assertions.assertNotNull(extensionMats);
        }

        @Test
        void testMatsFactoryAvailable(MatsFactory matsFactory) {
            Assertions.assertNotNull(matsFactory);
        }

        @Test
        void testMatsFuturizerAvailable(MatsFuturizer matsFuturizer) {
            Assertions.assertNotNull(matsFuturizer);
        }

        @Test
        void testMatsInitiator(MatsInitiator matsInitiator) {
            Assertions.assertNotNull(matsInitiator);
        }

        @Test
        void testMatsTestLatch(MatsTestLatch matsTestLatch) {
            Assertions.assertNotNull(matsTestLatch);
        }

        @Test
        void testMatsTestBrokerInterface(MatsTestBrokerInterface matsTestBrokerInterface) {
            Assertions.assertNotNull(matsTestBrokerInterface);
        }

        @Test
        void testDataSource(javax.sql.DataSource dataSource) {
            Assertions.assertNotNull(dataSource);
        }

        // Multiple parameters

        @Test
        void testMultipleAvailable(MatsFactory matsFactory, MatsFuturizer matsFuturizer, DataSource dataSource) {
            Assertions.assertNotNull(matsFactory);
            Assertions.assertNotNull(matsFuturizer);
            Assertions.assertNotNull(dataSource);
        }
    }

    @Nested
    class ConstructorInjection {
        private final Extension_Mats _extensionMats;
        private final MatsFactory _matsFactory;
        private final MatsFuturizer _matsFuturizer;
        private final MatsInitiator _matsInitiator;
        private final MatsTestLatch _matsTestLatch;
        private final MatsTestBrokerInterface _matsTestBrokerInterface;
        private final DataSource _dataSource;

        public ConstructorInjection(
                Extension_Mats extensionMats,
                MatsFactory matsFactory,
                MatsFuturizer matsFuturizer,
                MatsInitiator matsInitiator,
                MatsTestLatch matsTestLatch,
                MatsTestBrokerInterface matsTestBrokerInterface,
                DataSource dataSource) {
            _extensionMats = extensionMats;
            _matsFactory = matsFactory;
            _matsFuturizer = matsFuturizer;
            _matsInitiator = matsInitiator;
            _matsTestLatch = matsTestLatch;
            _matsTestBrokerInterface = matsTestBrokerInterface;
            _dataSource = dataSource;
        }

        @Test
        void testFieldValuesSet() {
            Assertions.assertNotNull(_extensionMats);
            Assertions.assertNotNull(_matsFactory);
            Assertions.assertNotNull(_matsFuturizer);
            Assertions.assertNotNull(_matsInitiator);
            Assertions.assertNotNull(_matsTestLatch);
            Assertions.assertNotNull(_matsTestBrokerInterface);
            Assertions.assertNotNull(_dataSource);
        }
    }
}
