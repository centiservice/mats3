package io.mats3.spring.test.apptest2;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.spring.test.MatsTestProfile;
import io.mats3.spring.test.apptest2.AppEndpoints.SingleEndpointUsingMatsClassMapping;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.RandomString;

/**
 * Two tests, each sending messages to Endpoints having the same EndpointId, but residing on different MatsFactories -
 * one configured by @MatsMapping-annotated method on the {@link AppEndpoints} {@literal @Configuration} class, while
 * the other is configured by the inner static class {@link SingleEndpointUsingMatsClassMapping}.
 * <p />
 * <b>Notice that the processors on the two endpoints are different (even though they have the exact same EndpointId):
 * One is multiplying the incoming number by 2, while the other by 3 - and also the strings are different.</b>
 *
 * @author Endre Stølsvik 2019-06-06 21:53 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
@MatsTestProfile
@ContextConfiguration(classes = AppMain_TwoMatsFactories.class)
@DirtiesContext
public class Test_C_UseFullApplicationConfiguration_TestingBothMatsFactories {
    private static final Logger log = LoggerFactory.getLogger(
            Test_C_UseFullApplicationConfiguration_TestingBothMatsFactories.class);

    @Inject
    @Qualifier("matsFactoryX") // This is the first MatsFactory
    private MatsFactory _matsFactoryX;

    @Inject
    @Qualifier("matsFactoryY") // This is the second MatsFactory
    private MatsFactory _matsFactoryY;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void testOnMatsFactoryX() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        SpringTestStateTO sto = new SpringTestStateTO(1, "elg");
        _matsFactoryX.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(AppMain_TwoMatsFactories.ENDPOINT_ID + ".terminator", sto)
                    .request(dto);
        });
        log.info("Sent message, going into wait on latch [" + _latch + "]");
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ":single"),
                result.getData());
    }

    @Test
    public void testOnMatsFactoryY() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        SpringTestStateTO sto = new SpringTestStateTO(1, "elg");
        _matsFactoryY.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(AppMain_TwoMatsFactories.ENDPOINT_ID + ".terminator", sto)
                    .request(dto);
        });
        log.info("Sent message, going into wait on latch [" + _latch + "]");
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new SpringTestDataTO(dto.number * 3, dto.string + ":single_on_class"),
                result.getData());
    }
}
