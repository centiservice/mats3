package io.mats3.test.jupiter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Doesn't really <i>test</i> anything, just a driver to follow the logging output.
 */
public class J_ExtensionMatsNested {
    private static final Logger log = LoggerFactory.getLogger(J_ExtensionMatsNested.class);

    @RegisterExtension
    private static final Extension_Mats MATS = Extension_Mats.create();

    @Test
    void root() {
        log.info("TEST root()");
    }

    @Nested
    class NestedClass1 {
        @Test
        void nestedMethod1() {
            log.info("TEST nestedMethod1()");
        }

        @Nested
        class NestedClass1_a {
            @Test
            void nestedMethod1_a() {
                log.info("TEST nestedMethod1_a()");
            }
        }

        @Nested
        class NestedClass1_b {
            @Test
            void nestedMethod1_b() {
                log.info("TEST nestedMethod1_b()");
            }
        }
    }

    @Nested
    class NestedClass2 {
        @Test
        void nestedMethod2() {
            log.info("TEST nestedMethod2()");
        }
    }
}
