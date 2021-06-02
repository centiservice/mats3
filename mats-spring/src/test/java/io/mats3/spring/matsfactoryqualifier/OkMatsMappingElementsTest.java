package io.mats3.spring.matsfactoryqualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

import io.mats3.MatsFactory;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.test.MatsTestLatch;

/**
 * Test qualification by means of the three {@link MatsMapping} element qualifications.
 *
 * @author Endre Stølsvik 2019-05-25 00:34 - http://stolsvik.com/, endre@stolsvik.com
 */
public class OkMatsMappingElementsTest extends AbstractQualificationTest {
    private final static String ENDPOINT_ID_Y = "QualifierTest_Y";
    private final static String ENDPOINT_ID_X = "QualifierTest_X";

    @Inject
    @CustomMatsFactoryQualifierWithoutElements
    private MatsFactory _matsFactoryX;

    @Inject
    @Qualifier("matsFactoryY")
    private MatsFactory _matsFactoryY;

    @Inject
    private MatsTestLatch _latch;

    @Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    public @interface CustomMatsFactoryQualifierWithoutElements {}

    @Bean
    @CustomMatsFactoryQualifierWithoutElements
    protected MatsFactory matsFactory1(@Qualifier("connectionFactory1") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    @Bean
    @Qualifier("matsFactoryY")
    protected MatsFactory matsFactory2(@Qualifier("connectionFactory2") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    /**
     * Test 2x "Single" endpoints
     */
    @MatsMapping(endpointId = ENDPOINT_ID_Y + ".single", matsFactoryCustomQualifierType = CustomMatsFactoryQualifierWithoutElements.class)
    @MatsMapping(endpointId = ENDPOINT_ID_X + ".single", matsFactoryQualifierValue = "matsFactoryY")
    protected SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test 2x "Terminator" endpoints
     */
    @MatsMapping(endpointId = ENDPOINT_ID_Y + ".terminator", matsFactoryBeanName = "matsFactory1")
    @MatsMapping(endpointId = ENDPOINT_ID_X + ".terminator", matsFactoryBeanName = "matsFactory2")
    protected void springMatsTerminatorEndpoint_MatsFactoryX(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    @Test
    public void test() {
        startSpring();
        Assert.assertEquals(2, _matsFactoryX.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactoryX.getEndpoint(ENDPOINT_ID_Y + ".single").isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactoryX.getEndpoint(ENDPOINT_ID_Y + ".terminator").isPresent());

        Assert.assertEquals(2, _matsFactoryY.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactoryY.getEndpoint(ENDPOINT_ID_X + ".single").isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactoryY.getEndpoint(ENDPOINT_ID_X + ".terminator").isPresent());
        try {
            doStandardTest(_matsFactoryX, ENDPOINT_ID_Y);
            doStandardTest(_matsFactoryY, ENDPOINT_ID_X);
        }
        finally {
            stopSpring();
        }
    }

}
