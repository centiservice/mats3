package io.mats3.spring.test.apptest1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.ComponentScanExcludingConfigurationForTest;
import io.mats3.spring.ConfigurationForTest;
import io.mats3.spring.EnableMats;
import io.mats3.spring.test.TestSpringMatsFactoryProvider;
import io.mats3.spring.test.apptest1.Test_B_PieceTogetherNeededAppComponents_Manual.TestConfiguration;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * This test "pieces together" what we need from the application to run the test, and then create the necessary Mats
 * infrastructure components "manually", employing {@link TestSpringMatsFactoryProvider} to get a handy
 * {@link MatsFactory} backed by an in-vm ActiveMQ. See {@link TestConfiguration}.
 * <p />
 * <b>Worth noting:</b> Spring's testing tools have a feature where any inner class annotated
 * with @{@link Configuration} will be automatically picked up. However, this works really badly together
 * with @{@link ComponentScan} which is employed by the application {@link AppMain_MockAndTestingHarnesses}: Since we
 * have the tests on the classpath now, any test that (indirectly) includes the ComponentScan <i>would also pick up this
 * internal Configuration class</i>! (this is relevant for {@link Test_A_UseFullApplicationConfiguration}) I perceive
 * this as a pretty annoying flaw, but that is how it is. That is why we here use the @{@link ContextConfiguration} and
 * <i>explicitly</i> point it to the inner Configuration class, which then is NOT annotated with Configuration.
 * <p />
 * An alternative is employing the nonstandard {@link ConfigurationForTest} and
 * {@link ComponentScanExcludingConfigurationForTest} combination instead of the Spring standard - this is done in the
 * "apptest2" test package, {@link AppMain_TwoMatsFactories}. If you find this nicer, then go right ahead. Also, if
 * <a href="https://github.com/spring-projects/spring-framework/issues/26144">this Spring issue</a> has been fixed, you
 * should be able to employ this instead!
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = { TestConfiguration.class })
@DirtiesContext
public class Test_B_PieceTogetherNeededAppComponents_Manual {
    /**
     * Enables Mats' SpringConfig using {@link EnableMats}, imports the needed application pieces, and sets up the Mats
     * infrastructure needed for the application components, and for running the tests.
     *
     * Note: Cannot be annotated with <code>{@literal @Configuration}</code>, as the @{@link ComponentScan} in the app
     * then would pick it up (when running the {@link Test_A_UseFullApplicationConfiguration}!)
     */
    @EnableMats
    @Import({ AppEndpoint_MainService.class, AppEndpoint_LeafServices.class, AppServiceCalculator.class })
    protected static class TestConfiguration {
        @Bean
        protected MatsFactory testMatsFactory() {
            return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(MatsSerializerJson.create());
        }

        @Bean
        protected MatsFuturizer testMatsFuturizer(MatsFactory matsFactory) {
            return MatsFuturizer.createMatsFuturizer(matsFactory, "MatsSpringTest");
        }
    }

    @Inject
    private MatsFuturizer _matsFuturizer;

    private void matsEndpointTest(double number, String string) {
        SpringTestDataTO dto = new SpringTestDataTO(number, string);
        CompletableFuture<Reply<SpringTestDataTO>> future = _matsFuturizer.futurizeNonessential(MatsTestHelp.traceId(),
                MatsTestHelp.from("testX"),
                AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT,
                SpringTestDataTO.class, dto);

        SpringTestDataTO reply;
        try {
            reply = future.get(10, TimeUnit.SECONDS).getReply();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Didn't get reply", e);
        }

        Assert.assertEquals(Test_A_UseFullApplicationConfiguration.expectedNumber(dto.number), reply.number, 0d);
        Assert.assertEquals(Test_A_UseFullApplicationConfiguration.expectedString(dto.string), reply.string);
    }

    // Running the test a few times, to check that the network "stays up" between tests.
    //
    // NOTICE: JUnit actually creates a new instance of the test class for each test, i.e. each of the methods below is
    // run within a new instance. This implies that the injection of the fields in this class is done multiple times,
    // i.e. for each new test. However, the Spring Context stays the same between the tests, thus the injected fields
    // get the same values each time.

    @Test
    public void matsEndpointTest1() {
        matsEndpointTest(10, "StartString");
    }

    @Test
    public void matsEndpointTest2() {
        matsEndpointTest(20, "BeginnerString");
    }

    @Test
    public void matsEndpointTest3() {
        matsEndpointTest(42, "TheAnswer");
    }
}
