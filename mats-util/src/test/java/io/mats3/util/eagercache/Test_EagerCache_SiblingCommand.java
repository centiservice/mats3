package io.mats3.util.eagercache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestFactory;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.eagercache.MatsEagerCacheServer.SiblingCommand;

/**
 * Tests the {@link SiblingCommand} concept between {@link MatsEagerCacheServer}s.
 */
public class Test_EagerCache_SiblingCommand {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_SiblingCommand.class);

    @Test
    public void run() throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        CustomerData sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 1);

        // :: Create the two MatsFactories, representing two different instances of the server-side service:
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory serverMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServers:
        MatsEagerCacheServer cacheServer1 = new MatsEagerCacheServer(serverMatsFactory1,
                "Customers", CustomerTransmitDTO.class, 1,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        MatsEagerCacheServer cacheServer2 = new MatsEagerCacheServer(serverMatsFactory2,
                "Customers", CustomerTransmitDTO.class, 1,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        CountDownLatch[] latch = new CountDownLatch[1];

        SiblingCommand[] siblingCommand = new SiblingCommand[3];

        cacheServer1.addSiblingCommandListener(command -> {
            log.info("CacheServer1 #A: Got sibling command: " + command);
            siblingCommand[0] = command;
            latch[0].countDown();
        });

        cacheServer1.addSiblingCommandListener(command -> {
            log.info("CacheServer1 #B: Got sibling command: " + command);
            siblingCommand[1] = command;
            latch[0].countDown();
        });

        cacheServer2.addSiblingCommandListener(command -> {
            log.info("CacheServer2: Got sibling command: " + command);
            siblingCommand[2] = command;
            latch[0].countDown();
        });

        // ## ACT:

        log.info("\n\n######### Starting the CacheServers, waiting for receive loops.\n\n");

        cacheServer1.start();
        cacheServer2.start();
        cacheServer1._waitForReceiving();
        cacheServer2._waitForReceiving();

        log.info("\n\n######### Sending SiblingCommand from CacheServer 1.\n\n");

        // Random byte array
        byte[] randomBytes = new byte[100];
        for (int i = 0; i < randomBytes.length; i++) {
            randomBytes[i] = (byte) (Math.random() * 256);
        }
        // Random String
        String randomString = "Random String: " + Math.random();

        String commandName = "Hello, CacheServers siblings! Here's some values!";
        latch[0] = new CountDownLatch(3);
        cacheServer1.sendSiblingCommand(commandName, randomString, randomBytes);
        latchWaitAndAssert(latch, siblingCommand, commandName, randomString, randomBytes);

        // Assert that the "sent from this host" works.
        Assert.assertTrue("SiblingCommand[0] should be sent from this host", siblingCommand[0]
                .commandOriginatedOnThisInstance());
        Assert.assertTrue("SiblingCommand[0] should be sent from this host", siblingCommand[1]
                .commandOriginatedOnThisInstance());
        Assert.assertFalse("SiblingCommand[2] should NOT be sent from this host", siblingCommand[2]
                .commandOriginatedOnThisInstance());


        commandName = "Hello, CacheServers siblings! Here's are som nulls!";
        latch[0] = new CountDownLatch(3);
        cacheServer2.sendSiblingCommand(commandName, null, null);
        latchWaitAndAssert(latch, siblingCommand, commandName, null, null);

        // Assert that the "sent from this host" works.
        Assert.assertFalse("SiblingCommand[0] should NOT be sent from this host", siblingCommand[0]
                .commandOriginatedOnThisInstance());
        Assert.assertFalse("SiblingCommand[0] should NOT be sent from this host", siblingCommand[1]
                .commandOriginatedOnThisInstance());
        Assert.assertTrue("SiblingCommand[2] should be sent from this host", siblingCommand[2]
                .commandOriginatedOnThisInstance());

        // Shutdown
        serverMatsFactory1.close();
        serverMatsFactory2.close();
        matsTestBroker.close();
    }

    private static void latchWaitAndAssert(CountDownLatch[] latch, SiblingCommand[] siblingCommand, String commandName, String randomString, byte[] randomBytes) throws InterruptedException {
        log.info("\n\n######### Waiting for sibling command to be received.\n\n");
        latch[0].await(30, TimeUnit.SECONDS);

        // ## ASSERT:

        log.info("\n\n######### Latched!\n\n");

        // Check that the sibling commands are as expected.
        for (int i = 0; i < 3; i++) {
            log.info("SiblingCommand[" + i + "]: " + siblingCommand[i]);
            // Compare the elements
            Assert.assertEquals("SiblingCommand[" + i + "]: commandName", commandName, siblingCommand[i].getCommand());
            Assert.assertEquals("SiblingCommand[" + i + "]: string", randomString, siblingCommand[i].getStringData());
            Assert.assertArrayEquals("SiblingCommand[" + i + "]: bytes", randomBytes, siblingCommand[i]
                    .getBinaryData());
        }
    }
}
