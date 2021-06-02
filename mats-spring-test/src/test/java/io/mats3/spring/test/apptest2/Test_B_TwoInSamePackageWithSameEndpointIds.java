package io.mats3.spring.test.apptest2;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.spring.ComponentScanExcludingConfigurationForTest;
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

/**
 * This is a literal copy of {@link Test_A_UseFullApplicationConfiguration}, which employs the same TERMINATOR
 * EndpointId, just to point out that the issue where @ComponentScan picks up the inner static @Configuration classes of
 * tests, is <b>not</b> present when employing the two special variants
 * {@link ComponentScanExcludingConfigurationForTest @ComponentScanExcludingConfigurationForTest} and
 * {@link ConfigurationForTest @ConfigurationForTest} instead of the standard ones. (Mats will wisely refuse to add two
 * endpoints having the same endpointIds to a single MatsFactory).
 *
 * @author Endre Stølsvik 2019-06-06 21:53 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestProfile
public class Test_B_TwoInSamePackageWithSameEndpointIds {
    private static final Logger log = LoggerFactory.getLogger(Test_B_TwoInSamePackageWithSameEndpointIds.class);
    static final String TERMINATOR = Test_A_UseFullApplicationConfiguration.TERMINATOR;

    @ConfigurationForTest
    @Import(AppMain_TwoMatsFactories.class)
    public static class TestConfig {
        @Inject
        private MatsTestLatch _latch;

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
