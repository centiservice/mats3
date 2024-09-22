package io.mats3.util.eagercache;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheSourceData;

public class Test_EagerCache {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache.class);

    @Test
    public void test() throws InterruptedException, JsonProcessingException {
        run();
    }

    public void run() throws InterruptedException, JsonProcessingException {
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory = MatsTestFactory.createWithBroker(matsTestBroker);

        ReplyDTO sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 10);

        ObjectMapper objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
        ObjectWriter replyWriter = objectMapper.writerFor(ReplyDTO.class);

        String serializedSourceData = replyWriter.writeValueAsString(sourceData);

        MatsEagerCacheServer cacheServer = new MatsEagerCacheServer(serverMatsFactory, "Customers",
                CustomerTransferDTO.class,
                () -> new CacheSourceData<>() {
                    @Override
                    public int getDataCount() {
                        return sourceData.customers.size();
                    }

                    @Override
                    public String getMetadata() {
                        return "Dummy Data!";
                    }

                    @Override
                    public Stream<CustomerDTO> getSourceDataStream() {
                        return sourceData.customers.stream();
                    }
                }, CustomerTransferDTO::fromCustomerDTO, 1);

        MatsEagerCacheClient<DataCarrier> cacheClient = new MatsEagerCacheClient<>(
                clientMatsFactory,
                "Customers", CustomerTransferDTO.class, receivedData -> {
                    log.info("Got the data! Meta:[" + receivedData.getMetadata() + "], Size:[" + receivedData
                            .getDataCount() + "]");
                    List<CustomerDTO> customers = receivedData.getReceivedDataStream()
                            .map(CustomerTransferDTO::toCustomerDTO)
                            .collect(Collectors.toList());
                    return new DataCarrier(customers);
                }, null);

        CountDownLatch latch = new CountDownLatch(1);

        cacheClient.addOnInitialPopulationTask(() -> {
            log.info("Initial population done!");
            latch.countDown();
        });

        log.info("\n\n######### Starting the CacheServer and CacheClient.\n\n");

        cacheServer.start();
        cacheClient.start();

        log.info("\n\n######### Waiting for initial population to be done.\n\n");
        latch.await(10, TimeUnit.SECONDS);

        log.info("\n\n######### Latched!\n\n");

        DataCarrier dataCarrier = cacheClient.get();
        log.info("######### Got the data! Size:[" + dataCarrier.customers.size() + "]");

        ReplyDTO cacheData = new ReplyDTO();
        cacheData.customers = dataCarrier.customers;

        String serializedReceivedData = replyWriter.writeValueAsString(sourceData);

        Assert.assertEquals("The serialized data should be the same.", serializedSourceData, serializedReceivedData);

        // Shutdown
        serverMatsFactory.close();
        clientMatsFactory.close();
        matsTestBroker.close();
    }

    public static class DataCarrier {
        public final List<CustomerDTO> customers;

        public DataCarrier(List<CustomerDTO> customers) {
            this.customers = customers;
        }

    }
}
