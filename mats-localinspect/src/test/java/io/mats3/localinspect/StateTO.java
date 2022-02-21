package io.mats3.localinspect;

/**
 * @author Endre Stølsvik 2022-02-21 21:47 - http://stolsvik.com/, endre@stolsvik.com
 */
public class StateTO {
    public int number1;
    public double number2;

    /**
     * Provide a Default Constructor for the Jackson JSON-lib which needs default constructor. It is needed explicitly
     * in this case since we also have a constructor taking arguments - this would normally not be needed for a STO
     * class.
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
