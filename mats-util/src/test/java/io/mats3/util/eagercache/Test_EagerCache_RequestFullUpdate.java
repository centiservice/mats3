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
import io.mats3.util.DummyFinancialService.ReplyDTO;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;

public class Test_EagerCache_RequestFullUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_RequestFullUpdate.class);

    @Test
    public void run() throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        ReplyDTO sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 5);

        // :: Create the three MatsFactories, representing two different instances of the server-side service, and
        // one client-side service.
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory serverMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServers:
        MatsEagerCacheServer cacheServer1 = new MatsEagerCacheServer(serverMatsFactory1,
                "Customers", CustomerCacheDTO.class, 1,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerCacheDTO::fromCustomerDTO);

        MatsEagerCacheServer cacheServer2 = new MatsEagerCacheServer(serverMatsFactory2,
                "Customers", CustomerCacheDTO.class, 1,
                () -> (consumeTo) -> sourceData.customers.forEach(consumeTo),
                CustomerCacheDTO::fromCustomerDTO);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient = new MatsEagerCacheClient<>(clientMatsFactory,
                "Customers", CustomerCacheDTO.class,
                DataCarrier::new, null);

        CountDownLatch[] updatedLatch = new CountDownLatch[1];

        CacheUpdated[] cacheUpdated = new CacheUpdated[1];
        // .. testing that the initial population is done.
        cacheClient.addCacheUpdatedListener((receivedData) -> {
            log.info("Cache updated! Size:[" + receivedData.getDataCount() + "]");
            cacheUpdated[0] = receivedData;
            updatedLatch[0].countDown();
        });

        log.info("\n\n######### Starting the CacheServers and CacheClient, waiting for receive loops.\n\n");

        updatedLatch[0] = new CountDownLatch(1);

        cacheServer1.start();
        cacheServer2.start();
        cacheClient.start();

        // .. initial population is automatically done, so we must get past this.

        DataCarrier dataCarrier = cacheClient.get();
        Assert.assertNotNull(dataCarrier);

        Assert.assertNotNull(cacheUpdated[0]);
        Assert.assertTrue(cacheUpdated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cacheUpdated[0].getDataCount());

        // ## ACT:

        updatedLatch[0] = new CountDownLatch(1);

        // Request full update
        cacheClient.requestFullUpdate();

        updatedLatch[0].await(30, TimeUnit.SECONDS);

        // ## ASSERT:
        Assert.assertNotNull(cacheUpdated[0]);
        Assert.assertTrue(cacheUpdated[0].isFullUpdate());
        Assert.assertEquals(sourceData.customers.size(), cacheUpdated[0].getDataCount());
    }

}
