package io.mats3.spring.test.apptest2;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.ConnectionFactory;

import io.mats3.localinspect.LocalStatsMatsInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.SpringVersion;

import io.mats3.MatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.ComponentScanExcludingConfigurationForTest;
import io.mats3.spring.ConfigurationForTest;
import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.factories.ConnectionFactoryWithStartStopWrapper;
import io.mats3.spring.jms.factories.MatsScenario;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryProducer;
import io.mats3.spring.jms.factories.SpringJmsMatsFactoryProducer;
import io.mats3.spring.jms.factories.SpringJmsMatsFactoryWrapper;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.broker.MatsTestBroker;

/**
 * "AppTest2" - showing how multiple MatsFactories work in a Spring environment, along with some testing - in particular
 * utilizing the special <code>{@link ComponentScanExcludingConfigurationForTest}</code> and
 * <code>{@link ConfigurationForTest}</code>.
 * <p />
 * A simple Spring test application using Mats and Mats' SpringConfig; You may run it as an application, i.e. it has a
 * <code>main</code> method. The point is to show how such an Mats-utilizing Spring application, with
 * <code>{@literal @Configuration}</code> classes, <code>{@literal @Service}</code> classes,
 * <code>{@literal @Bean}</code> methods and Mats SpringConfig-specified Endpoints can utilize two MatsFactories, each
 * using a different ActiveMQ. It also employs some different testing methods.
 * <p>
 * PLEASE NOTE: In this "application", we set up two MatsTestBroker in-vm "LocalVM" instances to simulate a production
 * setup where there are two external Message Brokers that this application wants to connect to. The reason is that it
 * should be possible to run this test-application without external resources set up. To connect to these brokers, you
 * may start the application with Spring Profile "mats-regular" active, or set the system property "mats.regular" (i.e.
 * "-Dmats.regular" on the Java command line) - <b>or just run it directly, as the default scenario when nothing is
 * specified is set up to be "regular".</b> <i>However</i>, if the Spring Profile "mats-test" is active (which you do in
 * integration tests), the JmsSpringConnectionFactoryProducer will instead of using the specified ConnectionFactory to
 * these two message brokers, make new (different!) LocalVM instances and return a ConnectionFactory to those. Had this
 * been a real application, where the ConnectionFactory specified in the regular scenario of those beans pointed to the
 * actual external production brokers, this would make it possible to switch between connecting to the production setup,
 * and the integration testing setup (employing LocalVM instances).
 *
 * @author Endre Stølsvik 2019-05-17 21:42 - http://stolsvik.com/, endre@stolsvik.com
 */
@Configuration
@EnableMats
@ComponentScanExcludingConfigurationForTest
public class AppMain_TwoMatsFactories {
    public static final String ENDPOINT_ID = "TestApp_TwoMf";

    private static final Logger log = LoggerFactory.getLogger(AppMain_TwoMatsFactories.class);

    public static void main(String... args) {
        new AppMain_TwoMatsFactories().start();
    }

