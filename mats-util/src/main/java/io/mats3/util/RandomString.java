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

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * If you need a random string for <u>a part of the traceId</u> (Read NOTE about traceIds!), use this class instead of
 * {@link UUID}, because UUID has low entropy density with only 4 bits per character, and dashes.
 */
public class RandomString {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * According to the internet, there are ~7.5e18 grains of sand on all the beaches on earth. There are 62 points in
     * the alphabet used to create these random strings. 62^20 is 7.0e35. There is slightly more room in a random UUID,
     * but I felt this was adequate.
     *
     * @return a 20-char length string, which should be plenty enough uniqueness for pretty much anything - you'll have
     *         to make <i>extremely</i> many such strings to get a collision.
     */
    public static String randomCorrelationId() {
        return randomString(20);
    }

    /**
     * @return a 6-char length string, which should be enough to make an already-very-unique TraceId become unique
     *         enough to be pretty sure that you will not ever have problems uniquely identifying log lines for a call
     *         flow.
     */
    public static String partTraceId() {
        return randomString(6);
    }

    /**
     * @param length
     *            the desired length of the returned random string.
     * @return a random string of the specified length.
     */
    public static String randomString(int length) {
        StringBuilder buf = new StringBuilder(length);
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++)
            buf.append(ALPHABET.charAt(tlr.nextInt(ALPHABET.length())));
        return buf.toString();
    }

}
