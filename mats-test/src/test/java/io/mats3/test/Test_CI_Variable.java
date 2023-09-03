package io.mats3.test;

import org.junit.Test;

/**
 * @author Endre Stølsvik 2022-10-03 23:11 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_CI_Variable {
    @Test
    public void printCiVariable() {
        System.out.println("### System.getenv('CI'): " + System.getenv("CI"));
        System.out.println("### MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURENCE: "
                + MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE);
        System.out.println("### Running on Java version: " + System.getProperty("java.version"));
        System.out.println("### .. $JAVA_HOME: " + System.getenv("JAVA_HOME"));
    }
}
