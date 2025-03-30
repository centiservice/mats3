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