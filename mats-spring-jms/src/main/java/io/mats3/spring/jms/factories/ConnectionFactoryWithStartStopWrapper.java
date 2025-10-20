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

package io.mats3.spring.jms.factories;

import jakarta.jms.ConnectionFactory;

import io.mats3.util.wrappers.ConnectionFactoryWrapper;

/**
 * A abstract {@link ConnectionFactoryWrapper} recognized by {@link ScenarioConnectionFactoryProducer}, which has a
 * start() and stop() method, which can be used if you need to fire up a local MQ Broker: This class is meant to be
 * extended to provide such functionality.
 *
 * @author Endre Stølsvik 2019-06-12 00:26 - http://stolsvik.com/, endre@stolsvik.com
 */
public abstract class ConnectionFactoryWithStartStopWrapper extends ConnectionFactoryWrapper {
    /**
     * Start whatever is needed to support the ConnectionFactory, i.e. a localVm MQ Broker. If you return a
     * {@link ConnectionFactory}, this will be set on the wrapper using {@link #setWrappee(ConnectionFactory)}. If you
     * return <code>null</code>, nothing will be done - implying that you need to do that setting.
     */
    public abstract ConnectionFactory start(String beanName) throws Exception;

    /**
     * Stop whatever you started in {@link #start(String)}.
     */
    public abstract void stop() throws Exception;
}
