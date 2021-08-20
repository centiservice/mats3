package io.mats3.test.broker;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * A small experiment with the Artemis broker, which emits debug loglines with exception stacktraces of exception
 * ActiveMQQueueExistsException. This is evidently expected when talking with the broker over JMS, and multiple
 * consumers tries to create the queue on the broker at the same time:
 * https://issues.apache.org/jira/browse/ARTEMIS-3424
 */
public class Test_ArtemisExceptionTest {

    @Test
    public void test() throws Exception {
        String brokerUrl = true ? "vm://test" : "tcp://localhost:61616";

        EmbeddedActiveMQ broker = brokerUrl.startsWith("vm")
                ? MatsTestBroker.MatsTestBroker_Artemis.createArtemisBroker(brokerUrl)
                : null;

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);

        Connection connection = connectionFactory.createConnection();

        int numberOfMessages = 50;
        CountDownLatch countDownLatch = new CountDownLatch(numberOfMessages);

        Thread thread1 = new Thread(() -> consumer(connection, countDownLatch), "EndreXY 1");
        Thread thread2 = new Thread(() -> consumer(connection, countDownLatch), "EndreXY 2");
        thread1.start();
        thread2.start();

        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("Test.queue");
        MessageProducer producer = session.createProducer(queue);
        for (int i = 0; i < 50; i++) {
            TextMessage textMessage = session.createTextMessage("Message " + i);
            producer.send(textMessage);
        }

        boolean await = countDownLatch.await(10, TimeUnit.SECONDS);
        Assert.assertTrue("Counted down correctly, received all messages", await);

        connection.close();
        if (broker != null) {
            broker.stop();
        }
    }

    private void consumer(Connection con, CountDownLatch countDownLatch) {
        try {
            Session session = con.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("Test.queue");
            MessageConsumer consumer = session.createConsumer(queue);
            while (true) {
                Message msg = consumer.receive(10_000);
                countDownLatch.countDown();
                if (msg instanceof TextMessage) {
                    TextMessage txtMsg = (TextMessage) msg;
                    System.out.println("Received message!" + txtMsg.getText());
                }
                if (msg == null) {
                    break;
                }
            }
        }
        catch (Exception e) {
            System.out.println("Got exception when receiving.");
        }
    }

    public static void main(String... args) throws Exception {
        System.setProperty(MatsTestBroker.SYSPROP_MATS_TEST_BROKER,
                MatsTestBroker.SYSPROP_MATS_TEST_BROKER_VALUE_ARTEMIS);
        new Test_ArtemisExceptionTest().test();
        System.out.println("Done!");
    }
}
