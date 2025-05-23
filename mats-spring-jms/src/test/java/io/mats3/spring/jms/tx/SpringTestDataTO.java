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

package io.mats3.spring.jms.tx;

/**
 * A <i>Data Transfer Object</i> meant for unit tests.
 * <p>
 * Note about DTOs in general: The DTOs used in MATS endpoints are to be considered their public interface, and should
 * be documented thoroughly.
 */
public class SpringTestDataTO {
    public double number;
    public String string;

    public SpringTestDataTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public SpringTestDataTO(double number, String string) {
        this.number = number;
        this.string = string;
    }

    @Override
    public int hashCode() {
        return ((int) Double.doubleToLongBits(number) * 4549) + (string != null ? string.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof SpringTestDataTO)) {
            throw new AssertionError(SpringTestDataTO.class.getSimpleName() + " was attempted equalled to [" + obj + "].");
        }
        SpringTestDataTO other = (SpringTestDataTO) obj;
        if ((this.string == null) ^ (other.string == null)) {
            return false;
        }
        return (this.number == other.number) && ((this.string == null) || (this.string.equals(other.string)));
    }

    @Override
    public String toString() {
        return "DataTO [number=" + number + ", string=" + string + "]";
    }
}