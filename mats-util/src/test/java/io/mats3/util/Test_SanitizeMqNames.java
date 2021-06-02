package io.mats3.util;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that the {@link SanitizeMqNames} works as expected with some names that should not be changed, and some
 * names that include a bunch of charts that should be replaced with "_".
 */
public class Test_SanitizeMqNames {

    @Test
    public void test() {
        // Positives
        shouldNotChange("Endre");
        shouldNotChange("Endre.Test");
        shouldNotChange("Endre.Test_Test2");
        shouldNotChange("Endre.Test_Test2-Test3");
        shouldNotChange("Endre.Test_Test2-Test3.Random-Stuff_Just.Testing__Elg..--aaaabbbCCCDDD");

        // "Negatives" - i.e. should change
        shouldChange("#", "_");
        shouldChange(",", "_");
        shouldChange(" ", "_");
        shouldChange("Endre Stolsvik", "Endre_Stolsvik");
        shouldChange("!\"£$% ^&*(),", "____________");
        shouldChange(" Test0!Test1\"Test2£Test3$Test4%Test5^&*(),",
                "_Test0_Test1_Test2_Test3_Test4_Test5______");
        shouldChange("\uD83D\uDCA9", "_");
        shouldChange("Endre\uD83D\uDCA9Stolsvik", "Endre_Stolsvik");
    }

    private void shouldNotChange(String text) {
        Assert.assertEquals(text, SanitizeMqNames.sanitizeName(text));
    }

    private void shouldChange(String input, String expectedOutput) {
        Assert.assertEquals(expectedOutput, SanitizeMqNames.sanitizeName(input));
    }
}
