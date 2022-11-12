package io.mats3.spring.matsmappings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.test.MatsTestContext;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * @author Endre Stølsvik 2019-08-13 22:13 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestContext
public class MatsSpringDefinedTest_MatsClassMapping_VoidReturn {
    private static final String ENDPOINT_ID = "MatsClassMapping.";
    private static final String MULTI_STAGE_TERMINATOR = "AppMain";
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

    /**
     * Creating a Terminator (single stage) endpoint using @MatsClassMapping. This is pretty unorthodox, and not really
     * recommended. Notice how we must copy the /fields/ of the SpringTestStateTO to be able to receive it.
     */
    @Configuration
    @MatsClassMapping(ENDPOINT_ID + TERMINATOR)
    public static class MatsClassMapping_Terminator {
        @Inject
        private MatsTestLatch _latch;

        @Inject
        private SomeSimpleService _someSimpleService;

        // Copy the SpringTestStateTO fields, to be able to receive the state.
        public int numero;
        public String cuerda;

        @Stage(Stage.INITIAL)
        public void springMatsSingleEndpoint(SpringTestDataTO msg) {
            _latch.resolve(new SpringTestStateTO(numero, cuerda), msg);
        }
    }

    /**
     * Creating a "Leaf" Single Stage endpoint using @MatsClassMapping.
     */
    @Configuration
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

    @MatsClassMapping(ENDPOINT_ID + MULTI_STAGE_TERMINATOR)
    @Configuration // This must be here so that this Spring component is automatically picked up by test runner.
    public static class MatsStagedClass {

        // === DEPENDENCIES INJECTED BY SPRING

        @Inject
        private MatsTestLatch _latch;

        @Inject
        private SomeSimpleService _someSimpleService;

        @Inject
        // Think of this as a @Service or @Repository or something. I don't have much smart to inject.
        private SpringTestDataTO _someService;

        @Inject
        // Think of this as a @Service or @Repository or something. I don't have much smart to inject.
        private SpringTestStateTO _someOtherService;

        // === MATS' ProcessContext FOR CURRENT MESSAGE INJECTED BY MATS' "SpringConfig" LIBRARY

        private ProcessContext<Void> _context;

        // === STATE VARIABLES, kept by Mats between the different stages of the endpoint.

        private int _someStateInt;
        private String _someStateString;
        private SpringTestDataTO _someStateObject;
        private final List<String> _inLineInitializedField = new ArrayList<>();

        // Copy the SpringTestStateTO fields, to be able to receive the state.
        public int numero;
        public String cuerda;

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
        void evaluateSomeThings(ProcessContext<Void> context, SpringTestDataTO in) {
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

            // We're finished. This is a "void returning endpoint", i.e. a Terminator, so nothing to return.
            // Resolve latch for waiting test - notice that we instantiate SpringTestStateTO using the fields of 'this'.
            _latch.resolve(new SpringTestStateTO(numero, cuerda), in);
        }
    }

    @Inject
    private MatsInitiator _matsInitiator;

    @Inject
    private MatsTestLatch _latch;

    @Inject
    private SomeSimpleService _someSimpleService;

    @Test
    public void invokeSingleStageTerminator() {
        SpringTestDataTO dto = new SpringTestDataTO(12, "tolv");
        SpringTestStateTO sto = new SpringTestStateTO(256, "State");
        _matsInitiator.initiateUnchecked(
                init -> init.traceId("test_trace_id")
                        .from("FromId")
                        .to(ENDPOINT_ID + TERMINATOR)
                        .send(dto, sto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(dto, result.getData());
    }

    @Test
    public void invokeMultiStageTerminator() {
        // Zero out the count of our simple service.
        _someSimpleService.reset();

        SpringTestDataTO dto = new SpringTestDataTO(13, "tretten");
        SpringTestStateTO sto = new SpringTestStateTO(128, "State");
        _matsInitiator.initiateUnchecked(
                init -> init.traceId("test_trace_id")
                        .from("FromId")
                        .to(ENDPOINT_ID + MULTI_STAGE_TERMINATOR)
                        .send(dto, sto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ':' + ENDPOINT_LEAF), result.getData());

        // Check that the SomeSimpleService interactions took place
        // Should be 2: multi-stage-terminator itself + Leaf as request of multi-stage-terminator
        Assert.assertEquals(2, _someSimpleService.getCounterValue());
    }

    @Test
    public void employMultiStateTerminatorAsReplyTo() {
        // Zero out the count of our simple service.
        _someSimpleService.reset();

        SpringTestDataTO dto = new SpringTestDataTO(14, "fjorten");
        SpringTestStateTO sto = new SpringTestStateTO(64, "State");
        _matsInitiator.initiateUnchecked(
                init -> init.traceId("test_trace_id")
                        .from("FromId")
                        .to(ENDPOINT_ID + ENDPOINT_LEAF)
                        .replyTo(ENDPOINT_ID + MULTI_STAGE_TERMINATOR, sto)
                        .request(dto));

        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2 * 2,
                dto.string + ':' + ENDPOINT_LEAF + ':' + ENDPOINT_LEAF), result.getData());

        // Check that the SomeSimpleService interactions took place
        // Should be 3: Leaf + multi-stage-terminator itself + Leaf again as request of multi-stage-terminator
        Assert.assertEquals(3, _someSimpleService.getCounterValue());
    }

    private static class SomeSimpleService {
        private AtomicInteger _counter = new AtomicInteger(0);

        public void reset() {
            _counter.set(0);
        }

        public void increaseCounter() {
            _counter.incrementAndGet();
        }

        public int getCounterValue() {
            return _counter.get();
        }
    }
}