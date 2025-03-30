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
