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

package io.mats3.util.eagercache;

import static io.mats3.test.MatsTestHelp.takeNap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheReceivedPartialData;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl;

/**
 * Factored out setup for tests that involve two servers and two clients.
 */
public class CommonSetup_TwoServers_TwoClients {

    private static final Logger log = LoggerFactory.getLogger(CommonSetup_TwoServers_TwoClients.class);

    public final ObjectMapper objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    public final ObjectWriter customerDataWriter = objectMapper.writerFor(CustomerData.class);
    public final ObjectReader customerDataReader = objectMapper.readerFor(CustomerData.class);

    public final CustomerData sourceData1;
    public final CustomerData sourceData2;
    public final MatsTestBroker matsTestBroker;
    public final MatsFactory serverMatsFactory1;
    public final MatsFactory serverMatsFactory2;
    public final MatsFactory clientMatsFactory1;
    public final MatsFactory clientMatsFactory2;
    public final MatsEagerCacheServer cacheServer1;
    public final MatsEagerCacheServer cacheServer2;
    public final MatsEagerCacheClient<DataCarrier> cacheClient1;
    public final MatsEagerCacheClient<DataCarrier> cacheClient2;
    public final CountDownLatch[] cacheClient1_latch;
    public final CountDownLatch[] cacheClient2_latch;
    public final CacheUpdated[] cacheClient1_updated;
    public final CacheUpdated[] cacheClient2_updated;
    public final AtomicInteger cacheClient1_updateCount;
    public final AtomicInteger cacheClient2_updateCount;

    public static CommonSetup_TwoServers_TwoClients create(int originalCount) throws JsonProcessingException,
            InterruptedException {
        return new CommonSetup_TwoServers_TwoClients(originalCount, null /* partialUpdateMapper */,
                (server) -> {
                    // No server adjustments
                }, (client) -> {
                    // No client adjustments
                });
    }

    public static CommonSetup_TwoServers_TwoClients createWithServerAdjust(int originalCount,
            Consumer<MatsEagerCacheServer> serversAdjust) throws JsonProcessingException, InterruptedException {
        return new CommonSetup_TwoServers_TwoClients(originalCount, null /* partialUpdateMapper */, serversAdjust,
                (client) -> {
                    // No client adjustments
                });
    }

    public static CommonSetup_TwoServers_TwoClients createWithClientAdjust(int originalCount,
            Consumer<MatsEagerCacheClient<?>> clientsAdjust) throws JsonProcessingException, InterruptedException {
        return new CommonSetup_TwoServers_TwoClients(originalCount, null /* partialUpdateMapper */,
                (server) -> {
                    // No server adjustments
                }, clientsAdjust);
    }

    public static CommonSetup_TwoServers_TwoClients createWithPartialUpdateMapper(int originalCount,
            Function<CacheReceivedPartialData<CustomerTransferDTO, DataCarrier>, DataCarrier> partialUpdateMapper)
            throws JsonProcessingException, InterruptedException {
        return new CommonSetup_TwoServers_TwoClients(originalCount, partialUpdateMapper,
                (server) -> {
                    // No server adjustments
                }, (client) -> {
                    // No client adjustments
                });
    }