    private void start() {
        long nanosStart = System.nanoTime();
        log.info("Starting " + this.getClass().getSimpleName() + "! Spring Version: " + SpringVersion.getVersion());
        log.info(" \\- new'ing up AnnotationConfigApplicationContext, giving class [" + this.getClass()
                .getSimpleName() + "] as base.");
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(this.getClass());
        log.info(" \\- done, AnnotationConfigApplicationContext: [" + ctx + "].");

        // ----- Spring is running.

        log.info("Starting application.");
        try {
            AppService appAppService = ctx.getBean(AppService.class);
            appAppService.run();
        }
        catch (Throwable t) {
            String msg = "Got some Exception when running app.";
            log.error(msg, t);
            throw new RuntimeException(msg, t);
        }
        finally {
            // :: Close Spring.
            ctx.close();

            log.info("Exiting! took " + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms.");
        }
    }

    @Bean
    public MatsTestLatch matsTestLatch() {
        return new MatsTestLatch();
    }

    @Bean
    public MatsSerializer<String> matsSerializer() {
        return MatsSerializerJson.create();
    }

    @Bean
    public AtomicInteger atomicInteger() {
        return new AtomicInteger();
    }

    @Bean
    @Qualifier("connectionFactoryA")
    protected ConnectionFactory jmsConnectionFactory1() {
        log.info("Creating ConnectionFactory with @Qualifier(\"connectionFactoryA\")");
        return ScenarioConnectionFactoryProducer
                /*
                 * NOTICE: This would normally be something like 'new ActiveMqConnectionFactory(<production URL 1>)'
                 * ALSO NOTICE: This is also where you'd switch between Production and Stagings URLs for MQ broker 1,
                 * typically using the supplied Spring Environment, or System Properties, or whatever, to decide.
                 */
                .withRegularConnectionFactory((springEnvironment) -> new ConnectionFactoryWithStartStopWrapper() {
                    private final MatsTestBroker _matsTestBroker = MatsTestBroker.create();

                    @Override
                    public ConnectionFactory start(String beanName) {
                        return _matsTestBroker.getConnectionFactory();
                    }

                    @Override
                    public void stop() {
                        _matsTestBroker.close();
                    }
                })

                // Choose Regular MatsScenario if none presented.
                // NOTE: I WOULD ADVISE AGAINST THIS IN REAL SETTINGS!
                // ... - I believe production should be a specific environment selected with some system property.
                .withDefaultScenario(MatsScenario.REGULAR)
                .build();
    }

    @Bean
    @Qualifier("connectionFactoryB")
    protected ConnectionFactory jmsConnectionFactory2() {
        log.info("Creating ConnectionFactory with @Qualifier(\"connectionFactoryB\")");
        return ScenarioConnectionFactoryProducer
                /*
                 * NOTICE: This would normally be something like 'new ActiveMqConnectionFactory(<production URL 2>)'
                 * ALSO NOTICE: This is also where you'd switch between Production and Stagings URLs for MQ broker 2,
                 * typically using the supplied Spring Environment, or System Properties, or whatever, to decide.
                 */
                .withRegularConnectionFactory((springEnvironment) -> new ConnectionFactoryWithStartStopWrapper() {
                    // Specifically creating a MatsTestBroker that uses a unique in-vm ActiveMQ, NOT going external.
                    // If we didn't set this, the default would employ MatsTestBroker.create(), which would go to
                    // the external broker if the special properties to MatsTestBroker are set. If those properties
                    // are not set, MatsTestBroker.create() and MatsTestBroker.createUniqueInVmActiveMq() are identical.
                    private final MatsTestBroker _matsTestBroker = MatsTestBroker.createUniqueInVmActiveMq();

                    @Override
                    public ConnectionFactory start(String beanName) {
                        return _matsTestBroker.getConnectionFactory();
                    }

                    @Override
                    public void stop() {
                        _matsTestBroker.close();
                    }
                })

                /*
                 * NOTICE: withLocalVmConnectionFactory(..) CONFIGURATION IS NOT NEEDED IN THE VAST MAJORITY OF CASES!!
                 * We're using two different MatsFactories in this testing scenario. To ensure that we can run the
                 * entire test suite with the "magic properties" set that directs the MatsTestBroker to use an external
                 * broker, we need to ensure that both of them DOES NOT go to the same external broker - as otherwise it
                 * would not be two different brokers! (both ConnectionFactories would connect to the same external
                 * broker). Therefore, we in this second ConnectionFactory explicitly create a unique, in-vm broker.
                 */
                .withLocalVmConnectionFactory((springEnvironment -> new ConnectionFactoryWithStartStopWrapper() {
                    // Specifically creating a MatsTestBroker that uses a unique in-vm ActiveMQ, NOT going external.
                    // If we didn't set this, the default would employ MatsTestBroker.create(), which would go to
                    // the external broker if the special properties to MatsTestBroker are set. If those properties
                    // are not set, MatsTestBroker.create() and MatsTestBroker.createUniqueInVmActiveMq() are identical.
                    private final MatsTestBroker _matsTestBroker = MatsTestBroker.createUniqueInVmActiveMq();

                    @Override
                    public ConnectionFactory start(String beanName) {
                        return _matsTestBroker.getConnectionFactory();
                    }

                    @Override
                    public void stop() {
                        _matsTestBroker.close();
                    }
                }))

                // Choose Regular MatsScenario if none presented.
                // NOTE: I WOULD ADVISE AGAINST THIS IN REAL SETTINGS!
                // ... - I believe production should be a specific environment selected with some system property.
                .withDefaultScenario(MatsScenario.REGULAR)
                .build();
    }

    @Bean
    @TestCustomQualifier(region = "SouthWest")
    @Qualifier("matsFactoryX")
    protected MatsFactory matsFactory1(@Qualifier("connectionFactoryA") ConnectionFactory connectionFactory,
            MatsSerializer<String> matsSerializer) {
        log.info("Creating MatsFactory1");
        SpringJmsMatsFactoryWrapper matsFactory = SpringJmsMatsFactoryProducer.createJmsTxOnlyMatsFactory(
                AppMain_TwoMatsFactories.class.getSimpleName(), "#testing#", matsSerializer, connectionFactory);
        // Configure for test-scenario: Enough with concurrency of 2
        matsFactory.getFactoryConfig().setConcurrency(2);
        // Add the LocalInspect just to exercise it here in the tests.
        LocalStatsMatsInterceptor.install(matsFactory);
        return matsFactory;
    }

    @Bean
    @Qualifier("matsFactoryY")
    protected MatsFactory matsFactory2(@Qualifier("connectionFactoryB") ConnectionFactory connectionFactory,
            MatsSerializer<String> matsSerializer) {
        log.info("Creating MatsFactory2");
        SpringJmsMatsFactoryWrapper matsFactory = SpringJmsMatsFactoryProducer.createJmsTxOnlyMatsFactory(
                AppMain_TwoMatsFactories.class.getSimpleName(), "#testing#", matsSerializer, connectionFactory);
        // Configure for test-scenario: Enough with concurrency of 2
        matsFactory.getFactoryConfig().setConcurrency(2);
        // Add the LocalInspect just to exercise it here in the tests.
        LocalStatsMatsInterceptor.install(matsFactory);
        return matsFactory;
    }

    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface TestCustomQualifier {
        String region() default "";
    }
}