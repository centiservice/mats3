package io.mats3.test.jupiter;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Similar to {@link J_ExtensionMatsTest} illustrating the usage of {@link Extension_Mats}, this class illustrates the
 * usage of {@link Extension_MatsGeneric}.
 *
 * @author Kevin Mc Tiernan, 2020-11-09, kmctiernan@gmail.com
 */
public class J_ExtensionMatsGenericTest {

    @RegisterExtension
    public static final Extension_MatsGeneric<String> MATS = new Extension_MatsGeneric<>(MatsSerializerJson.create());

    @BeforeAll
    static void setupEndpoint() {
        // :: Arrange
        MATS.getMatsFactory().single("MyEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg);
    }

    /**
     * Simple test to verify that we actually have a factory and a valid broker.
     */
    @Test
    public void verifyValidMatsFactoryCreated() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Act
        String reply = MATS.getMatsFuturizer().futurizeNonessential("VerifyValidMatsFactory",
                getClass().getSimpleName(),
                "MyEndpoint",
                String.class,
                "World!")
                .thenApply(Reply::getReply)
                .get(10, TimeUnit.SECONDS);

        // :: Assert
        Assertions.assertEquals("Hello World!", reply);
    }
}