    private CommonSetup_TwoServers_TwoClients(int originalCount,
            Function<CacheReceivedPartialData<CustomerTransferDTO, DataCarrier>, DataCarrier> partialUpdateMapper,
            Consumer<MatsEagerCacheServer> serversAdjust,
            Consumer<MatsEagerCacheClient<?>> clientsAdjust)
            throws JsonProcessingException, InterruptedException {
        // Create source data, one set for each server.
        sourceData1 = DummyFinancialService.createRandomReplyDTO(1234L, originalCount);
        sourceData2 = DummyFinancialService.createRandomReplyDTO(1234L, originalCount);

        // Assert that they are identical
        Assert.assertEquals("The source datas should be the same",
                customerDataWriter.writeValueAsString(sourceData1),
                customerDataWriter.writeValueAsString(sourceData2));

        // :: Create four MatsFactories, representing two different instances of the server-side service, and
        // two different instances of the client-side service.
        matsTestBroker = MatsTestBroker.create();
        MatsSerializerJson matsSerializer = MatsSerializerJson.create();

        serverMatsFactory1 = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("CustomerService", "*testing*",
                JmsMatsJmsSessionHandler_Pooling.create(matsTestBroker.getConnectionFactory()), matsSerializer);
        serverMatsFactory1.getFactoryConfig().setNodename(serverMatsFactory1.getFactoryConfig().getNodename() + "-1s");

        serverMatsFactory2 = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("CustomerService", "*testing*",
                JmsMatsJmsSessionHandler_Pooling.create(matsTestBroker.getConnectionFactory()), matsSerializer);
        serverMatsFactory2.getFactoryConfig().setNodename(serverMatsFactory2.getFactoryConfig().getNodename() + "-2s");

        clientMatsFactory1 = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("OrderService", "*testing*",
                JmsMatsJmsSessionHandler_Pooling.create(matsTestBroker.getConnectionFactory()), matsSerializer);
        clientMatsFactory1.getFactoryConfig().setNodename(clientMatsFactory1.getFactoryConfig().getNodename() + "-1c");

        clientMatsFactory2 = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions("AccountService", "*testing*",
                JmsMatsJmsSessionHandler_Pooling.create(matsTestBroker.getConnectionFactory()), matsSerializer);
        clientMatsFactory2.getFactoryConfig().setNodename(clientMatsFactory2.getFactoryConfig().getNodename() + "-2c");

        // :: Create the CacheServers:
        cacheServer1 = MatsEagerCacheServer.create(serverMatsFactory1,
                "Customers", CustomerTransferDTO.class,
                () -> (consumeTo) -> sourceData1.customers.stream()
                        .map(CustomerTransferDTO::fromCustomerDTO).forEach(o -> {
                            takeNap(1);
                            consumeTo.accept(o);
                        }));
        // Adjust delays for testing
        adjustDelaysForTest(cacheServer1);
        // Adjust the server from caller
        serversAdjust.accept(cacheServer1);

        cacheServer2 = MatsEagerCacheServer.create(serverMatsFactory2,
                "Customers", CustomerTransferDTO.class,
                () -> (consumeTo) -> sourceData2.customers.stream()
                        .map(CustomerTransferDTO::fromCustomerDTO).forEach(o -> {
                            takeNap(3);
                            consumeTo.accept(o);
                        }));
        // Adjust delays for testing
        adjustDelaysForTest(cacheServer2);
        // Adjust the server from caller
        serversAdjust.accept(cacheServer2);

        // :: Create the CacheClients:
        if (partialUpdateMapper == null) {
            cacheClient1 = MatsEagerCacheClient.create(clientMatsFactory1, "Customers",
                    CustomerTransferDTO.class, DataCarrier::new);
            clientsAdjust.accept(cacheClient1);

            cacheClient2 = MatsEagerCacheClient.create(clientMatsFactory2, "Customers",
                    CustomerTransferDTO.class, DataCarrier::new);
            clientsAdjust.accept(cacheClient2);
        }
        else {
            cacheClient1 = MatsEagerCacheClient.create(clientMatsFactory1, "Customers",
                    CustomerTransferDTO.class, DataCarrier::new, partialUpdateMapper);
            clientsAdjust.accept(cacheClient1);

            cacheClient2 = MatsEagerCacheClient.create(clientMatsFactory2, "Customers",
                    CustomerTransferDTO.class, DataCarrier::new, partialUpdateMapper);
            clientsAdjust.accept(cacheClient2);
        }

        cacheClient1_latch = new CountDownLatch[1];
        cacheClient2_latch = new CountDownLatch[1];

        cacheClient1_updated = new CacheUpdated[1];
        cacheClient2_updated = new CacheUpdated[1];

        cacheClient1_updateCount = new AtomicInteger();
        cacheClient2_updateCount = new AtomicInteger();

        // .. recording the updates - and then latching, so that the test can go to next phase.
        cacheClient1.addCacheUpdatedListener((cacheUpdated) -> {
            log.info("Cache 1 updated! " + (cacheUpdated.isFullUpdate() ? "Full" : "PARTIAL") + " " + cacheUpdated);
            cacheClient1_updateCount.incrementAndGet();
            cacheClient1_updated[0] = cacheUpdated;
            cacheClient1_latch[0].countDown();
        });
        cacheClient2.addCacheUpdatedListener((cacheUpdated) -> {
            log.info("Cache 2 updated! " + (cacheUpdated.isFullUpdate() ? "Full" : "PARTIAL") + " " + cacheUpdated);
            cacheClient2_updateCount.incrementAndGet();
            cacheClient2_updated[0] = cacheUpdated;
            cacheClient2_latch[0].countDown();
        });

        log.info("\n\n######### Starting the CacheServers and CacheClient, waiting for CacheServers receiving.\n\n");

        cacheClient1_latch[0] = new CountDownLatch(1);
        cacheClient2_latch[0] = new CountDownLatch(1);

        cacheServer1.startAndWaitForReceiving();
        cacheServer2.startAndWaitForReceiving();
        cacheClient1.start();
        cacheClient2.start();

        // .. initial population is automatically done, so we'll wait for that to happen.
        waitForClientsUpdate();

        // :: Assert that the CacheClients were updated, and that they now have the initial data.
        assertUpdateAndConsistency(true, originalCount);

        // Assert that we've only gotten one update for each cache (even though both of them requested full update)
        Assert.assertEquals(1, cacheClient1_updateCount.get());
        Assert.assertEquals(1, cacheClient2_updateCount.get());
        // Assert that these were initial updates.
        Assert.assertTrue(cacheClient1_updated[0].isInitialPopulation());
        Assert.assertTrue(cacheClient2_updated[0].isInitialPopulation());
    }

