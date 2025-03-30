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

import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestBarrier;
import io.mats3.test.MatsTestFactory;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheServer.CacheDataCallback;

/**
 * Simple/basic test of the {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient}: A single server and a single
 * client, where the server has data, and the client is then expected to get the same data.
 */
public class Test_EagerCache_SimpleCacheServerAndClient {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_SimpleCacheServerAndClient.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void simpleServerAndClient_Small() throws JsonProcessingException {
        simpleServerAndClient(0);
    }

    @Test
    public void simpleServerAndClient_Large() throws JsonProcessingException {
        simpleServerAndClient(Integer.MAX_VALUE);
    }

    public void simpleServerAndClient(int sizeCutover) throws JsonProcessingException {
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
        MatsEagerCacheServer cacheServer = MatsEagerCacheServer.create(serverMatsFactory,
                "Customers", CustomerTransferDTO.class,
                () -> new CustomerDTOCacheDataCallback(sourceData));

        // Adjust delays for testing
        CommonSetup_TwoServers_TwoClients.adjustDelaysForTest(cacheServer);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient = MatsEagerCacheClient.create(clientMatsFactory,
                "Customers", CustomerTransferDTO.class, DataCarrier::new);
        cacheClient.setSizeCutover(sizeCutover);

        // .. testing that the initial population is done.
        MatsTestBarrier cacheClientBarrier = new MatsTestBarrier();
        cacheClient.addAfterInitialPopulationTask(() -> {
            log.info("Initial population done!");
            cacheClientBarrier.resolve();
        });

        // ## ACT:

        log.info("\n\n######### Starting the CacheServer and CacheClient.\n\n");

        cacheServer.startAndWaitForReceiving();
        cacheClient.start();

        log.info("\n\n######### Waiting for initial population to be done.\n\n");
        cacheClientBarrier.await();

        log.info("\n\n######### Barrier passed!\n\n");

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
        cacheServer.close();
        cacheClient.close();
        serverMatsFactory.close();
        clientMatsFactory.close();
        matsTestBroker.close();
    }

    static class CustomerDTOCacheDataCallback implements CacheDataCallback<CustomerTransferDTO> {
        private final CustomerData _sourceData;

        public CustomerDTOCacheDataCallback(CustomerData sourceData) {
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
        public void provideSourceData(Consumer<CustomerTransferDTO> consumer) {
            _sourceData.customers.stream().map(CustomerTransferDTO::fromCustomerDTO).forEach(consumer);
        }
    }
}
