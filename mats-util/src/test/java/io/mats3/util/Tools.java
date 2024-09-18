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
