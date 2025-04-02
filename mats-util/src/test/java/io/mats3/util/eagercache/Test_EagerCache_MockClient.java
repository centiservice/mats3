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

import java.util.OptionalDouble;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.MatsTestBarrier;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientImpl.CacheUpdatedImpl;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientMock;

/**
 * Test of the {@link MatsEagerCacheClientMock} solution: The Client mock itself, setting of data "directly", setting of
 * data via supplier, and mocking of the CacheUpdated event for cache update listeners.
 */
public class Test_EagerCache_MockClient {

    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_MockClient.class);

    @Test
    public void mockClient() {
        // :: GLOBAL ARRANGE

        MatsEagerCacheClientMock<DataCarrier> mockClient = MatsEagerCacheClient.mock("Customers");

        // Initial population listener/task
        MatsTestBarrier initialPopulationBarrier = new MatsTestBarrier();
        mockClient.addAfterInitialPopulationTask(() -> {
            log.info("## !! Initial population done!");
            initialPopulationBarrier.resolve();
        });

        // Update listener
        MatsTestBarrier cacheUpdatedBarrier = new MatsTestBarrier();
        mockClient.addCacheUpdatedListener(update -> {
            log.info("## Cache updated: " + update);
            cacheUpdatedBarrier.resolve(update);
        });

        // ===============================================

        // :: ARRANGE #1 - using direct data.

        // Set mock data, using direct method:
        CustomerData mockSource = DummyFinancialService.createRandomReplyDTO(1234L, 10);
        DataCarrier mockData = new DataCarrier(mockSource.customers);
        mockClient.setMockData(mockData);

        // :: ACT #1

        mockClient.start();

        DataCarrier dataCarrier = mockClient.get();
        log.info("######### Got the data 1! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT #1

        // Assert data is the same
        Assert.assertSame(mockData, dataCarrier);

        // Assert that the initial population was done
        initialPopulationBarrier.await();

        // Assert that the CacheUpdated listener was called
        CacheUpdated cacheUpdated = cacheUpdatedBarrier.await();

        // Assert that the CacheUpdated was as expected
        Assert.assertNotNull(cacheUpdated);
        Assert.assertTrue(cacheUpdated.isFullUpdate());

        // Assert that we've queried the data only once
        Assert.assertEquals(1, mockClient.getCacheClientInformation().getNumberOfAccesses());

        // ===============================================

        // :: ARRANGE #2 - using data supplier

        // Now use the supplier method to set the data
        mockSource = DummyFinancialService.createRandomReplyDTO(21L, 10);
        DataCarrier mockData2 = new DataCarrier(mockSource.customers);

        MatsTestBarrier dataBarrier = new MatsTestBarrier();
        mockClient.setMockDataSupplier(() -> {
            dataBarrier.resolve();
            return mockData2;
        });

        // Reset the other barriers.
        initialPopulationBarrier.reset();
        cacheUpdatedBarrier.reset();

        // :: ACT #2

        // "Request full update" to trigger update listeners
        mockClient.requestFullUpdate(-1);

        // Get the data again, triggering the supplier
        dataCarrier = mockClient.get();
        log.info("######### Got the data 2! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT #2

        // Assert that the data callback was invoked
        dataBarrier.await();

        // Assert data is the same
        Assert.assertSame(mockData2, dataCarrier);

        // Assert that the initial population was NOT done again!
        initialPopulationBarrier.awaitNoResult();

        // Assert that the CacheUpdated listener was called
        cacheUpdated = cacheUpdatedBarrier.await();

        // Assert that the CacheUpdated was as expected
        Assert.assertNotNull(cacheUpdated);
        Assert.assertTrue(cacheUpdated.isFullUpdate());

        // Assert that we've queried the data twice now
        Assert.assertEquals(2, mockClient.getCacheClientInformation().getNumberOfAccesses());

        // ===============================================

        // :: ARRANGE #3 - check the CacheUpdate mocking

        // Set the CacheUpdate mock
        MatsTestBarrier cacheUpdatedMockedBarrier = new MatsTestBarrier();
        mockClient.setMockCacheUpdatedSupplier(() -> {
            var ret = new CacheUpdatedImpl(true, false, 10, 20, 30, "MetaMock", OptionalDouble.empty());
            cacheUpdatedMockedBarrier.resolve(ret);
            return ret;
        });

        // Reset Barriers
        initialPopulationBarrier.reset();
        cacheUpdatedBarrier.reset();
        dataBarrier.reset();

        // :: ACT #3

        // "Request full update" to trigger update listeners
        mockClient.requestFullUpdate(-1);

        // Get the data again, triggering the data supplier from ARRANGE 2
        dataCarrier = mockClient.get();
        log.info("######### Got the data 3! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT #3

        // Assert that the CacheUpdate supplier mock was invoked
        CacheUpdated cacheUpdatedMocked = cacheUpdatedMockedBarrier.await();

        // Assert that the CacheUpdated listener was called (the listener invoked, which should get the above mocked)
        cacheUpdated = cacheUpdatedBarrier.await();

        // Assert that the CacheUpdated the listener got is the same as the one we supplied
        Assert.assertSame(cacheUpdatedMocked, cacheUpdated);

        // Assert that the CacheUpdated was as designed, pretty much just to show how this works
        Assert.assertFalse(cacheUpdated.isFullUpdate());
        Assert.assertEquals(10, cacheUpdated.getCompressedSize());
        Assert.assertEquals(20, cacheUpdated.getDecompressedSize());
        Assert.assertEquals(30, cacheUpdated.getDataCount());
        Assert.assertEquals("MetaMock", cacheUpdated.getMetadata());
        Assert.assertFalse(cacheUpdated.getRoundTripTimeMillis().isPresent());

        // Assert that the data callback was invoked
        dataBarrier.await();

        // Assert data is the same
        Assert.assertSame(mockData2, dataCarrier);

        // Assert that we've queried the data thrice now
        Assert.assertEquals(3, mockClient.getCacheClientInformation().getNumberOfAccesses());
    }
}
