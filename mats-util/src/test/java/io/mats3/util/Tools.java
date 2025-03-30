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

package io.mats3.util;

public class Tools {

    /**
     * Formats a nanos duration as a string with milliseconds precision, 2 decimals.
     */
    public static String ms2(long nanos) {
        return fd2(nanos / 1_000_000d);
    }

    /**
     * Formats a nanos duration as a string with milliseconds precision, 3 decimals.
     */
    public static String ms4(long nanos) {
        return fd4(nanos / 1_000_000d);
    }

    /**
     * Formats a double as a string with 2 decimals.
     */
    public static String fd2(double d) {
        return String.format("%.2f", d);
    }

    /**
     * Formats a double as a string with 3 decimals.
     */
    public static String fd4(double d) {
        return String.format("%.4f", d);
    }

    /**
     * Formats a long as a string with thousands separators.
     */
    public static String ft(long l) {
        return String.format("%,d", l);
    }
}
