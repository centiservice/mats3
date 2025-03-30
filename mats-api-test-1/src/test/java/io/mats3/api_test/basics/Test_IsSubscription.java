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

package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.junit.Rule_Mats;

public class Test_IsSubscription {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @BeforeClass
    public static void ensureNotStart() {
        // Hold endpoints: We won't actually ever start them - this is just to not make a mess out of shutdown.
        MATS.getMatsFactory().holdEndpointsUntilFactoryIsStarted();
    }

    @Test
    public void testNonSubscription() {
        MatsEndpoint<Void, StateTO> nonSubscription = MATS.getMatsFactory().terminator(
                "testNonSubscription", StateTO.class, DataTO.class, (context, sto, dto) -> {
                });

        Assert.assertFalse(nonSubscription.getEndpointConfig().isSubscription());
    }

    @Test
    public void testSubscription() {
        MatsEndpoint<Void, StateTO> subscription = MATS.getMatsFactory().subscriptionTerminator(
                "testSubscription", StateTO.class, DataTO.class, (context, sto, dto) -> {
                });

        Assert.assertTrue(subscription.getEndpointConfig().isSubscription());
    }
}
