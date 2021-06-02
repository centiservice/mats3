package io.mats3.spring.test;

/**
 * A <i>State Transfer Object</i> meant for Spring unit tests.
 */
public class SpringTestStateTO {
    public int number;
    public String string;

    public SpringTestStateTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public SpringTestStateTO(int number, String string) {
        this.number = number;
        this.string = string;
    }

    @Override
    public int hashCode() {
        return ((int) Double.doubleToLongBits(number) * 4549) + (string != null ? string.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SpringTestStateTO)) {
            throw new AssertionError(SpringTestStateTO.class.getSimpleName() + " was attempted equalled to [" + obj
                    + "].");
        }
        SpringTestStateTO other = (SpringTestStateTO) obj;
        if ((this.string == null) ^ (other.string == null)) {
            return false;
        }
        return (this.number == other.number) && ((this.string == null) || (this.string.equals(other.string)));
    }

    @Override
    public String toString() {
        return "StateTO [number=" + number + ", string=" + string + "]";
    }
}