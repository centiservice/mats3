package io.mats3.spring;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsSpringAnnotationRegistration.MatsSpringConfigException;
import io.mats3.spring.test.MatsTestContext;

/**
 * Test that lets us check that we log hard if we're missing a no-args constructor for @MatsClassMapping (we'll in a
 * later version throw). Note that when using Jackson for serialization, as the {@link MatsSerializerJson} does, this
 * will throw anyway since Jackson itself requires this.
 * <p/>
 * Note: This is a "manual test": You need to check the log for the expected "HARD WARNING - DEPRECATION!!" log line.
 *
 * @author Endre Stølsvik 2023-09-16 13:06 - http://stolsvik.com/, endre@stolsvik.com
 */
@MatsTestContext
public class Test_MissingNoArgsConstructor {
    /**
     * Creating a "Leaf" Single Stage endpoint using @MatsClassMapping.
     */
    @Configuration
    @MatsClassMapping("test")
    public static class MatsTestEndpoint {
        private final transient SomeSimpleService _someSimpleService;

        @Inject
        MatsTestEndpoint(SomeSimpleService someSimpleService) {
            _someSimpleService = someSimpleService;
        }

        @Stage(Stage.INITIAL)
        public SpringTestDataTO springMatsSingleEndpoint(SpringTestDataTO msg) {
            _someSimpleService.increaseCounter();
            return new SpringTestDataTO(1, "two");
        }
    }

    @Service
    private static class SomeSimpleService {
        private final AtomicInteger _counter = new AtomicInteger(0);

        public void increaseCounter() {
            _counter.incrementAndGet();
        }
    }

    @Test(expected = MatsSpringConfigException.class)
    public void test() {
        // Just start up Spring, which will fail due to missing no-args constructor.
        new AnnotationConfigApplicationContext(this.getClass(), MatsTestEndpoint.class, SomeSimpleService.class);
    }
}
