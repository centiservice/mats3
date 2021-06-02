package io.mats3.spring.test.apptest1;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.test.junit.Rule_MatsEndpoint;
import io.mats3.spring.test.MatsTestInfrastructureConfiguration;
import io.mats3.spring.test.SpringInjectRulesAndExtensions;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.test.MatsTestHelp;

/**
 * Test that pieces together the needed components from the application <i>except</i> the
 * {@link AppEndpoint_LeafServices} which will be mocked using {@link Rule_MatsEndpoint}, and employs
 * {@link MatsTestInfrastructureConfiguration} to get Mats infrastructure.
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { AppEndpoint_MainService.class, AppServiceCalculator.class,
        AppServiceMatsInvoker.class, MatsTestInfrastructureConfiguration.class })
@SpringInjectRulesAndExtensions
public class Test_D_MockEndpoints_Rule_MatsEndpoint {
    private static final Logger log = MatsTestHelp.getClassLogger();
    @Rule
    public Rule_MatsEndpoint<SpringTestDataTO, SpringTestDataTO> _leaf1 = Rule_MatsEndpoint.create(
            AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFSERVICE1, SpringTestDataTO.class, SpringTestDataTO.class);

    @Rule
    public Rule_MatsEndpoint<SpringTestDataTO, SpringTestDataTO> _leaf2 = Rule_MatsEndpoint.create(
            AppMain_MockAndTestingHarnesses.ENDPOINT_ID_LEAFSERVICE2, SpringTestDataTO.class, SpringTestDataTO.class);

    @Inject
    private AppServiceMatsInvoker _matsInvoker;

    @Test
    public void mock1() {
        // :: Arrange
        _leaf1.setProcessLambda((ctx, msg) -> new SpringTestDataTO(msg.number * 2, msg.string + ":MockedLeaf1A"));
        _leaf2.setProcessLambda((ctx, msg) -> new SpringTestDataTO(msg.number * 3, msg.string + ":MockedLeaf2A"));

        // :: Act
        SpringTestDataTO result = _matsInvoker.invokeMatsWithFuturizer(10, "MockTest1");

        // :: Assert
        // Incoming message to leaf1
        SpringTestDataTO leaf1_incoming = _leaf1.waitForRequest();
        Assert.assertEquals(10, leaf1_incoming.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793)", leaf1_incoming.string);

        // Incoming message to leaf2
        SpringTestDataTO leaf2_incoming = _leaf2.waitForRequest();
        Assert.assertEquals(10 * 2, leaf2_incoming.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793):MockedLeaf1A:(φ=1.618033988749895)",
                leaf2_incoming.string);

        // The actual result.
        Assert.assertEquals(10 * 2 * 3 * 10, result.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793):MockedLeaf1A"
                + ":(φ=1.618033988749895):MockedLeaf2A:TestApp_TwoMf.mainService",
                result.string);
    }

    @Test
    public void mock2() {
        // :: Arrange
        _leaf1.setProcessLambda((ctx, msg) -> new SpringTestDataTO(msg.number * 4, msg.string + ":MockedLeaf1B"));
        _leaf2.setProcessLambda((ctx, msg) -> new SpringTestDataTO(msg.number * 5, msg.string + ":MockedLeaf2B"));

        // :: Act
        SpringTestDataTO result = _matsInvoker.invokeMatsWithFuturizer(10, "MockTest1");

        // :: Assert
        // Incoming message to leaf1
        SpringTestDataTO leaf1_incoming = _leaf1.waitForRequest();
        Assert.assertEquals(10, leaf1_incoming.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793)", leaf1_incoming.string);

        // Incoming message to leaf2
        SpringTestDataTO leaf2_incoming = _leaf2.waitForRequest();
        Assert.assertEquals(10 * 4, leaf2_incoming.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793):MockedLeaf1B:(φ=1.618033988749895)",
                leaf2_incoming.string);

        Assert.assertEquals(10 * 4 * 5 * 10, result.number, 0d);
        Assert.assertEquals("MockTest1:(π=3.141592653589793):MockedLeaf1B"
                + ":(φ=1.618033988749895):MockedLeaf2B:TestApp_TwoMf.mainService",
                result.string);
    }
}
