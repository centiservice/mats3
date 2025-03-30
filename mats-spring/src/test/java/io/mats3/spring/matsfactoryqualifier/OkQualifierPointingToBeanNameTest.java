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

import io.mats3.MatsFactory;
import io.mats3.spring.Dto;
import io.mats3.spring.MatsMapping;
import io.mats3.spring.SpringTestDataTO;
import io.mats3.spring.SpringTestStateTO;
import io.mats3.spring.Sto;
import io.mats3.test.MatsTestLatch;

/**
 * Test basic {@link Qualifier @Qualifier} qualification, but where there is no @Qualifier annotation on the
 * MatsFactory, but instead the qualification points to the MatsFactory bean names.
 *
 * @author Endre Stølsvik 2019-05-25 23:28 - http://stolsvik.com/, endre@stolsvik.com
 */
public class OkQualifierPointingToBeanNameTest extends AbstractQualificationTest {
    private final static String ENDPOINT_ID = "OkQualifierPointingToBeanNameTest";

    @Inject
    @Qualifier("matsFactory1")
    private MatsFactory _matsFactory1;

    @Inject
    @Qualifier("matsFactory2")
    private MatsFactory _matsFactory2;

    @Inject
    private MatsTestLatch _latch;

    @Bean
    protected MatsFactory matsFactory1(@Qualifier("connectionFactory1") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    @Bean
    protected MatsFactory matsFactory2(@Qualifier("connectionFactory2") ConnectionFactory connectionFactory) {
        return getMatsFactory(connectionFactory);
    }

    /**
     * Test "Single" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".single")
    @Qualifier("matsFactory1")
    protected SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test "Terminator" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @Qualifier("matsFactory1")
    protected void springMatsTerminatorEndpoint_MatsFactory1(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    /**
     * Test "Terminator" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @Qualifier("matsFactory2")
    protected void springMatsTerminatorEndpoint_MatsFactory2(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
        _latch.resolve(state, msg);
    }

    @Test
    public void test() {
        startSpring();
        Assert.assertEquals(2, _matsFactory1.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory1.getEndpoint(ENDPOINT_ID + ".single").isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactory1.getEndpoint(ENDPOINT_ID + ".terminator").isPresent());

        Assert.assertEquals(1, _matsFactory2.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory2.getEndpoint(ENDPOINT_ID + ".terminator").isPresent());
        try {
            doStandardTest(_matsFactory1, ENDPOINT_ID);
        }
        finally {
            stopSpring();
        }
    }

}
