package io.mats3.spring.test.apptest2;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.spring.ConfigurationForTest;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.Sto;
import io.mats3.spring.test.MatsTestProfile;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories.TestQualifier;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.RandomString;

import no.saua.remock.RemockBootstrapper;

/**
 * As identical to {@link Test_A_UseFullApplicationConfiguration} as possible, but now using
 * <a href="https://github.com/ksaua/remock">Remock</a>. The issue when using Remock is that this library puts all beans
 * into <i>lazy initialization</i> mode to make tests (in particular those pointing to the entire application's Spring
 * Context (i.e. dependency injection) configuration) as fast as possible by only firing up beans that are actually
 * "touched" by the tests. There was an issue with some of Mats' SpringConfig elements relying on eager init - and this
 * test was made to weed out those dependencies.
 *
 * @author Endre Stølsvik 2019-06-25 23:31 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
// This overrides the configured ConnectionFactories in the app to be LocalVM testing instances.
@MatsTestProfile
// Using Remock
@BootstrapWith(RemockBootstrapper.class)
public class Test_D_UseFullApplicationConfiguration_WithRemock {
    private static final Logger log = LoggerFactory.getLogger(Test_D_UseFullApplicationConfiguration_WithRemock.class);
    private static final String TERMINATOR = "Test.TERMINATOR";

    /**
     * This is @Inject'ed here to get its @MatsMapping endpoint to register, as otherwise there is nothing that depends
     * on this bean being instantiated - and since everything is lazy-init with Remock, it will not be instantiated
     * unless something depends on it. We depend on it /indirectly/: We don't need the /bean/, but the "contents" of the
     * bean, which is the Mats endpoint. Therefore, we @Inject it here, even though we do not need the instance in the
     * test. Okay. You got this, right?
     *
     * Note: Another way to get the same effect through a different route, is to annotate the class with
     * "@DisableLazyInit(Mats_Endpoints.class)". This tells Remock to disable lazy-init for this particular class, and
     * thus it will be "booted", taking up the Mats endpoint.
     */
    @Inject
    private AppEndpoints _dependency1;

    // ===== The rest is identical to Test_UseFullApplicationConfiguration

    @ConfigurationForTest
    // This is where we import the application's main configuration class
    // 1. It is annotated with @EnableMats
    // 2. It configures two ConnectionFactories, and two MatsFactories.
    // 3. It configures classpath scanning, and thus gets the Mats endpoints configured.
    @Import(AppMain_TwoMatsFactories.class)
    public static class TestConfig {
        @Inject
        private MatsTestLatch _latch;

        /**
         * Test "Terminator" endpoint where we send the result of testing the endpoint in the application.
         */
        @MatsMapping(endpointId = TERMINATOR, matsFactoryCustomQualifierType = TestQualifier.class)
        public void testTerminatorEndpoint(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            log.info("Got result, resolving latch [" + _latch + "]!");
            _latch.resolve(state, msg);
        }
    }

    @Inject
    @TestQualifier(name = "SouthWest")
    private MatsFactory _matsFactory;

    @Inject
    private MatsTestLatch _latch;

    public void test(double number, String string) {
        SpringTestDataTO dto = new SpringTestDataTO(number, string);
        _matsFactory.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(TERMINATOR, null)
                    .request(dto);
        });
        log.info("Sent message, going into wait on latch [" + _latch + "]");
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ":single"), result.getData());
    }

    // Running the test a few times, to check that the network "stays up" between tests.
    //
    // NOTICE: JUnit actually creates a new instance of the test class for each test, i.e. each of the methods below is
    // run within a new instance. This implies that the injection of the fields in this class is done multiple times,
    // i.e. for each new test. However, the Spring Context stays the same between the tests, thus the injected fields
    // get the same values each time.

    @Test
    public void test1() {
        log.info("### test1, test class instance: @" + Integer.toHexString(System.identityHashCode(this)));
        test(4, "test_1");
    }

    @Test
    public void test2() {
        log.info("### test2, test class instance: @" + Integer.toHexString(System.identityHashCode(this)));
        test(5, "test_2");
    }

    @Test
    public void test3() {
        log.info("### test3, test class instance: @" + Integer.toHexString(System.identityHashCode(this)));
        test(4, "test_3");
    }
}
