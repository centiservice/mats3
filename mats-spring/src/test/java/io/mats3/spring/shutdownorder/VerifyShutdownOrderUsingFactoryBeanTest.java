package io.mats3.spring.shutdownorder;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.AbstractFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_PoolingSerial;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.EnableMats;
import io.mats3.spring.matsfactoryqualifier.AbstractQualificationTest;
import io.mats3.test.broker.MatsTestBroker;

/**
 * The intention of this test is to verify that a {@link MatsFactory} created through the use of a
 * {@link AbstractFactoryBean} is shutdown BEFORE the {@link javax.jms.ConnectionFactory JMS ConnectionFactory}. The
 * reason we want to verify that this is true is to ensure that there are no unwanted exceptions being generated during
 * shutdown, as shutting down the connectionFactory before shutting down the MatsFactory can lead to.
 * <p>
 * The {@link MatsFactory} and {@link MatsTestBroker} beans created in this configuration have been wrapped so as to get
 * to intercept their "stop" and "close" calls, to be able to verify the order in which they are stopped.
 * <p />
 * Note that we do not use SpringRunner or other frameworks, but instead do all Spring config ourselves. This so that
 * the testing is as application-like as possible.
 *
 * @author Kevin Mc Tiernan, 2020-06-10 - kmctiernan@gmail.com, heavily inspired by {@link AbstractQualificationTest} by
 *         Endre Stølsvik
 * @author Endre Stølsvik, 2021-08-14 - endre@stolsvik.com, slight refactor.
 */
public class VerifyShutdownOrderUsingFactoryBeanTest {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected AnnotationConfigApplicationContext _springApplicationContext;

    protected void startSpring() {
        long nanosStart = System.nanoTime();
        log.info("STARTING SPRING for " + this.getClass().getSimpleName()
                + " - instantiating Spring ApplicationContext and refreshing it!");
        log.info(" \\- new'ing up AnnotationConfigApplicationContext, giving class [" + this.getClass()
                .getSimpleName() + "] as base.");
        // :: Create the Spring ApplicationContext, register the @Configuration class, and refresh (start) it.
        _springApplicationContext = new AnnotationConfigApplicationContext();
        _springApplicationContext.register(DI_MainContext.class);
        _springApplicationContext.refresh();
        double startTimeMs = (System.nanoTime() - nanosStart) / 1_000_000d;
        log.info(" \\- done, AnnotationConfigApplicationContext: [" + _springApplicationContext + "], took: ["
                + startTimeMs + " ms].");

        // Since this test is NOT run by the SpringRunner, the instance which this code is running in is not
        // managed by Spring, it is managed by JUnit.
        // Therefore, manually autowire this instance which JUnit has instantiated.
        _springApplicationContext.getAutowireCapableBeanFactory().autowireBean(this);
    }

    protected void stopSpring() {
        // :: Close Spring.
        long nanosStart = System.nanoTime();
        log.info("STOPPING SPRING for " + this.getClass().getSimpleName() + " - closing Spring ApplicationContext!");
        _springApplicationContext.close();
        log.info("done. took: [" + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms].");
    }

    // =============================================== App configuration ==============================================

    /**
     * Context was extracted into a separate class as the approach utilized inside {@link AbstractQualificationTest} did
     * not detected the usage of {@link Import} as utilized on {@link DI_MainContext}.
     */
    @Configuration
    @EnableMats
    public static class DI_MainContext {
        @Bean
        protected StoppedOrderRegistry stoppedOrderRegistry() {
            return new StoppedOrderRegistry();
        }

        @Bean
        protected MatsTestBroker matsTestBroker(StoppedOrderRegistry stoppedOrderRegistry) {
            return new MatsTestBrokerVerifiableStopWrapper(MatsTestBroker.create(),
                    stoppedOrderRegistry);
        }

        @Bean
        protected ConnectionFactory connectionFactory(MatsTestBroker matsTestBroker) {
            return matsTestBroker.getConnectionFactory();
        }

        @Bean
        protected MatsFactory matsFactory(ConnectionFactory connectionFactory,
                StoppedOrderRegistry stoppedOrderRegistry) {
            MatsFactoryVerifiableStopWrapper matsFactoryVerifiableStopWrapper = new MatsFactoryVerifiableStopWrapper(
                    JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("##TEST##",
                            "##VERSION##",
                            JmsMatsJmsSessionHandler_PoolingSerial.create(connectionFactory),
                            MatsSerializerJson.create()),
                    stoppedOrderRegistry);
            // Chill the concurrency a tad
            matsFactoryVerifiableStopWrapper.getFactoryConfig().setConcurrency(4);
            return matsFactoryVerifiableStopWrapper;
        }
    }

    /**
     * Simple class which contains a list of which services has been stopped, so as to be able to assert the order.
     */
    private static class StoppedOrderRegistry {
        private final static Logger log = LoggerFactory.getLogger(StoppedOrderRegistry.class);

