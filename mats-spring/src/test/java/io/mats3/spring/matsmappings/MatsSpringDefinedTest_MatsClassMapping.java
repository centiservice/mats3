package io.mats3.spring.matsmappings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * @author Endre Stølsvik 2019-08-13 22:13 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class MatsSpringDefinedTest_MatsClassMapping {
    private static final Logger log = MatsTestHelp.getClassLogger();

    private static final String ENDPOINT_ID = "MatsClassMapping.";
    private static final String ENDPOINT_MAIN = "AppMain";
    private static final String ENDPOINT_LEAF = "Leaf";
    private static final String TERMINATOR = "Terminator";

    @Configuration
    public static class BeanConfig {
        @Bean
        protected SpringTestDataTO someService() {
            return new SpringTestDataTO(2, "to");
        }

        @Bean
        protected SpringTestStateTO someOtherService() {
            return new SpringTestStateTO(5, "fem");
        }

        @Bean
        protected SomeSimpleService someSimpleService() {
            return new SomeSimpleService();
        }
    }

    @Configuration
    public static class SimpleEndpointsConfig {
        @Inject
        private MatsTestLatch _latch;

        @MatsMapping(ENDPOINT_ID + TERMINATOR)
        public void springMatsTerminatorEndpoint(ProcessContext<Void> context,
                @Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            // Dump the MatsTrace
            log.info("MatsTrace XXXXXXXXXXXXXXXXXXX:\n" + context);


            _latch.resolve(state, msg);
        }

        /**
         * Creating a "Leaf" Single Stage endpoint using @MatsClassMapping.
         */
        @MatsClassMapping(ENDPOINT_ID + ENDPOINT_LEAF)
        public static class MatsClassMapping_Leaf {
            @Inject
            private SomeSimpleService _someSimpleService;

            @Stage(Stage.INITIAL)
            public SpringTestDataTO springMatsSingleEndpoint(SpringTestDataTO msg) {
                // Interact with Spring injected service
                _someSimpleService.increaseCounter();
                return new SpringTestDataTO(msg.number * 2, msg.string + ':' + ENDPOINT_LEAF);
            }
        }
    }

    @MatsClassMapping(ENDPOINT_ID + ENDPOINT_MAIN)
    @Configuration // This must be here so that this Spring component is automatically picked up by test runner.
    public static class MatsStagedClass {

        // === DEPENDENCIES INJECTED BY SPRING

        @Inject
        private SomeSimpleService _someSimpleService;

        @Inject
        // Think of this as a @Service or @Repository or something. I don't have much smart to inject.
        private SpringTestDataTO _someService;

        @Inject
        // Think of this as a @Service or @Repository or something. I don't have much smart to inject.
        private SpringTestStateTO _someOtherService;

        // === MATS' ProcessContext FOR CURRENT MESSAGE INJECTED BY MATS' "SpringConfig" LIBRARY

        private ProcessContext<SpringTestDataTO> _context;

        // === STATE VARIABLES, kept by Mats between the different stages of the endpoint.

        private int _someStateInt;
        private String _someStateString;
        private SpringTestDataTO _someStateObject;
        private List<String> _inLineInitializedField = new ArrayList<>();

        // === ENDPOINT STAGES

        @Stage(Stage.INITIAL)
        void receiveAndCheckValidity(SpringTestDataTO in) {
            // Assert that state is empty (null, zero)
            Assert.assertEquals(0, _someStateInt);
            Assert.assertNull(_someStateString);
            Assert.assertNull(_someStateObject);
            Assert.assertEquals(0, _inLineInitializedField.size());

            // Set some state for next stage.
            _someStateInt = -10;
            _someStateString = "SetFromInitial";
            _someStateObject = null;
            _inLineInitializedField.add("Endre testing");
            _inLineInitializedField.add("More test");

            // Interact with Spring injected service
            _someSimpleService.increaseCounter();

            // Assert that we have the Spring injected "services" in place, as expected.
            Assert.assertEquals(new SpringTestDataTO(2, "to"), _someService);
            Assert.assertEquals(new SpringTestStateTO(5, "fem"), _someOtherService);

            // Do a request to a service
            _context.request(ENDPOINT_ID + ENDPOINT_LEAF, new SpringTestDataTO(in.number, in.string));
        }

        @Stage(10)
        void evaluateSomeThings(ProcessContext context, SpringTestDataTO in) {
            // Assert that state is kept from previous stage
            Assert.assertEquals(-10, _someStateInt);
            Assert.assertEquals("SetFromInitial", _someStateString);
            Assert.assertNull(_someStateObject);
            Assert.assertEquals(2, _inLineInitializedField.size());
            Assert.assertEquals("Endre testing", _inLineInitializedField.get(0));
            Assert.assertEquals("More test", _inLineInitializedField.get(1));
            Assert.assertSame(_context, context);

            // Don't change state..

            // Assert that we have the Spring injected "services" in place, as expected.
            Assert.assertEquals(new SpringTestDataTO(2, "to"), _someService);
            Assert.assertEquals(new SpringTestStateTO(5, "fem"), _someOtherService);

            // Jump to next stage
            _context.next(in);
        }

        @Stage(20)
        void evaluateSomeMoreThings(SpringTestDataTO in, ProcessContext context) {
            // Assert that state is kept from previous stage
            Assert.assertEquals(-10, _someStateInt);
            Assert.assertEquals("SetFromInitial", _someStateString);
            Assert.assertNull(_someStateObject);
            Assert.assertEquals(2, _inLineInitializedField.size());
            Assert.assertEquals("Endre testing", _inLineInitializedField.get(0));
            Assert.assertEquals("More test", _inLineInitializedField.get(1));
            Assert.assertSame(_context, context);

            // Set some state for next stage.
            _someStateInt = 10;
            _someStateString = "SetFromStageA";
            _someStateObject = new SpringTestDataTO(57473, "state");
            _inLineInitializedField.add("Even moar testing");

            // Interact with Spring injected service
            _someSimpleService.increaseCounter();

            // Assert that we have the Spring injected "services" in place, as expected.
            Assert.assertEquals(new SpringTestDataTO(2, "to"), _someService);
            Assert.assertEquals(new SpringTestStateTO(5, "fem"), _someOtherService);

            // Jump to next stage: Notice the use of a different DTO here (just using the STO since it was here)
            _context.next(new SpringTestStateTO((int) in.number * 3, in.string + ":next"));
        }

        @Stage(30)
        void processSomeStuff(@Dto SpringTestStateTO in, String anotherParameterMaybeUsedForTesting,
                ProcessContext context) {
            // Assert that state is kept from previous stage
            Assert.assertEquals(10, _someStateInt);
            Assert.assertEquals("SetFromStageA", _someStateString);
            Assert.assertEquals(new SpringTestDataTO(57473, "state"), _someStateObject);
            Assert.assertEquals(3, _inLineInitializedField.size());
            Assert.assertEquals("Endre testing", _inLineInitializedField.get(0));
            Assert.assertEquals("More test", _inLineInitializedField.get(1));
            Assert.assertEquals("Even moar testing", _inLineInitializedField.get(2));
            Assert.assertSame(_context, context);

            // Set some state for next stage.
            _someStateInt = 20;
            _someStateString = "SetFromStageB";
            _someStateObject = new SpringTestDataTO(314159, "pi * 100.000");
            _inLineInitializedField.set(1, "Ok, replacing a value then");

            // Do a stash, to check the ProcessContext wrapping (not handled, should not need).
            _context.stash();

            // Interact with Spring injected service
            _someSimpleService.increaseCounter();

            // Assert that we have the Spring injected "services" in place, as expected.
            Assert.assertEquals(new SpringTestDataTO(2, "to"), _someService);
            Assert.assertEquals(new SpringTestStateTO(5, "fem"), _someOtherService);

            // Do a request to a service
            _context.request(ENDPOINT_ID + ENDPOINT_LEAF, new SpringTestDataTO(in.numero, in.cuerda));
        }

        @Stage(40)
        SpringTestDataTO processMoreThenReply(boolean primitiveBooleanParameter,
                byte byteP, short shortP, int intP, long longP, @Dto SpringTestDataTO in,
                float floatP, double doubleP, List<String> meaninglessList) {
            // Assert that state is kept from previous stage
            Assert.assertEquals(20, _someStateInt);
            Assert.assertEquals("SetFromStageB", _someStateString);
            Assert.assertEquals(new SpringTestDataTO(314159, "pi * 100.000"), _someStateObject);
            Assert.assertEquals(3, _inLineInitializedField.size());
            Assert.assertEquals("Endre testing", _inLineInitializedField.get(0));
            Assert.assertEquals("Ok, replacing a value then", _inLineInitializedField.get(1));
            Assert.assertEquals("Even moar testing", _inLineInitializedField.get(2));

            // Interact with Spring injected service
            _someSimpleService.increaseCounter();

            // Assert that we have the Spring injected "services" in place, as expected.
            Assert.assertEquals(new SpringTestDataTO(2, "to"), _someService);
            Assert.assertEquals(new SpringTestStateTO(5, "fem"), _someOtherService);

            // Reply to caller with our amazing result.
            return new SpringTestDataTO(in.number * 5, in.string + ':' + ENDPOINT_MAIN);
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Inject
    private SomeSimpleService _someSimpleService;

    @Test
    public void doTest() {
        SpringTestStateTO sto = new SpringTestStateTO(256, "State");
        SpringTestDataTO dto = new SpringTestDataTO(12, "tolv");
        _matsInitiator.initiateUnchecked(
                init -> {
                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("test"))
                            .to(ENDPOINT_ID + ENDPOINT_MAIN)
                            .replyTo(ENDPOINT_ID + TERMINATOR, sto);
                    init.request(dto);
                });

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 3 * 2 * 5,
                dto.string + ':' + ENDPOINT_LEAF + ":next" + ':' + ENDPOINT_LEAF + ':' + ENDPOINT_MAIN),
                result.getData());

        // Check that the SomeSimpleService interactions took place
        Assert.assertEquals(6, _someSimpleService.getCounterValue());
    }

    private static class SomeSimpleService {
        private AtomicInteger _counter = new AtomicInteger(0);

        public void increaseCounter() {
            _counter.incrementAndGet();
        }

        public int getCounterValue() {
            return _counter.get();
        }
    }
}