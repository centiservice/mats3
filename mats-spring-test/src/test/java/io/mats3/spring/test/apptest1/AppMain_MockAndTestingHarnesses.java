package io.mats3.spring.test.apptest1;

import javax.jms.ConnectionFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.SpringVersion;

import io.mats3.MatsFactory;
import io.mats3.localinspect.LocalStatsMatsInterceptor;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.EnableMats;
import io.mats3.spring.jms.factories.ConnectionFactoryWithStartStopWrapper;
import io.mats3.spring.jms.factories.MatsScenario;
import io.mats3.spring.jms.factories.ScenarioConnectionFactoryProducer;
import io.mats3.spring.jms.factories.SpringJmsMatsFactoryProducer;
import io.mats3.spring.jms.factories.SpringJmsMatsFactoryWrapper;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.MatsFuturizer;

/**
 * "AppTest1" - showing different ways to employ the testing harnesses supplied by Mats in a Spring application.
 * <p />
 * A simple Spring test application using Mats and Mats' SpringConfig; You may run it as an application, i.e. it has a
 * <code>main</code> method. The point is to show how such an Mats-utilizing Spring application, with
 * <code>{@literal @Configuration}</code> classes, <code>{@literal @Service}</code> classes,
 * <code>{@literal @Bean}</code> methods and Mats SpringConfig-specified Endpoints can be tested with the Mats testing
 * tools.
 * <p>
 * PLEASE NOTE: In this "application", we set up a MatsTestBroker in-vm "LocalVM" instances to simulate a production
 * setup where there are an external Message Broker that this application wants to connect to. The reason is that it
 * should be possible to run this test-application without external resources set up. To connect to this broker, you may
 * start the application with Spring Profile "mats-regular" active, or set the system property "mats.regular" (i.e.
 * "-Dmats.regular" on the Java command line) - <b>or just run it directly, as the default scenario when nothing is
 * specified is set up to be "regular"</b>. <i>However</i>, if the Spring Profile "mats-test" is active (which you do in
 * integration tests), the JmsSpringConnectionFactoryProducer will instead of using the specified ConnectionFactory to
 * this message broker, make a new (different!) LocalVM instance and return a ConnectionFactory to those. Had this been
 * a real application, where the ConnectionFactory specified in the regular scenario of these beans pointed to the
 * actual external production broker, this would make it possible to switch between connecting to the production setup,
 * and the integration testing setup (employing LocalVM instances).
 *
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Configuration
@EnableMats
@ComponentScan
public class AppMain_MockAndTestingHarnesses {
    private static final String ENDPOINT_ID_BASE = "TestApp_TwoMf";
    public static final String ENDPOINT_ID_MAINENDPOINT = ENDPOINT_ID_BASE + ".mainService";
    public static final String ENDPOINT_ID_LEAFENDPOINT1 = ENDPOINT_ID_BASE + ".leafService1";
    public static final String ENDPOINT_ID_LEAFENDPOINT2 = ENDPOINT_ID_BASE + ".leafService2";

    private static final Logger log = LoggerFactory.getLogger(AppMain_MockAndTestingHarnesses.class);

    public static void main(String... args) {
        new AppMain_MockAndTestingHarnesses().start();
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
        SpringTestDataTO result;
        try {
            AppServiceMatsInvoker serviceMatsInvoker = ctx.getBean(AppServiceMatsInvoker.class);
            result = serviceMatsInvoker.invokeMatsWithFuturizer(10, "StartString");
        }
        catch (Throwable t) {
            String msg = "Got some Exception when running app.";
            log.error(msg, t);
            throw new RuntimeException(msg, t);
        }
        finally {
            // :: Close Spring.
            ctx.close();

        }

        log.info("Result: " + result);

        log.info("Exiting! took " + ((System.nanoTime() - nanosStart) / 1_000_000d) + " ms.");

    }

    @Bean
    protected ConnectionFactory jmsConnectionFactory() {
        log.info("Creating ConnectionFactory");
        return ScenarioConnectionFactoryProducer
                .withRegularConnectionFactory((springEnvironment) ->
                // NOTICE: This would normally be something like 'new ActiveMqConnectionFactory(<production URL>)'
                // ALSO NOTICE: This is also where you'd switch between Production and Stagings URLs for the MQ broker,
                // typically using the supplied Spring Environment to decide.
                new ConnectionFactoryWithStartStopWrapper() {
                    private final MatsTestBroker _amq = MatsTestBroker.createUniqueInVmActiveMq();

                    @Override
                    public ConnectionFactory start(String beanName) {
                        return _amq.getConnectionFactory();
                    }

                    @Override
                    public void stop() {
                        _amq.close();
                    }
                })
                // Choose Regular MatsScenario if none presented.
                // NOTE: I WOULD ADVISE AGAINST THIS IN REAL SETTINGS!
                // ... - I believe production should be a specific environment selected with some system property.
                .withDefaultScenario(MatsScenario.REGULAR)
                .createConnectionFactory();
    }

    @Bean
    public MatsSerializer matsSerializer() {
        return MatsSerializerJson.create();
    }

    @Bean
    protected MatsFactory matsFactory(ConnectionFactory connectionFactory,
            MatsSerializer matsSerializer) {
        log.info("Creating MatsFactory");
        SpringJmsMatsFactoryWrapper matsFactory = SpringJmsMatsFactoryProducer.createJmsTxOnlyMatsFactory(
                AppMain_MockAndTestingHarnesses.class.getSimpleName(), "#testing#", matsSerializer, connectionFactory);
        // Add the LocalInspect just to exercise it here in the tests.
        LocalStatsMatsInterceptor.install(matsFactory);
        return matsFactory;
    }

    @Bean
    public MatsFuturizer matsFuturizer(MatsFactory matsFactory) {
        return MatsFuturizer.createMatsFuturizer(matsFactory, "TestFuturizer");
    }
}