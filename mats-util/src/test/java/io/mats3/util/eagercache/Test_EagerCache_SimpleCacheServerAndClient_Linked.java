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
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.Test_EagerCache_SimpleCacheServerAndClient.CustomerDTOCacheDataCallback;

/**
 * Variant of {@link Test_EagerCache_SimpleCacheServerAndClient} that uses the concept of linked client to server,
 * thereby alleviating the need to create two separate MatsFactories for the server and client (The reason for this is
 * that the MatsFactory does not allow two MatsEndpoints with the same endpointId, which would be the case if you had
 * both a server and a client using the same MatsFactory, since they both need to listen to the broadcast topic).
 * <p>
 * <b>This is only relevant for cache server and client development and testing!</b> In production, you would have the
 * cache server and client in separate services, and thus they would have separate MatsFactories.
 *
 * @author Endre Stølsvik 2024-10-13 20:39 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_EagerCache_SimpleCacheServerAndClient_Linked {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_SimpleCacheServerAndClient_Linked.class);

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

        // :: Create a single MatsFactory - the client will be linked to the server.
        MatsFactory matsFactory = MatsTestFactory.create();

        // :: Create the CacheServer.
        MatsEagerCacheServer cacheServer = MatsEagerCacheServer.create(matsFactory,
                "Customers", CustomerTransferDTO.class,
                () -> new CustomerDTOCacheDataCallback(sourceData));

        // Adjust delays for testing
        CommonSetup_TwoServers_TwoClients.adjustDelaysForTest(cacheServer);

        // :: Create the CacheClient.
        MatsEagerCacheClient<DataCarrier> cacheClient = MatsEagerCacheClient.create(matsFactory,
                "Customers", CustomerTransferDTO.class, DataCarrier::new);
        cacheClient.setSizeCutover(sizeCutover);

        // .. testing that the initial population is done.
        MatsTestBarrier cacheClientBarrier = new MatsTestBarrier();
        cacheClient.addAfterInitialPopulationTask(() -> {
            log.info("Initial population done!");
            cacheClientBarrier.resolve();
        });

        // ## ACT:

        log.info("\n\n######### Starting the CacheServer and CacheClient, linking the Client to the Server\n\n");

        cacheServer.start();
        cacheClient.linkToServer(cacheServer);
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
        matsFactory.close();
    }
}
