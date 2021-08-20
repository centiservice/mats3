package io.mats3.spring.test.apptest2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Assert;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.Sto;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories.TestCustomQualifier;
import io.mats3.test.MatsTestLatch;

/**
 * @author Endre Stølsvik 2019-06-17 23:48 - http://stolsvik.com/, endre@stolsvik.com
 */
@Service
public class AppEndpoints {
    public static final String THROW = "ThrowIt!";

    @Inject
    private MatsTestLatch _latch;

    /**
     * Test "Single" endpoint.
     * <p/>
     * Notice how we're using the same endpointId as {@link SingleEndpointUsingMatsClassMapping}, but this is OK since
     * this is on a different MatsFactory.
     */
    @MatsMapping(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
    @Qualifier("matsFactoryX")
    public SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) throws MatsRefuseMessageException {
        if (msg.string.equals(THROW)) {
            throw new MatsRefuseMessageException("Got asked to throw, so that we do!");
        }
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test Single-Stage @MatsClassMapping endpoint.
     * <p/>
     * Notice how we're using the same endpointId as {@link AppEndpoints#springMatsSingleEndpoint(SpringTestDataTO)},
     * but this is OK since this is on a different MatsFactory.
     */
    @MatsClassMapping(endpointId = AppMain_TwoMatsFactories.ENDPOINT_ID
            + ".single", matsFactoryQualifierValue = "matsFactoryY")
    static class SingleEndpointUsingMatsClassMapping {
        @Inject
        private AtomicInteger _atomicInteger;

        @Stage(0)
        SpringTestDataTO initialStage(SpringTestDataTO in) {
            _atomicInteger.incrementAndGet();
            return new SpringTestDataTO(in.number * 3, in.string + ":single_on_class");
        }
    }

    /**
     * Test "Terminator" endpoints, set up on both MatsFactories.
     */
    @MatsMapping(endpointId = AppMain_TwoMatsFactories.ENDPOINT_ID
            + ".terminator", matsFactoryCustomQualifierType = TestCustomQualifier.class)
    @MatsMapping(endpointId = AppMain_TwoMatsFactories.ENDPOINT_ID
            + ".terminator", matsFactoryQualifierValue = "matsFactoryY")
    public void springMatsTerminatorEndpoint(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    /**
     * Test Multi-Stage @MatsClassMapping endpoint.
     */
    @MatsClassMapping(AppMain_TwoMatsFactories.ENDPOINT_ID + ".multi")
    @TestCustomQualifier(region = "SouthWest") // This is the same as "matsFactoryX", the first.
    static class MultiEndPoint {
        @Inject
        private AtomicInteger _atomicInteger;

        private ProcessContext<SpringTestDataTO> _context;

        // State fields
        private double _statePi;
        private SpringTestDataTO _stateObject;
        private List<String> _inLineInitializedField = new ArrayList<>();

        @Stage(Stage.INITIAL)
        void initialStage(SpringTestDataTO in) {
            // Assert initial values of State
            Assert.assertEquals(0, _statePi, 0);
            Assert.assertNull(_stateObject);
            Assert.assertEquals(0, _inLineInitializedField.size());

            // Set some state
            _statePi = Math.PI;
            _stateObject = new SpringTestDataTO(Math.E, "This is state.");
            _inLineInitializedField.add("Endre testing");
            _inLineInitializedField.add("Moar test");

            // Perform some advanced stuff with our Dependency Injected Service..!
            _atomicInteger.incrementAndGet();

            _context.request(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single", new SpringTestDataTO(in.number,
                    in.string));
        }

        @Stage(10)
        SpringTestDataTO finalStage(SpringTestDataTO in) {
            // Assert that the state sticks along
            Assert.assertEquals(Math.PI, _statePi, 0d);
            Assert.assertEquals(new SpringTestDataTO(Math.E, "This is state."), _stateObject);
            Assert.assertEquals(2, _inLineInitializedField.size());
            Assert.assertEquals("Endre testing", _inLineInitializedField.get(0));
            Assert.assertEquals("Moar test", _inLineInitializedField.get(1));

            return new SpringTestDataTO(in.number * 3, in.string + ":multi");
        }
    }
}
