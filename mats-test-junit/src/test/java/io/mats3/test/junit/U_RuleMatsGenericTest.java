package io.mats3.test.junit;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Similar to {@link U_RuleMatsTest} illustrating the usage of {@link Rule_Mats}, this class illustrates the usage of
 * {@link Rule_MatsGeneric}.
 *
 * @author Kevin Mc Tiernan, 2020-11-09, kmctiernan@gmail.com
 */
public class U_RuleMatsGenericTest {

    @ClassRule
    public static Rule_MatsGeneric<String> MATS = new Rule_MatsGeneric<>(MatsSerializerJson.create());

    @BeforeClass
    public static void setupEndpoint() {
        // :: Setup
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

        // :: Verify
        Assert.assertEquals("Hello World!", reply);
    }
}
