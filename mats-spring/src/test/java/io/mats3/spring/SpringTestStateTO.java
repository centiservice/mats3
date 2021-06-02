package io.mats3.spring;

/**
 * A <i>State Transfer Object</i> meant for Spring unit tests.
 */
public class SpringTestStateTO {
    public int numero;
    public String cuerda;

    public SpringTestStateTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public SpringTestStateTO(int numero, String cuerda) {
        this.numero = numero;
        this.cuerda = cuerda;
    }

    @Override
    public int hashCode() {
        return ((int) Double.doubleToLongBits(numero) * 4549) + (cuerda != null ? cuerda.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SpringTestStateTO)) {
            throw new AssertionError(SpringTestStateTO.class.getSimpleName() + " was attempted equalled to [" + obj
                    + "].");
        }
        SpringTestStateTO other = (SpringTestStateTO) obj;
        if ((this.cuerda == null) ^ (other.cuerda == null)) {
            return false;
        }
        return (this.numero == other.numero) && ((this.cuerda == null) || (this.cuerda.equals(other.cuerda)));
    }

    @Override
    public String toString() {
        return "StateTO [number=" + numero + ", string=" + cuerda + "]";
    }
}