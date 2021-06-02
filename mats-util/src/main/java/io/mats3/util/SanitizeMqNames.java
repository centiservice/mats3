package io.mats3.util;

import java.util.regex.Pattern;

/**
 * Utility class for replacing dodgy characters from queue/topic names, and names in general, in the Message Broker
 * world - it is quite restrictive, replacing any character not in [a-z,A-Z,0-9,.,_,-] (lower alpha, upper alpha,
 * digits, dot, underscore, minus/dash) with '_'.
 * <p />
 * The code is literally: <br />
 * &nbsp;&nbsp;&nbsp;&nbsp;<code>Pattern.compile("[^a-zA-Z0-9._\\-]").matcher(input).replaceAll("_")</code> <br />
 * .. but the compiled pattern is statically cached.
 * <p />
 * Its functionality may very well be copied to where its logic is needed if not desired to depend on 'mats-util'.
 */
public class SanitizeMqNames {

    public static final Pattern MQ_NAME_REPLACE_PATTERN = Pattern.compile("[^a-zA-Z0-9._\\-]");

    /**
     * Sanitizes the input, only allowing [a-z,A-Z,0-9,.,-,_] (last being dot, minus, underscore)
     * 
     * @param input
     *            the name to sanitize
     * @return the name after being run through the code
     *         '<code>Pattern.compile("[^a-zA-Z0-9._\\-]").matcher(input).replaceAll("_")</code></code>'.
     */
    public static String sanitizeName(String input) {
        return MQ_NAME_REPLACE_PATTERN.matcher(input).replaceAll("_");
    }
}
