package io.mats3.test.jupiter.annotation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.mats3.test.jupiter.Extension_MatsEndpoint;
import io.mats3.util.MatsFuturizer;

/**
 * Test to demonstrate how to use the {@link MatsTest.Endpoint} annotation to create a MatsEndpoint for testing.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
@MatsTest
class J_MatsAnnotationTest_MatsEndpoint {

    private static final String ENDPOINT_ID = "TestEndpoint";

    @MatsTest.Endpoint(name = ENDPOINT_ID)
    private Extension_MatsEndpoint<String, String> _matsEndpoint;

    @Test
    void testMatsEndpointRegistered(MatsFuturizer matsFuturizer) {
        _matsEndpoint.setProcessLambda((ctx, msg) -> "Hello " + msg);

        String result = matsFuturizer.futurizeNonessential(
                "testMatsEndpointRegistered",
                "UnitTest",
                ENDPOINT_ID,
                String.class,
                "World").join().get();

        Assertions.assertEquals("Hello World", result);
    }
}
