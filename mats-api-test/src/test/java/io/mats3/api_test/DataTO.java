package io.mats3.api_test;

import java.util.Objects;

/**
 * A <i>Data Transfer Object</i> meant for unit tests.
 * <p>
 * Note about DTOs in general: The DTOs used in MATS endpoints are to be considered their public interface, and
 * should be documented thoroughly.
 */
public class DataTO {
    public double number;
    public String string;

    // This is used for the "Test_ComplexLargeMultiStage" to tell the service what it should multiply 'number' with..!
    public int multiplier;

    public DataTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public DataTO(double number, String string) {
        this.number = number;
        this.string = string;
    }

    public DataTO(double number, String string, int multiplier) {
        this.number = number;
        this.string = string;
        this.multiplier = multiplier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        // NOTICE: Not Class-equals, but "instanceof", since we accept the "SubDataTO" too.
        if (o == null || !(o instanceof DataTO)) return false;
        DataTO dataTO = (DataTO) o;
        return Double.compare(dataTO.number, number) == 0 &&
                multiplier == dataTO.multiplier &&
                Objects.equals(string, dataTO.string);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, string, multiplier);
    }

    @Override
    public String toString() {
        return "DataTO [number=" + number
                + ", string=" + string
                + (multiplier != 0 ? ", multiplier="+multiplier : "")
                + "]";
    }
}