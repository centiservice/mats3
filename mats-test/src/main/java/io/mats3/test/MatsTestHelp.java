package io.mats3.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.util.RandomString;

/**
 * Microscopic helper methods to create Loggers and Ids typically needed when making Mats tests.
 */
public class MatsTestHelp {
    /**
     * @return a SLF4J Logger with the classname of the calling class (using
     *         <code>Thread.currentThread().getStackTrace()</code> to figure this out).
     */
    public static Logger getClassLogger() {
        return LoggerFactory.getLogger(getCallerClassName());
    }

    /**
     * @return a String <code>"{CallerClassSimpleName}.{name}"</code>.
     */
    public static String endpointId(String name) {
        return getCallerClassSimpleName() + '.' + name;
    }

    /**
     * @return a String <code>"{CallerClassSimpleName}.Service"</code>.
     */
    public static String service() {
        return endpointId("Service");
    }

    /**
     * @return a String <code>"{CallerClassSimpleName}.Terminator"</code>.
     */
    public static String terminator() {
        return endpointId("Terminator");
    }

    /**
     * @return a String <code>"{CallerClassSimpleName}.traceId.{randomId}"</code>.
     */
    public static String traceId() {
        return getCallerClassSimpleName() + ".traceId." + randomId();
    }

    /**
     * @return a String <code>"{CallerClassSimpleName}.{what}"</code>.
     */
    public static String from(String what) {
        return getCallerClassSimpleName() + '.' + what;
    }

    /**
     * @return a random string of length 8 for testing - <b>BUT PLEASE NOTE!! ALWAYS use a semantically meaningful,
     *         globally unique Id as traceId in production code!</b>
     */
    public static String randomId() {
        return RandomString.randomString(8);
    }

    /**
     * Sleeps the specified number of milliseconds - can emulate processing time, primarily meant for the concurrency
     * tests.
     *
     * @param millis
     *            the number of millis to sleep
     * @throws AssertionError
     *             if an {@link InterruptedException} occurs.
     */
    public static void takeNap(int millis) throws AssertionError {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static String getCallerClassSimpleName() {
        String classname = getCallerClassName();
        int lastDot = classname.lastIndexOf('.');
        return lastDot > 0
                ? classname.substring(lastDot + 1)
                : classname;
    }

    /**
     * from <a href="https://stackoverflow.com/a/11306854">Stackoverflow - Denys Séguret</a>.
     */
    private static String getCallerClassName() {
        StackTraceElement[] stElements = Thread.currentThread().getStackTrace();
        for (int i = 1; i < stElements.length; i++) {
            StackTraceElement ste = stElements[i];
            if (!ste.getClassName().equals(MatsTestHelp.class.getName())) {
                return ste.getClassName();
            }
        }
        throw new AssertionError("Could not determine calling class.");
    }

}
