package io.mats3.util.eagercache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestFactory;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerDTO;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheSourceDataCallback;

/**
 * Simple/basic test of the {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient}: A single server and a single
 * client, where the server has data, and the client is then expected to get the same data.
 */
public class Test_EagerCache_SimpleCacheServerAndClient {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_SimpleCacheServerAndClient.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void run() throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        CustomerData sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 10);
        // For comparison on the client side: Serialize the source data.
        String serializedSourceData = _replyWriter.writeValueAsString(sourceData);

        // :: Create the two MatsFactories, representing two different services:
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServer.
        MatsEagerCacheServer cacheServer = new MatsEagerCacheServer(serverMatsFactory,
                "Customers", CustomerTransmitDTO.class, 1,
                () -> new CustomerDTOCacheSourceDataCallback(sourceData),
                CustomerTransmitDTO::fromCustomerDTO);

        // Adjust the timings for fast test.
        cacheServer._setDelays(250, 500);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient = new MatsEagerCacheClient<>(clientMatsFactory,
                "Customers", CustomerTransmitDTO.class,
                DataCarrier::new);

        CountDownLatch latch = new CountDownLatch(1);

        // .. testing that the initial population is done.
        cacheClient.addAfterInitialPopulationTask(() -> {
            log.info("Initial population done!");
            latch.countDown();
        });

        // ## ACT:

        log.info("\n\n######### Starting the CacheServer and CacheClient.\n\n");

        cacheServer.start();
        cacheClient.start();

        log.info("\n\n######### Waiting for initial population to be done.\n\n");
        latch.await(30, TimeUnit.SECONDS);

        log.info("\n\n######### Latched!\n\n");

        // ## ASSERT:

        DataCarrier dataCarrier = cacheClient.get();
        log.info("######### Got the data! Size:[" + dataCarrier.customers.size() + "]");

        // Create cache-side data from the source data, and serialize it.
        CustomerData cacheData = new CustomerData();
        cacheData.customers = dataCarrier.customers;
        String serializedCacheData = _replyWriter.writeValueAsString(cacheData);

        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache.", serializedSourceData, serializedCacheData);

        // Shutdown
        serverMatsFactory.close();
        clientMatsFactory.close();
        matsTestBroker.close();
    }

    private static class CustomerDTOCacheSourceDataCallback implements CacheSourceDataCallback<CustomerDTO> {
        private final CustomerData _sourceData;

        public CustomerDTOCacheSourceDataCallback(CustomerData sourceData) {
            _sourceData = sourceData;
        }

        @Override
        public int provideDataCount() {
            return _sourceData.customers.size();
        }

        @Override
        public String provideMetadata() {
            return "Dummy Data!";
        }

        @Override
        public void provideSourceData(Consumer<CustomerDTO> consumer) {
            _sourceData.customers.forEach(consumer);
        }
    }
}
