package io.mats3.spring.test.apptest2;

import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import io.mats3.MatsFactory;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.ConfigurationForTest;
import io.mats3.spring.Dto;
import io.mats3.spring.EnableMats;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.Sto;
import io.mats3.spring.test.TestSpringMatsFactoryProvider;
import io.mats3.spring.test.apptest2.AppMain_TwoMatsFactories.TestQualifier;
import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.spring.test.SpringTestStateTO;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.util.RandomString;

/**
 * A test that points to only a specific @Configuration bean of an application, thus not taking up the entire
 * application - we have to provide the infrastructure (i.e. MatsFactories) in the test.
 * 
 * @author Endre Stølsvik 2019-06-06 21:53 - http://stolsvik.com/, endre@stolsvik.com
 */
@RunWith(SpringRunner.class)
public class Test_E_PieceTogetherExplicitComponents {
    private static final String TERMINATOR = "Test_UseOnlyMatsConfigurationFromApplication.TERMINATOR";

    @ConfigurationForTest
    // This is where we import the application's endpoint configurations:
    @Import(AppEndpoints.class)
    // Enable SpringConfig of Mats, as nobody else is doing it now (done by AppMain normally):
    @EnableMats
    public static class TestConfig {
        /**
         * Create the "matsFactoryX" needed by endpoints defined in class Mats_Endpoint.
         */
        @Bean
        @TestQualifier(name = "SouthWest")
        @Qualifier("matsFactoryX")
        protected MatsFactory matsFactory1() {
            return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(MatsSerializerJson.create());
        }

        /**
         * Create the "matsFactoryY" needed by endpoints defined in class Mats_Endpoint.
         */
        @Bean
        @Qualifier("matsFactoryY")
        protected MatsFactory matsFactory2() {
            return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(MatsSerializerJson.create());
        }

        @Bean
        public MatsTestLatch latch() {
            return new MatsTestLatch();
        }

        @Inject
        private MatsTestLatch _latch;

        @Bean
        public AtomicInteger getAtomicInteger() {
            return new AtomicInteger();
        }

        /**
         * Test "Terminator" endpoint where we send the result of testing the endpoint in the application.
         * <p />
         * <b>Notice how we register this on both the MatsFactories.</b>
         */
        @MatsMapping(endpointId = TERMINATOR, matsFactoryQualifierValue = "matsFactoryX")
        @MatsMapping(endpointId = TERMINATOR, matsFactoryQualifierValue = "matsFactoryY")
        public void testTerminatorEndpoint(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
            _latch.resolve(state, msg);
        }
    }

    @Inject
    @Qualifier("matsFactoryX")
    private MatsFactory _matsFactoryX;

    @Inject
    @Qualifier("matsFactoryY")
    private MatsFactory _matsFactoryY;

    @Inject
    private MatsTestLatch _latch;

    @Test
    public void testOnMatsFactoryX() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        _matsFactoryX.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(TERMINATOR, null)
                    .request(dto);
        });
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 2, dto.string + ":single"), result.getData());
    }

    @Test
    public void testOnMatsFactoryY() {
        SpringTestDataTO dto = new SpringTestDataTO(4, "test");
        _matsFactoryY.getDefaultInitiator().initiateUnchecked(msg -> {
            msg.traceId(RandomString.randomCorrelationId())
                    .from("TestInitiate")
                    .to(AppMain_TwoMatsFactories.ENDPOINT_ID + ".single")
                    .replyTo(TERMINATOR, null)
                    .request(dto);
        });
        Result<SpringTestStateTO, SpringTestDataTO> result = _latch.waitForResult();
        Assert.assertEquals(new SpringTestDataTO(dto.number * 3, dto.string + ":single_on_class"), result.getData());
    }

}
