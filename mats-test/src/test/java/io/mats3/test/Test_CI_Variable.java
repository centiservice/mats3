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
