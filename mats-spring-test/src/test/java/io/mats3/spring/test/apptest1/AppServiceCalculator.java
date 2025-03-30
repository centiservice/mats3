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

package io.mats3.spring.test.apptest1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Service
public class AppServiceCalculator {
    private static final Logger log = LoggerFactory.getLogger(AppServiceCalculator.class);

    public static final double Π = Math.PI;
    public static final double Φ = (1d + Math.sqrt(5)) / 2d;

    public double multiplyByΠ(double number) {
        double result = number * Π;
        log.info("Calculator got number [" + number + "], multipled by π, this becomes [" + result + "].");
        return result;
    }

    public double multiplyByΦ(double number) {
        double result = number * Φ;
        log.info("Calculator got number [" + number + "], multipled by φ, this becomes [" + result + "].");
        return result;
    }

}
