package io.mats3.lib_test;

/**
 * A <i>State Transfer Object</i> meant for unit tests.
 * <p>
 * Note about STOs in general: The STOs in use by the different multi-stage endpoints are to be considered private
 * implementation details, and should typically reside as a private inner class of the endpoint employing them.
 * <p>
 * Typically, a STO class should just be fields that are access directly - they are to be employed like a "this"
 * object for the state that needs to traverse stages. The equals(..), hashCode() and toString() of this testing STO
 * is just to facilitate efficient testing.
 * <p>
 * The possibility to send STOs along with the request should only be employed if both the sender and the endpoint
 * to which the state is sent along to, resides in the same code base, and hence still can be considered private
 * implementation details.
 */
public class StateTO {
    public int number1;
    public double number2;

    /**
     * Provide a Default Constructor for the Jackson JSON-lib which needs default constructor. It is needed
     * explicitly in this case since we also have a constructor taking arguments - this would normally not be needed
     * for a STO class.
     */
    public StateTO() {
    }

    public StateTO(int number1, double number2) {
        this.number1 = number1;
        this.number2 = number2;
    }

    @Override
    public int hashCode() {
        return (number1 * 3539) + (int) Double.doubleToLongBits(number2 * 99713.80309);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StateTO)) {
            throw new AssertionError(StateTO.class.getSimpleName() + " was attempted equalled to [" + obj + "].");
        }
        StateTO other = (StateTO) obj;
        return (this.number1 == other.number1) && (this.number2 == other.number2);
    }

    @Override
    public String toString() {
        return "StateTO [number1=" + number1 + ", number2=" + number2 + "]";
    }
}