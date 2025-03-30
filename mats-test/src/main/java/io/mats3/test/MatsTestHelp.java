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
        return LoggerFactory.getLogger(getCallerClassNameAndMethod()[0]);
    }

    private static String internal(String name) {
        return getCallerClassSimpleName() + '.' + name;
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.Endpoint"</code>.
     */
    public static String endpoint() {
        return internal("Endpoint");
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.Endpoint.{what}"</code>.
     */
    public static String endpoint(String what) {
        return internal("Endpoint." + what);
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.Terminator"</code>.
     */
    public static String terminator() {
        return internal("Terminator");
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.Terminator"</code>.
     */
    public static String terminator(String what) {
        return internal("Terminator." + what);
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.traceId.{randomId}"</code> - <b>BUT PLEASE NOTE!! ALWAYS
     *         use a semantically meaningful, globally unique Id as traceId in production code!</b>
     */
    public static String traceId() {
        return getCallerClassSimpleNameAndMethod() + "_traceId." + randomId();
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}"</code>.
     */
    public static String from() {
        return getCallerClassSimpleNameAndMethod();
    }

    /**
     * @return a String <code>"{CallerClassSimpleName.method}.{what}"</code>.
     */
    public static String from(String what) {
        return getCallerClassSimpleNameAndMethod() + '.' + what;
    }

    /**
     * @return a random string of length 8 for testing.
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

    private static String getCallerClassSimpleNameAndMethod() {
        String[] classnameAndMethod = getCallerClassNameAndMethod();
        int lastDot = classnameAndMethod[0].lastIndexOf('.');
        return lastDot > 0
                ? classnameAndMethod[0].substring(lastDot + 1) + '.' + classnameAndMethod[1]
                : classnameAndMethod[0] + '.' + classnameAndMethod[1];
    }

    private static String getCallerClassSimpleName() {
        String[] classnameAndMethod = getCallerClassNameAndMethod();
        int lastDot = classnameAndMethod[0].lastIndexOf('.');
        return lastDot > 0
                ? classnameAndMethod[0].substring(lastDot + 1)
                : classnameAndMethod[0];
    }

    /**
     * from <a href="https://stackoverflow.com/a/11306854">Stackoverflow - Denys Séguret</a>.
     */
    private static String[] getCallerClassNameAndMethod() {
        StackTraceElement[] stElements = Thread.currentThread().getStackTrace();
        for (int i = 1; i < stElements.length; i++) {
            StackTraceElement ste = stElements[i];
            if (!ste.getClassName().equals(MatsTestHelp.class.getName())) {
                return new String[] { ste.getClassName(), ste.getMethodName() };
            }
        }
        throw new AssertionError("Could not determine calling class.");
    }
}