    static void adjustDelaysForTest(MatsEagerCacheServer cacheServer) {
        // Changing delays (towards shorter), as we're testing. But also handle CI, which can be very slow.
        int shortDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE; // Local: 250ms, On CI: 1 sec
        int longDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE * 2; // Local: 500ms, On CI: 2 sec
        ((MatsEagerCacheServerImpl) cacheServer)._setCoalescingDelays(shortDelay, longDelay);
    }

    public void waitForClientsUpdate() throws InterruptedException {
        boolean cacheClient1Updated = cacheClient1_latch[0].await(30, TimeUnit.SECONDS);
        if (!cacheClient1Updated) {
            throw new AssertionError("CacheClient #1 did not get CacheUpdated callback within 30 seconds.");
        }
        boolean cacheClient2Updated = cacheClient2_latch[0].await(30, TimeUnit.SECONDS);
        if (!cacheClient2Updated) {
            throw new AssertionError("CacheClient #2 did not get CacheUpdated callback within 30 seconds.");
        }
    }

    public void assertUpdateAndConsistency(boolean expectedFullUpdate, int expectedDataCount)
            throws JsonProcessingException {
        // :: Assert internal consistency between the two source datasets
        // NOTE! This is the responsibility of the server side (i.e. the server-side service), not the cache system.
        // Ref. partial updates: The service itself must ensure that the data is consistent between its instances.
        Assert.assertEquals("The CacheServer's source datas should be the same!",
                customerDataWriter.writeValueAsString(sourceData1),
                customerDataWriter.writeValueAsString(sourceData2));

        // :: Assert updated
        Assert.assertNotNull(cacheClient1_updated[0]);
        Assert.assertNotNull(cacheClient2_updated[0]);

        Assert.assertNotNull("CacheClient should have gotten CacheUpdated callback.", cacheClient1_updated[0]);
        Assert.assertEquals("DataCount in CacheUpdated should match server's source.", expectedDataCount,
                cacheClient1_updated[0].getDataCount());

        Assert.assertNotNull("CacheClient should have gotten CacheUpdated callback.", cacheClient2_updated[0]);
        Assert.assertEquals("DataCount in CacheUpdated should match server's source.", expectedDataCount,
                cacheClient2_updated[0].getDataCount());

        if (expectedFullUpdate) {
            Assert.assertTrue(cacheClient1_updated[0].isFullUpdate());
            Assert.assertTrue(cacheClient2_updated[0].isFullUpdate());
        }
        else {
            Assert.assertFalse(cacheClient1_updated[0].isFullUpdate());
            Assert.assertFalse(cacheClient2_updated[0].isFullUpdate());
        }

        // :: Assert same data all over

        DataCarrier dataCarrier1 = cacheClient1.get();
        Assert.assertNotNull(dataCarrier1);
        CustomerData cacheData1 = new CustomerData(dataCarrier1.customers);

        Assert.assertEquals("The serialized data should be the same from ClientServer source,"
                + " via server-to-client, and to CacheClient - #1's",
                customerDataWriter.writeValueAsString(sourceData1),
                customerDataWriter.writeValueAsString(cacheData1));

        // Create cache2-side data from the source data, and serialize it.
        DataCarrier dataCarrier2 = cacheClient2.get();
        Assert.assertNotNull(dataCarrier2);
        CustomerData cacheData2 = new CustomerData(dataCarrier2.customers);

        Assert.assertEquals("The serialized data should be the same from ClientServer source,"
                + " via server-to-client, and to CacheClient - #1's",
                customerDataWriter.writeValueAsString(sourceData2),
                customerDataWriter.writeValueAsString(cacheData2));

        // :: Just to be explicit, but this is already asserted in the above.
        Assert.assertEquals("The two CacheClient's data should be the same",
                customerDataWriter.writeValueAsString(cacheData1),
                customerDataWriter.writeValueAsString(cacheData2));
    }

    public void reset() {
        cacheClient1_updated[0] = null;
        cacheClient2_updated[0] = null;
        cacheClient1_latch[0] = new CountDownLatch(1);
        cacheClient2_latch[0] = new CountDownLatch(1);
    }

    public void close() {
        cacheClient1.close();
        cacheClient2.close();
        cacheServer1.close();
        cacheServer2.close();

        serverMatsFactory1.close();
        serverMatsFactory2.close();
        clientMatsFactory1.close();
        clientMatsFactory2.close();

        matsTestBroker.close();
    }
}