        private List<String> _stoppedServices = new ArrayList<>(2);

        public void registerStopped(String clazzName) {
            log.info("REGISTER STOPPED: " + clazzName);
            _stoppedServices.add(clazzName);
        }

        public List<String> getListOfServicesStopped() {
            return _stoppedServices;
        }
    }

    /**
     * MatsFactoryWrapper which registers itself to the StoppedOrderRegistry upon stop, to be able to verify that it is
     * closed <i>before</i> the MatsTestBroker (the underlying MQ and its ConnectionFactory). Also no-op overrides the
     * close() method, so as the Spring default call to any bean's close() won't register us as stopped twice
     * (MatsFactory.close() is default implemented as invoking <code>stop(30_000)</code>): The thing we want to test
     * here is that @EnableMats with its MatsSpringAnnotationRegistration stops the MatsFactory before the underlying
     * MatsTestBroker is stopped.
     */
    private static class MatsFactoryVerifiableStopWrapper extends MatsFactoryWrapper {

        private StoppedOrderRegistry _stoppedOrderRegistry;

        public MatsFactoryVerifiableStopWrapper(MatsFactory matsFactory,
                StoppedOrderRegistry stoppedOrderRegistry) {
            super(matsFactory);
            _stoppedOrderRegistry = stoppedOrderRegistry;
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            boolean returnBoolean = unwrap().stop(gracefulShutdownMillis);
            if (returnBoolean) {
                _stoppedOrderRegistry.registerStopped(getClass().getSimpleName());
            }
            return returnBoolean;
        }

        @Override
        public void close() {
            // NO-OP: Overridden as a no-op, read this class' JavaDoc.
        }
    }

    /**
     * Wrapper for MatsTestBroker registers itself to the StoppedOrderRegistry upon stop, to be able to verify that it
     * is closed <i>after</i> the MatsFactory.
     */
    private static class MatsTestBrokerVerifiableStopWrapper implements MatsTestBroker {
        MatsTestBroker _matsTestBroker;
        private StoppedOrderRegistry _stoppedOrderRegistry;

        public MatsTestBrokerVerifiableStopWrapper(MatsTestBroker matsTestBroker,
                StoppedOrderRegistry stoppedOrderRegistry) {
            _matsTestBroker = matsTestBroker;
            _stoppedOrderRegistry = stoppedOrderRegistry;
        }

        /**
         * Called by spring life cycle.
         */
        public void close() {
            _matsTestBroker.close();
            // Assume that it stopped correctly
            // NOTICE: If this is run towards an external MQ, then that MatsTestBroker implementation does nothing upon
            // "close", as it cannot and should not stop the external MQ.
            _stoppedOrderRegistry.registerStopped(getClass().getSimpleName());
        }

        public ConnectionFactory getConnectionFactory() {
            return _matsTestBroker.getConnectionFactory();
        }
    }

    /**
     * Default timeout in milliseconds utilized for methods requiring a specified timeout.
     *
     * @see MatsFactory#waitForReceiving(int)
     */
    private static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    @Inject
    private MatsFactory _matsFactory;

    @Inject
    private StoppedOrderRegistry _stoppedOrderRegistry;

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

        // ----- We now have 1 endpoint registered!

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
        // ---- At this point everything should be stopped, lets verify this - AND THE STOPPING ORDER!

        // Shutting down the MatsFactory has happened when we stopped the springContext and should have removed the
        // StageProcessors from the endpoint. This means that the "isRunning()" call to the endpoint should now return
        // "false".
        Assert.assertFalse(anEndpoint.getEndpointConfig().isRunning());

        // The MatsFactory "isRunning()" will now return "false" as even though it as an endpoint within, the
        // the shutdown of the factory cause all the stageProcessors of the endpoint to be shutdown and removed
        // from the endpoint.
        Assert.assertEquals(1, _matsFactory.getEndpoints().size());
        Assert.assertFalse(_matsFactory.getFactoryConfig().isRunning());

        // Assert the order of which the Factory and the MatsTestBroker was shutdown.
        // Correct order: 1# MatsFactory - 2# MatsTestBroker.
        List<String> stoppedServices = _stoppedOrderRegistry.getListOfServicesStopped();
        Assert.assertEquals(2, stoppedServices.size());
        Assert.assertEquals(MatsFactoryVerifiableStopWrapper.class.getSimpleName(), stoppedServices.get(0));
        Assert.assertEquals(MatsTestBrokerVerifiableStopWrapper.class.getSimpleName(), stoppedServices.get(1));

        // Verify that stopping the Endpoint is idempotent.
        Assert.assertTrue(anEndpoint.stop(DEFAULT_TIMEOUT_MILLIS));

        // Verify that stopping the MatsFactory is idempotent.
        Assert.assertTrue(_matsFactory.stop(DEFAULT_TIMEOUT_MILLIS));
    }
}
