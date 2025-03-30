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

package io.mats3.api_test.failure;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.impl.jms.JmsMatsFactory.CannotInstantiateClassException;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.MatsTestHelp;

/**
 * Tests that the early catching of non-instantiatable DTOs and State classes works.
 *
 * @author Endre Stølsvik 2019-10-27 22:02 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_StateAndDtoInstantiationFailure  {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    public static class MissingNoArgsConstructor {
        public MissingNoArgsConstructor(String test) {
            /* no-op */
        }
    }

    public static class ExceptionInConstructor {
        ExceptionInConstructor() {
            throw new RuntimeException("Throw from Constructor!");
        }
    }

    @Before
    public void cleanMatsFactory() {
        MATS.cleanMatsFactories();
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void missingNoArgs_Endpoint_Reply() {
        MATS.getMatsFactory().single(ENDPOINT, MissingNoArgsConstructor.class, String.class, (processContext,
                incomingDto) -> null);
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void exceptionInConstructor_Endpoint_Reply() {
        MATS.getMatsFactory().single(ENDPOINT, ExceptionInConstructor.class, String.class, (processContext,
                incomingDto) -> null);
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void missingNoArgs_Stage_Incoming() {
        MATS.getMatsFactory().single(ENDPOINT, String.class, MissingNoArgsConstructor.class, (processContext,
                incomingDto) -> null);
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void exceptionInConstructor_Stage_Incoming() {
        MATS.getMatsFactory().single(ENDPOINT, String.class, ExceptionInConstructor.class, (processContext,
                incomingDto) -> null);
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void missingNoArgs_Endpoint_State() {
        MATS.getMatsFactory().staged(ENDPOINT, String.class, MissingNoArgsConstructor.class);
    }

    @Test(expected = CannotInstantiateClassException.class)
    public void exceptionInConstructor_Stage_State() {
        MATS.getMatsFactory().staged(ENDPOINT, String.class, ExceptionInConstructor.class);
    }
}
