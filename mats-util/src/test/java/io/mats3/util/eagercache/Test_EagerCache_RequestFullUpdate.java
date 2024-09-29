package io.mats3.util.eagercache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestFactory;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;

public class Test_EagerCache_RequestFullUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_RequestFullUpdate.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void onlyClientSide() throws InterruptedException, JsonProcessingException {
        run(0, 3);
    }

    @Test
    public void onlyServerSide() throws InterruptedException, JsonProcessingException {
        run(3, 0);
    }

    @Test
    public void fromBothServerAndClient() throws InterruptedException, JsonProcessingException {
        run(3, 3);
    }

    private void run(int serverSideCount, int clientSideCount) throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        CustomerData sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 10);
        // For comparison on the client side: Serialize the source data.
        String serializedSourceData = _replyWriter.writeValueAsString(sourceData);

        // :: Create the three MatsFactories, representing two different instances of the server-side service, and
        // one client-side service.
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory serverMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServers:
        MatsEagerCacheServer cacheServer1 = new MatsEagerCacheServer(serverMatsFactory1,
                "Customers", CustomerTransmitDTO.class,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        MatsEagerCacheServer cacheServer2 = new MatsEagerCacheServer(serverMatsFactory2,
                "Customers", CustomerTransmitDTO.class,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient1 = new MatsEagerCacheClient<>(clientMatsFactory1,
                "Customers", CustomerTransmitDTO.class,
                DataCarrier::new);

        MatsEagerCacheClient<DataCarrier> cacheClient2 = new MatsEagerCacheClient<>(clientMatsFactory2,
                "Customers", CustomerTransmitDTO.class,
                DataCarrier::new);

        CountDownLatch[] cache1_latch = new CountDownLatch[1];
        CountDownLatch[] cache2_latch = new CountDownLatch[1];

        CacheUpdated[] cache1_updated = new CacheUpdated[1];
        CacheUpdated[] cache2_updated = new CacheUpdated[1];

        AtomicInteger cache1_updateCount = new AtomicInteger();
        AtomicInteger cache2_updateCount = new AtomicInteger();

        // .. testing that we get the updates.
        cacheClient1.addCacheUpdatedListener((receivedData) -> {
            log.info("Cache 1 updated! Size:[" + receivedData.getDataCount() + "]");
            cache1_updateCount.incrementAndGet();
            cache1_updated[0] = receivedData;
            cache1_latch[0].countDown();
        });

        // .. testing that we get the same data from the second cache client.
        cacheClient2.addCacheUpdatedListener((receivedData) -> {
            log.info("Cache 2 updated! Size:[" + receivedData.getDataCount() + "]");
            cache2_updateCount.incrementAndGet();
            cache2_updated[0] = receivedData;
            cache2_latch[0].countDown();
        });

        // Changing delays (towards shorter), as we're testing. But also handle CI, which can be dog slow.
        int shortDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE; // On CI: 1 sec
        int longDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE * 2; // On CI: 2 sec
        cacheServer1._setDelays(shortDelay, longDelay);
        cacheServer2._setDelays(shortDelay, longDelay);

        log.info("\n\n######### Starting the CacheServers and CacheClient, waiting for receive loops.\n\n");

        cache1_latch[0] = new CountDownLatch(1);
        cache2_latch[0] = new CountDownLatch(1);

        cacheServer1.startAndWaitForReceiving();
        cacheServer2.startAndWaitForReceiving();
        cacheClient1.start();
        cacheClient2.start();

        // .. initial population is automatically done, so we must get past this.

        cache1_latch[0].await(30, TimeUnit.SECONDS);
        cache2_latch[0].await(30, TimeUnit.SECONDS);

        DataCarrier dataCarrier1 = cacheClient1.get();
        Assert.assertNotNull(dataCarrier1);
        Assert.assertNotNull(cache1_updated[0]);
        Assert.assertTrue(cache1_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cache1_updated[0].getDataCount());

        // ------

        DataCarrier dataCarrier2 = cacheClient1.get();
        Assert.assertNotNull(dataCarrier2);
        Assert.assertNotNull(cache2_updated[0]);
        Assert.assertTrue(cache2_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cache2_updated[0].getDataCount());

        // Assert that we've only gotten one update for each cache
        Assert.assertEquals(1, cache1_updateCount.get());
        Assert.assertEquals(1, cache2_updateCount.get());

        // ## ACT:

        cache1_updated[0] = null;
        cache2_updated[0] = null;
        cache1_latch[0] = new CountDownLatch(1);
        cache2_latch[0] = new CountDownLatch(1);

        // From both the client and server, request a full update - do it multiple times, from all of them.
        // (This should still just result in one full update sent in total)
        for (int i = 0; i < serverSideCount; i++) {
            cacheServer1.scheduleFullUpdate();
            cacheServer2.scheduleFullUpdate();
        }
        for (int i = 0; i < clientSideCount; i++) {
            cacheClient1.requestFullUpdate();
            cacheClient2.requestFullUpdate();
        }

        cache1_latch[0].await(30, TimeUnit.SECONDS);
        cache2_latch[0].await(30, TimeUnit.SECONDS);

        // ## ASSERT:

        dataCarrier1 = cacheClient1.get();

        Assert.assertNotNull(cache1_updated[0]);
        Assert.assertTrue(cache1_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cache1_updated[0].getDataCount());

        // Create cache1-side data from the source data, and serialize it.
        CustomerData cacheData = new CustomerData();
        cacheData.customers = dataCarrier1.customers;
        String serializedCacheData = _replyWriter.writeValueAsString(cacheData);

        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache 1 - after a requested full update", serializedSourceData, serializedCacheData);

        // ------

        dataCarrier2 = cacheClient1.get();

        Assert.assertNotNull(cache2_updated[0]);
        Assert.assertTrue(cache2_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cache2_updated[0].getDataCount());

        // Create cache2-side data from the source data, and serialize it.
        cacheData = new CustomerData();
        cacheData.customers = dataCarrier2.customers;
        serializedCacheData = _replyWriter.writeValueAsString(cacheData);

        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache 2 - after a requested full update", serializedSourceData, serializedCacheData);

        // Assert that we've only gotten one extra update total for each cache (initial + requested full update)
        Assert.assertEquals(2, cache1_updateCount.get());
        Assert.assertEquals(2, cache2_updateCount.get());

        // Shutdown
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
