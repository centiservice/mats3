/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.spring.matsfactoryqualifier;

import javax.inject.Inject;
import javax.jms.ConnectionFactory;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.mats3.MatsFactory;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.test.MatsTestLatch;

/**
 * Test where one MatsFactory is made {@link Primary @Primary}, and the other is qualified with standard
 * {@link Qualifier @Qualifier}.
 *
 * @author Endre Stølsvik 2019-05-26 00:43 - http://stolsvik.com/, endre@stolsvik.com
 */
public class OkPrimaryAndQualifierTest extends AbstractQualificationTest {
    private final static String ENDPOINT_ID = "QualifierTest";

    @Inject
    private MatsFactory _matsFactory_Primary;

    @Inject
    @Qualifier("matsFactoryY")
    private MatsFactory _matsFactoryY;

    @Inject
    private MatsTestLatch _latch;

    @Bean
    @Primary
    protected MatsFactory matsFactory1(@Qualifier("connectionFactory1") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    @Bean
    @Qualifier("matsFactoryY")
    protected MatsFactory matsFactory2(@Qualifier("connectionFactory2") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    /**
     * Test "Single" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".single")
    protected SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test "Terminator" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    protected void springMatsTerminatorEndpoint_Primary(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    /**
     * Test "Terminator" endpoint to other factory
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @Qualifier("matsFactoryY")
    protected void springMatsTerminatorEndpoint_MatsFactoryY(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    @Test
    public void test() {
        startSpring();
        Assert.assertEquals(2, _matsFactory_Primary.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory_Primary.getEndpoint(ENDPOINT_ID + ".single").isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactory_Primary.getEndpoint(ENDPOINT_ID + ".terminator")
                .isPresent());

        Assert.assertEquals(1, _matsFactoryY.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactoryY.getEndpoint(ENDPOINT_ID + ".terminator").isPresent());
        try {
            doStandardTest(_matsFactory_Primary, ENDPOINT_ID);
        }
        finally {
            stopSpring();
        }
    }

}
