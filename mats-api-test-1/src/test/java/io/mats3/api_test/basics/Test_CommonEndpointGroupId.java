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
import org.junit.Test;

import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestFactory;

/**
 * Tests the functionality whereby an Endpoint is registered with a leading ".", it should use the MatsFactory's
 * "CommonEndpointGroupId" as the EndpointGroupId, i.e. the first part.
 */
public class Test_CommonEndpointGroupId {

    @Test
    public void whenNotOverridden() {
        try (MatsTestFactory matsFactory = MatsTestFactory.create()) {
            String appName = matsFactory.getFactoryConfig().getAppName();
            String commonEndpointGroupId = matsFactory.getFactoryConfig().getCommonEndpointGroupId();
            Assert.assertEquals("When not overridden, the CommonEndpointGroupId should be the same as AppName",
                    appName, commonEndpointGroupId);

            var single = matsFactory.single(".EndpointA", String.class, String.class, (ctx, msg) -> msg);
            Assert.assertTrue("When not overridden, the CommonEndpointGroupId should be the same as AppName",
                    single.getEndpointConfig().getEndpointId().startsWith(appName + "."));

            var staged = matsFactory.staged(".EndpointB", String.class, StateTO.class);
            Assert.assertTrue("When not overridden, the CommonEndpointGroupId should be the same as AppName",
                    staged.getEndpointConfig().getEndpointId().startsWith(appName + "."));

            var terminator = matsFactory.terminator(".EndpointC", String.class, String.class, (ctx, state, msg) -> {
            });
            Assert.assertTrue("When not overridden, the CommonEndpointGroupId should be the same as AppName",
                    terminator.getEndpointConfig().getEndpointId().startsWith(appName + "."));

            var subTerminator = matsFactory.subscriptionTerminator(".EndpointD", String.class, String.class,
                    (ctx, state, msg) -> {
                    });
            Assert.assertTrue("When not overridden, the CommonEndpointGroupId should be the same as AppName",
                    subTerminator.getEndpointConfig().getEndpointId().startsWith(appName + "."));
        }
    }

    @Test
    public void whenOverridden() {
        try (MatsTestFactory matsFactory = MatsTestFactory.create()) {
            String commonEndpointGroupId = "MyCommonEndpointGroupId";
            matsFactory.getFactoryConfig().setCommonEndpointGroupId(commonEndpointGroupId);
            Assert.assertEquals("When overridden, the CommonEndpointGroupId should be the overridden value",
                    commonEndpointGroupId, matsFactory.getFactoryConfig().getCommonEndpointGroupId());

            var single = matsFactory.single(".EndpointA", String.class, String.class, (ctx, msg) -> msg);
            Assert.assertTrue("When overridden, the CommonEndpointGroupId should be the overridden value",
                    single.getEndpointConfig().getEndpointId().startsWith(commonEndpointGroupId + "."));

            var staged = matsFactory.staged(".EndpointB", String.class, StateTO.class);
            Assert.assertTrue("When overridden, the CommonEndpointGroupId should be the overridden value",
                    staged.getEndpointConfig().getEndpointId().startsWith(commonEndpointGroupId + "."));

            var terminator = matsFactory.terminator(".EndpointC", String.class, String.class, (ctx, state, msg) -> {
            });
            Assert.assertTrue("When overridden, the CommonEndpointGroupId should be the overridden value",
                    terminator.getEndpointConfig().getEndpointId().startsWith(commonEndpointGroupId + "."));

            var subTerminator = matsFactory.subscriptionTerminator(".EndpointD", String.class, String.class,
                    (ctx, state, msg) -> {
                    });
            Assert.assertTrue("When overridden, the CommonEndpointGroupId should be the overridden value",
                    subTerminator.getEndpointConfig().getEndpointId().startsWith(commonEndpointGroupId + "."));
        }
    }
}
