package io.mats3.spring.shutdownorder;

import java.util.Map;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.config.AbstractFactoryBean;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;

/**
 * The intention of this test is to verify that a {@link MatsFactory} created through the use of a
 * {@link AbstractFactoryBean} is shutdown BEFORE the {@link javax.jms.ConnectionFactory JMS ConnectionFactory}. The
 * reason we want to verify that this is true is to ensure that there are no unwanted exceptions being generated during
 * shutdown, as shutting down the connectionFactory before shutting down the MatsFactory can lead to.
 *
 * @author Kevin Mc Tiernan, 10-06-2020 - kmctiernan@gmail.com
 */
public class VerifyShutdownOrderUsingFactoryBeanTest extends AbstractFactoryBeanTestBase {

    /**
     * Default timeout in milliseconds utilized for methods requiring a specified timeout.
     *
     * @see MatsFactory#waitForReceiving(int)
     */
    private static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    @Inject
    private MatsFactory _matsFactory;

    @Test
    public void verifyShutdownOrder() {
        // At this point, the spring context has not been started and "this" has not been autowired.
        // Thus the injected matsFactory should be NULL.
        Assert.assertNull(_matsFactory);

        // :: Act - Start up
        startSpring();

        // :: Verify - Post start up
        // Spring context has been started, and autowiring should be complete. Verify that this is in fact the case.
        Assert.assertNotNull(_matsFactory);

        // ----- There are no Endpoints configured at this point!

        // There are no Endpoints at this point, and thus waitForReceiving() should return true.
        Assert.assertTrue(_matsFactory.waitForReceiving(DEFAULT_TIMEOUT_MILLIS));

        // The factoryConfig will only return "true" if there are any endpoints registered and running. Thus this
        // first call should result in a return "false".
        Assert.assertFalse(_matsFactory.getFactoryConfig().isRunning());

        // Register an endpoint for no other purpose than to verify that the MatsFactory is indeed running.
        MatsEndpoint<String, Void> anEndpoint = _matsFactory.single("anEndpoint", String.class, String.class, (ctx,
                msg) -> "I do nothing.");

        // This endpoint should immediately be running, since the MatsFactory is started.
        Assert.assertTrue(anEndpoint.getEndpointConfig().isRunning());

        // Wait for the endpoint to enter the receive-loop - If you change the timeout to "1" milliseconds you can
        // observe the fact that the endpoint hasn't done that yet (Usually works).
        Assert.assertTrue(anEndpoint.waitForReceiving(DEFAULT_TIMEOUT_MILLIS));

        // Secondary call to the "isRunning()", notice that this of course also returns true.
        Assert.assertTrue(anEndpoint.getEndpointConfig().isRunning());

        // There is now a 1 endpoint registered within the Factory and this endpoint should be running. Thus
        // the factory should now indicate that it is indeed running.
        Assert.assertTrue(_matsFactory.getFactoryConfig().isRunning());

        // :: Act - Shutdown
        stopSpring();

        // :: Verify - Post shutdown
        // ---- At this point everything should be stopped, lets verify this.

        // Shutting down the MatsFactory has happened when we stopped the springContext and should have removed the
        // StageProcessors from the endpoint. This means that the "isRunning()" call to the endpoint should now return
        // "false".
        Assert.assertFalse(anEndpoint.getEndpointConfig().isRunning());

        // The MatsFactory "isRunning()" will now return "false" as even though it as an endpoint within, the
        // the shutdown of the factory cause all the stageProcessors of the endpoint to be shutdown and removed
        // from the endpoint.
        Assert.assertEquals(1, _matsFactory.getEndpoints().size());
        Assert.assertFalse(_matsFactory.getFactoryConfig().isRunning());

        // Verify that stopping the Endpoint is idempotent.
        Assert.assertTrue(anEndpoint.stop(DEFAULT_TIMEOUT_MILLIS));

        // Verify that stopping the MatsFactory is idempotent.
        Assert.assertTrue(_matsFactory.stop(DEFAULT_TIMEOUT_MILLIS));

        Map<String, Boolean> stoppedServices = _stoppedRegistry.getStoppedServices();

        // Assert the order of which the Factory and the underlying JMS broker was shutdown.
        // Correct order: 1# Factory - 2# Broker.
        Assert.assertEquals(stoppedServices.size(), 2);
        Assert.assertTrue(stoppedServices.containsKey(MatsFactoryVerifiableStopWrapper.class.getSimpleName()));
        Assert.assertTrue(stoppedServices.containsKey(MatsLocalVmActiveMqVerifiableStopWrapper.class.getSimpleName()));

        Assert.assertTrue(stoppedServices.get(MatsFactoryVerifiableStopWrapper.class.getSimpleName()));
        Assert.assertTrue(stoppedServices.get(MatsLocalVmActiveMqVerifiableStopWrapper.class.getSimpleName()));
    }
}
