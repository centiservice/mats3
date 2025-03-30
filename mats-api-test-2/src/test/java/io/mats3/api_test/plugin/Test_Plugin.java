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

package io.mats3.api_test.plugin;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.MatsFactory.MatsPlugin;
import io.mats3.MatsInitiator;
import io.mats3.test.MatsTestFactory;

/**
 * Tests the MatsPlugin interface, and that it is invoked correctly.
 *
 * @author Endre Stølsvik 2023-09-02 22:53 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_Plugin {

    @Test
    public void lifecycle_InstallAndRemoveAndMatsFactoryStop() {
        // :: ARRANGE

        try (MatsTestFactory matsFactory = MatsTestFactory.create()) {

            // .. Create Endpoint to assert presence of within plugin's start(..) method.
            MatsEndpoint<String, Void> single = matsFactory.single("endpoint", String.class, String.class,
                    (ctx, msg) -> "reply");

            // .. Create test MatsPlugin
            class TestMatsPlugin implements MatsPlugin {
                boolean _started;
                boolean _preStop;
                boolean _stop;

                @Override
                public void start(MatsFactory matsFactoryMethodArg) {
                    Assert.assertEquals(matsFactory, matsFactoryMethodArg);
                    List<MatsEndpoint<?, ?>> endpoints = matsFactoryMethodArg.getEndpoints();
                    Assert.assertEquals(1, endpoints.size());
                    Assert.assertEquals(single, endpoints.get(0));
                    _started = true;
                }

                @Override
                public void preStop() {
                    Assert.assertFalse(_stop);
                    _preStop = true;
                }

                @Override
                public void stop() {
                    Assert.assertTrue(_preStop);
                    _stop = true;
                }

                public void reset() {
                    _started = _preStop = _stop = false;
                }
            }

            // :: ACT: Create and install Plugin

            TestMatsPlugin plugin = new TestMatsPlugin();

            matsFactory.getFactoryConfig().installPlugin(plugin);

            // :: ASSERT

            // Assert of the plugin's start(..) method is done in the plugin itself, but check that it went ok.
            Assert.assertTrue(plugin._started);

            // Assert that the plugin is installed in the MatsFactory
            List<TestMatsPlugin> testPlugin = matsFactory.getFactoryConfig().getPlugins(TestMatsPlugin.class);
            Assert.assertEquals(1, testPlugin.size());
            Assert.assertEquals(plugin, testPlugin.get(0));

            // Assert that the metrics and logging interceptors are also installed
            List<MatsPlugin> allPlugins = matsFactory.getFactoryConfig().getPlugins(MatsPlugin.class);
            Assert.assertEquals(3, allPlugins.size());

            // :: ACT: Remove Plugin

            // Remove plugin
            matsFactory.getFactoryConfig().removePlugin(plugin);

            // :: ASSERT

            // Assert that the plugin is removed from the MatsFactory
            testPlugin = matsFactory.getFactoryConfig().getPlugins(TestMatsPlugin.class);
            Assert.assertEquals(0, testPlugin.size());

            // Assert that the metrics and logging interceptors are still installed
            allPlugins = matsFactory.getFactoryConfig().getPlugins(MatsPlugin.class);
            Assert.assertEquals(2, allPlugins.size());

            // Assert that the plugin's preStop(..) and stop(..) methods were invoked
            Assert.assertTrue(plugin._preStop);
            Assert.assertTrue(plugin._stop);

            // :: ACT: Stop MatsFactory

            // Reset plugin
            plugin.reset();

            // Reinstall plugin
            matsFactory.getFactoryConfig().installPlugin(plugin);

            // :: ASSERT

            // Assert that the plugin is installed in the MatsFactory
            testPlugin = matsFactory.getFactoryConfig().getPlugins(TestMatsPlugin.class);
            Assert.assertEquals(1, testPlugin.size());
            Assert.assertEquals(plugin, testPlugin.get(0));

            // Stop MatsFactory
            matsFactory.stop(30_000);

            // Assert that the plugin's preStop(..) and stop(..) methods were invoked correctly
            Assert.assertTrue(plugin._preStop);
            Assert.assertTrue(plugin._stop);

            // Reset plugin, so that AutoCloseable's close() can be invoked without assert exceptions.
            plugin.reset();
        }
    }

    @Test
    public void addingAndRemovingEndpoints() {
        try (MatsTestFactory matsFactory = MatsTestFactory.create()) {

            // .. Create Endpoint to assert presence of within plugin's start(..) method.
            MatsEndpoint<String, Void> a_Endpoint = matsFactory.single("A-endpoint", String.class, String.class,
                    (ctx, msg) -> "reply");

            // .. Create test MatsPlugin
            class TestMatsPlugin implements MatsPlugin {
                boolean _started;

                MatsEndpoint<?, ?> _b_Endpoint;

                MatsInitiator _addedInitiator;

                MatsEndpoint<?, ?> _addedEndpoint;

                MatsEndpoint<?, ?> _removedEndpoint;

                @Override
                public void start(MatsFactory matsFactoryMethodArg) {
                    Assert.assertEquals(matsFactory, matsFactoryMethodArg);
                    List<MatsEndpoint<?, ?>> endpoints = matsFactoryMethodArg.getEndpoints();
                    Assert.assertEquals(1, endpoints.size());
                    Assert.assertEquals(a_Endpoint, endpoints.get(0));

                    // Add another endpoint
                    _b_Endpoint = matsFactoryMethodArg.single("B-endpoint", String.class, String.class,
                            (ctx, msg) -> "reply");

                    _started = true;
                }

                @Override
                public void addingInitiator(MatsInitiator initiator) {
                    _addedInitiator = initiator;
                }

                @Override
                public void addingEndpoint(MatsEndpoint<?, ?> endpoint) {
                    _addedEndpoint = endpoint;
                }

                @Override
                public void removedEndpoint(MatsEndpoint<?, ?> endpoint) {
                    _removedEndpoint = endpoint;
                }
            }

            // :: ACT: Create and install Plugin

            TestMatsPlugin plugin = new TestMatsPlugin();

            matsFactory.getFactoryConfig().installPlugin(plugin);

            // :: ASSERT

            // Assert of the plugin's start(..) method is done in the plugin itself, but check that it went ok.
            Assert.assertTrue(plugin._started);

            // Assert that the Endpoint added inside the start method was added
            List<MatsEndpoint<?, ?>> endpoints = matsFactory.getEndpoints(); // getEndpoints() is sorted
            Assert.assertEquals(2, endpoints.size());
            Assert.assertEquals(a_Endpoint, endpoints.get(0));
            Assert.assertEquals(plugin._b_Endpoint, endpoints.get(1));

            // Assert that addingEndpoint() was not invoked by us adding an endpoint within the plugin's start(..)
            Assert.assertNull(plugin._addedEndpoint);
            // Same for initiator - none added yet
            Assert.assertNull(plugin._addedInitiator);

            // :: ACT: Add initiator

            MatsInitiator d_Initiator = matsFactory.getOrCreateInitiator("initiator");

            // :: ASSERT

            // Assert that addingInitiator() was invoked by us adding an initiator
            // Note, the returned MatsInitiator is wrapped, so we cannot do an equals() on it. Using name instead.
            Assert.assertEquals(d_Initiator.getName(), plugin._addedInitiator.getName());
            // No endpoint added yet (except the one we added inside plugin, which was before the plugin was installed)
            Assert.assertNull(plugin._addedEndpoint);

            // :: ACT: Add an Endpoint "from the outside", i.e. not within the plugin. The plugin is now installed.

            MatsEndpoint<String, Void> c_Endpoint = matsFactory.single("C-endpoint", String.class, String.class,
                    (ctx, msg) -> "reply");

            // :: ASSERT

            // Assert that addingEndpoint() was invoked by us adding an endpoint
            Assert.assertEquals(c_Endpoint, plugin._addedEndpoint);

            // :: ACT: Remove endpoint
            c_Endpoint.remove(30_000);

            // :: ASSERT

            // Assert that removedEndpoint() was invoked by us removing an endpoint
            Assert.assertEquals(c_Endpoint, plugin._removedEndpoint);
        }
    }
}
