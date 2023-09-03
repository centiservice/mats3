package io.mats3.api_test.types;

import java.util.HashSet;
import java.util.Set;

import io.mats3.api_test.DataTO;

/**
 * A subtype of the {@link DataTO}, for testing covariant reply and contravariant arguments, contains one more field.
 *
 * @author Endre Stølsvik - 2016 - http://endre.stolsvik.com
 */
public class SubDataTO extends DataTO {
    public Set<String> stringSet;

    public SubDataTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public SubDataTO(double number, String string, String setString) {
        super(number, string);
        if (setString != null) {
            stringSet = new HashSet<>();
            stringSet.add(setString);
        }
    }

    @Override
    public int hashCode() {
        return (31 * super.hashCode()) + ((stringSet == null) ? 0 : stringSet.hashCode());
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SubDataTO)) {
            throw new AssertionError(SubDataTO.class.getSimpleName() + " was attempted equalled to [" + obj + "].");
        }
        if (!super.equals(obj)) {
            return false;
        }
        SubDataTO other = (SubDataTO) obj;
        if (stringSet == null) {
            return other.stringSet == null;
        }
        return stringSet.equals(other.stringSet);
    }

    @Override
    public String toString() {
        return "SubDataTO [number=" + number
                + ", string=" + string
                + ", multiplier=" + multiplier
                + ", stringSet=" + stringSet
                + "]";
    }
}
