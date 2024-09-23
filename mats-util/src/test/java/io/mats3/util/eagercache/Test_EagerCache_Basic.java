package io.mats3.util.eagercache;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import io.mats3.util.DummyFinancialService.ReplyDTO;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheReceivedData;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheSourceDataCallback;

/**
 * Tests the {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient}.
 */
public class Test_EagerCache_Basic {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_Basic.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(ReplyDTO.class);

    @Test
    public void run() throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        ReplyDTO sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 10);
        // For comparison on the client side: Serialize the source data.
        String serializedSourceData = _replyWriter.writeValueAsString(sourceData);

        // :: Create the two MatsFactories, representing two different services:
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServer.
        MatsEagerCacheServer cacheServer = new MatsEagerCacheServer(serverMatsFactory,
                "Customers", CustomerCacheDTO.class, 1,
                () -> new CustomerDTOCacheSourceDataCallback(sourceData),
                CustomerCacheDTO::fromCustomerDTO);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient = new MatsEagerCacheClient<>(clientMatsFactory,
                "Customers", CustomerCacheDTO.class,
                DataCarrier::new, null);

        CountDownLatch latch = new CountDownLatch(1);

        // .. testing that the initial population is done.
        cacheClient.addOnInitialPopulationTask(() -> {
            log.info("Initial population done!");
            latch.countDown();
        });

        // ## ACT:

        log.info("\n\n######### Starting the CacheServer and CacheClient.\n\n");

        cacheServer.start();
        cacheClient.start();

        log.info("\n\n######### Waiting for initial population to be done.\n\n");
        latch.await(10, TimeUnit.SECONDS);

        log.info("\n\n######### Latched!\n\n");

        // ## ASSERT:

        DataCarrier dataCarrier = cacheClient.get();
        log.info("######### Got the data! Size:[" + dataCarrier.customers.size() + "]");

        ReplyDTO cacheData = new ReplyDTO();
        cacheData.customers = dataCarrier.customers;

        String serializedCacheData = _replyWriter.writeValueAsString(cacheData);

        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache.", serializedSourceData, serializedCacheData);

        // Shutdown
        serverMatsFactory.close();
        clientMatsFactory.close();
        matsTestBroker.close();
    }

    public static class DataCarrier {
        private static final Logger log = LoggerFactory.getLogger(DataCarrier.class);

        public final List<CustomerDTO> customers;

        DataCarrier(CacheReceivedData<CustomerCacheDTO> receivedData) {
            log.info("Creating DataCarrier! Meta:[" + receivedData.getMetadata()
                    + "], Size:[" + receivedData.getDataCount() + "]");
            customers = receivedData.getReceivedDataStream()
                    .map(CustomerCacheDTO::toCustomerDTO)
                    .collect(Collectors.toList());
        }
    }

    private static class CustomerDTOCacheSourceDataCallback implements CacheSourceDataCallback<CustomerDTO> {
        private final ReplyDTO _sourceData;

        public CustomerDTOCacheSourceDataCallback(ReplyDTO sourceData) {
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
