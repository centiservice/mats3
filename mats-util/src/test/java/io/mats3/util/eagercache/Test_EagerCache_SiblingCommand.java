package io.mats3.util.eagercache;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import io.mats3.util.eagercache.MatsEagerCacheServer.SiblingCommand;

public class Test_EagerCache_SiblingCommand {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_SiblingCommand.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(ReplyDTO.class);

    @Test
    public void run() throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        // Create the source data.
        ReplyDTO sourceData = DummyFinancialService.createRandomReplyDTO(1234L, 1);

        // :: Create the two MatsFactories, representing two different services:
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory serverMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServer.
        MatsEagerCacheServer cacheServer1 = new MatsEagerCacheServer(serverMatsFactory1,
                "Customers", CustomerCacheDTO.class, 1,
                () -> new CustomerDTOCacheSourceDataCallback(sourceData),
                CustomerCacheDTO::fromCustomerDTO);

        MatsEagerCacheServer cacheServer2 = new MatsEagerCacheServer(serverMatsFactory2,
                "Customers", CustomerCacheDTO.class, 1,
                () -> new CustomerDTOCacheSourceDataCallback(sourceData),
                CustomerCacheDTO::fromCustomerDTO);

        CountDownLatch[] latch = new CountDownLatch[1];
        latch[0] = new CountDownLatch(3);

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

        log.info("\n\n######### Starting the CacheServers, waiting for receivive loops.\n\n");

        cacheServer1.start();
        cacheServer2.start();
        cacheServer1._waitForReceiving();
        cacheServer2._waitForReceiving();


        log.info("\n\n######### Sending SiblingCommand from CacheServer 1.\n\n");

        cacheServer1.sendSiblingCommand("Hello, CacheServers siblings!", "Customers", new byte[0]);

        log.info("\n\n######### Waiting for sibling command to be received.\n\n");
        latch[0].await(10, TimeUnit.SECONDS);

        // ## ASSERT:

        // Shutdown
        serverMatsFactory1.close();
        serverMatsFactory2.close();
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
