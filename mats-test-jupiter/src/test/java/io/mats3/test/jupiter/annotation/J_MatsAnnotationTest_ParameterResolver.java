package io.mats3.test.jupiter.annotation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.mats3.MatsFactory;
import io.mats3.util.MatsFuturizer;

/**
 * Test to demonstrate how to use the {@link MatsTest} annotation to inject a MatsFactory or MatsFuturizer into a
 * test.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest
class J_MatsAnnotationTest_ParameterResolver {

    @Nested
    class ParameterInjection {

        @Test
        void testMatsFactoryAvailable(MatsFactory matsFactory) {
            Assertions.assertNotNull(matsFactory);
        }

        @Test
        void testMatsFuturizerAvailable(MatsFuturizer matsFuturizer) {
            Assertions.assertNotNull(matsFuturizer);
        }
    }

    @Nested
    class FieldInjection {
        private final MatsFactory _matsFactory;
        private final MatsFuturizer _matsFuturizer;

        FieldInjection(MatsFactory matsFactory, MatsFuturizer matsFuturizer) {
            _matsFactory = matsFactory;
            _matsFuturizer = matsFuturizer;
        }

        @Test
        void testFieldValuesSet() {
            Assertions.assertNotNull(_matsFactory);
            Assertions.assertNotNull(_matsFuturizer);
        }
    }


}
