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

package io.mats3.test.broker;

import javax.jms.ConnectionFactory;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.Assert;
import org.junit.Test;

import io.mats3.test.broker.MatsTestBroker.MatsTestBroker_InVmActiveMq;

public class Test_SpecifyMatsTestBrokerClass {

    @Test
    public void test_specify_MatsTestBroker_class() {
        try {
            System.setProperty(MatsTestBroker.SYSPROP_MATS_TEST_BROKER, MatsTestBroker_InVmActiveMq.class.getName());

            MatsTestBroker matsTestBroker = MatsTestBroker.create();
            ConnectionFactory connectionFactory = matsTestBroker.getConnectionFactory();
            Assert.assertEquals("This should be an ActiveMqConnectionFactory!",
                    connectionFactory.getClass(), ActiveMQConnectionFactory.class);
            matsTestBroker.close();
        }
        finally {
            System.clearProperty(MatsTestBroker.SYSPROP_MATS_TEST_BROKER);
        }
    }

}
