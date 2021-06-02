package io.mats3.spring.shutdownorder;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.AbstractFactoryBean;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsFactoryWrapper;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.EnableMats;
import io.mats3.spring.matsfactoryqualifier.AbstractQualificationTest;
import io.mats3.util_activemq.MatsLocalVmActiveMq;

/**
 * Base class for {@link VerifyShutdownOrderUsingFactoryBeanTest} - we do not use SpringRunner or other frameworks, but
 * instead do all Spring config ourselves. This so that the testing is as application-like as possible.
 * <p>
 * The the beans created in this configuration have been wrapped in a thin shell which replicates the calls straight
 * down to their respective counter parts. The reason for wrapping, is that we want access and the possibility to track
 * when the different beans are closed/stopped/destroyed.
 * <p>
 * Heavily inspired by {@link AbstractQualificationTest} created by Endre Stølsvik.
 */
public class AbstractFactoryBeanTestBase {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected AnnotationConfigApplicationContext _ctx;

    /**
     * "Singleton" registry which "tracks" which service have been stopped.
     */
    protected static StoppedRegistry _stoppedRegistry = new StoppedRegistry();

    protected void startSpring() {
        long nanosStart = System.nanoTime();
        log.info("Starting " + this.getClass().getSimpleName() + "!");
        log.info(" \\- new'ing up AnnotationConfigApplicationContext, giving class [" + this.getClass()
                .getSimpleName() + "] as base.");
        _ctx = new AnnotationConfigApplicationContext(DI_MainContext.class);
        double startTimeMs = (System.nanoTime() - nanosStart) / 1_000_000d;
        log.info(" \\- done, AnnotationConfigApplicationContext: [" + _ctx + "], took: [" + startTimeMs + " ms].");

        // Since this test is NOT run by the SpringRunner, the instance which this code is running in is not
        // managed by Spring. That is: JUnit have instantiated one (this), and Spring has instantiated another.
        // Therefore, manually autowire this which JUnit has instantiated.
        _ctx.getAutowireCapableBeanFactory().autowireBean(this);
    }

    protected void stopSpring() {
        // :: Close Spring.
        long nanosStart = System.nanoTime();
        log.info("Stop - closing Spring ApplicationContext.");
        _ctx.close();
        log.info("done. took: [" + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms].");
    }

    // =============================================== App configuration ==============================================

    /**
     * Context was extracted into a separate class as the approach utilized inside {@link AbstractQualificationTest} did
     * not detected the usage of {@link Import} as utilized on {@link DI_MainContext}.
     */
    @Configuration
    @Import(MatsFactoryBeanDefRegistrar.class)
    @EnableMats
    public static class DI_MainContext {
        @Bean
        protected MatsLocalVmActiveMqVerifiableStopWrapper activeMq1() {
            return new MatsLocalVmActiveMqVerifiableStopWrapper(MatsLocalVmActiveMq.createInVmActiveMq("activeMq1"),
                    _stoppedRegistry);
        }

        @Bean
        protected ConnectionFactory connectionFactory1(
                @Qualifier("activeMq1") MatsLocalVmActiveMqVerifiableStopWrapper activeMq1) {
            return activeMq1.getConnectionFactory();
        }
    }

    public static class MatsFactoryBeanDefRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                BeanDefinitionRegistry registry) {
            ConstructorArgumentValues constructorArgumentValues = new ConstructorArgumentValues();
            GenericBeanDefinition matsFactory = new GenericBeanDefinition();
            matsFactory.setPrimary(true);
            matsFactory.setBeanClass(MatsMServiceMatsFactory_FactoryBean.class);
            matsFactory.setConstructorArgumentValues(constructorArgumentValues);
            registry.registerBeanDefinition("matsFactory", matsFactory);
        }
    }

    public static class MatsMServiceMatsFactory_FactoryBean extends
            AbstractFactoryBean<MatsFactoryVerifiableStopWrapper> {

        @Inject
        private ConnectionFactory _connectionFactory;

        @Override
        public Class<?> getObjectType() {
            return MatsFactory.class;
        }

        @Override
        protected MatsFactoryVerifiableStopWrapper createInstance() {
            return new MatsFactoryVerifiableStopWrapper(JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("##TEST##",
                    "##VERSION##",
                    JmsMatsJmsSessionHandler_Pooling.create(_connectionFactory),
                    MatsSerializerJson.create()),
                    _stoppedRegistry);
        }
    }

    // ===============================================================================================================

    /**
     * Simple class which contains a {@link LinkedHashMap}, since a LinkedList maintain the elements by insertion order
     * we can be sure that index 0 happened before index 1. This gives us a reason to be reasonable certain that the
     * elements contained within the list happen in this order.
     */
    public static class StoppedRegistry {

        private Map<String, Boolean> _stoppedServices = new LinkedHashMap<>();

        public void registerStopped(String clazzName, boolean stopped) {
            _stoppedServices.put(clazzName, stopped);
        }

        public Map<String, Boolean> getStoppedServices() {
            return _stoppedServices;
        }
    }

    // ===============================================================================================================

    /**
     * Wrapper replicating the behavior of {@link JmsMatsFactory} by passing all calls through to the internal
     * {@link JmsMatsFactory} given to the constructor. The key method is
     * {@link MatsFactoryVerifiableStopWrapper#stop(int)} which stops the underlying factory and utilizes a finally
     * clause to register itself with the {@link StoppedRegistry} that this instance was indeed stopped.
     */
    public static class MatsFactoryVerifiableStopWrapper extends MatsFactoryWrapper {

        private StoppedRegistry _stoppedRegistry;

        public MatsFactoryVerifiableStopWrapper(MatsFactory matsFactory,
                StoppedRegistry stoppedRegistry) {
            super(matsFactory);
            _stoppedRegistry = stoppedRegistry;
        }

        @Override
        public boolean stop(int gracefulShutdownMillis) {
            boolean returnBoolean = false;
            try {
                returnBoolean = unwrap().stop(gracefulShutdownMillis);
            }
            finally {
                _stoppedRegistry.registerStopped(getClass().getSimpleName(), returnBoolean);
            }
            return returnBoolean;
        }
    }

    /**
     * Wrapper containing a {@link MatsLocalVmActiveMq} which contains the {@link ConnectionFactory} utilized by the
     * created {@link MatsFactory}.
     */
    public static class MatsLocalVmActiveMqVerifiableStopWrapper {
        MatsLocalVmActiveMq _matsLocalVmActiveMq;
        private StoppedRegistry _stoppedRegistry;

        public MatsLocalVmActiveMqVerifiableStopWrapper(MatsLocalVmActiveMq matsLocalVmActiveMq,
                StoppedRegistry stoppedRegistry) {
            _matsLocalVmActiveMq = matsLocalVmActiveMq;
            _stoppedRegistry = stoppedRegistry;
        }

        /**
         * Called by spring life cycle.
         */
        public void close() {
            try {
                _matsLocalVmActiveMq.close();
            }
            finally {
                _stoppedRegistry.registerStopped(getClass().getSimpleName(), _matsLocalVmActiveMq.getBrokerService()
                        .isStopped());
            }
        }

        public ConnectionFactory getConnectionFactory() {
            return _matsLocalVmActiveMq.getConnectionFactory();
        }
    }

}
