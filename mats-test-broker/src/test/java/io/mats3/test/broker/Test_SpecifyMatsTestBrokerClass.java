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
