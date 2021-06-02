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
