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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.inject.Inject;
import jakarta.jms.ConnectionFactory;

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
 * Test where one MatsFactory is annotated with custom qualifier annotation (an annotation annotated with @Qualifier),
 * and the @MatsMapping refers to that, and another is annotated with standard @Qualifier.
 *
 * @author Endre Stølsvik 2019-05-26 00:35 - http://stolsvik.com/, endre@stolsvik.com
 */
public class OkCustomQualifierWithoutElementTest extends AbstractQualificationTest {
    private final static String ENDPOINT_ID = "QualifierTest";

    @Inject
    @CustomMatsFactoryQualifierWithoutElements
    private MatsFactory _matsFactory_CustomQualifier;

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
     * Test "Single" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".single")
    @CustomMatsFactoryQualifierWithoutElements
    protected SpringTestDataTO springMatsSingleEndpoint(@Dto SpringTestDataTO msg) {
        return new SpringTestDataTO(msg.number * 2, msg.string + ":single");
    }

    /**
     * Test "Terminator" endpoint.
     */
    @MatsMapping(endpointId = ENDPOINT_ID + ".terminator")
    @CustomMatsFactoryQualifierWithoutElements
    protected void springMatsTerminatorEndpoint_MatsFactoryX(@Dto SpringTestDataTO msg, @Sto SpringTestStateTO state) {
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
        Assert.assertEquals(2, _matsFactory_CustomQualifier.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactory_CustomQualifier.getEndpoint(ENDPOINT_ID + ".single")
                .isPresent());
        Assert.assertTrue("Missing endpoint", _matsFactory_CustomQualifier.getEndpoint(ENDPOINT_ID + ".terminator")
                .isPresent());

        Assert.assertEquals(1, _matsFactoryY.getEndpoints().size());
        Assert.assertTrue("Missing endpoint", _matsFactoryY.getEndpoint(ENDPOINT_ID + ".terminator").isPresent());
        try {
            doStandardTest(_matsFactory_CustomQualifier, ENDPOINT_ID);
        }
        finally {
            stopSpring();
        }
    }

}
