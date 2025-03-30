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

import java.util.Objects;

/**
 * @author Endre Stølsvik 2022-02-21 21:46 - http://stolsvik.com/, endre@stolsvik.com
 */
public class DataTO {
    public double number;
    public String string;

    // This is used for the "Test_ComplexLargeMultiStage" to tell the service what it should multiply 'number'
    // with..!
    public int multiplier;

    public DataTO() {
        // For Jackson JSON-lib which needs default constructor.
    }

    public DataTO(double number, String string) {
        this.number = number;
        this.string = string;
        this.multiplier = 1;
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
        if (!(o instanceof DataTO)) return false;
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
                + (multiplier != 0 ? ", multiplier=" + multiplier : "")
                + "]";
    }
}
