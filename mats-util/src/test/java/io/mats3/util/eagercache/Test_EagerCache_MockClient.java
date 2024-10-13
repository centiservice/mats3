package io.mats3.util.eagercache;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.test.MatsTestBarrier;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientMock;

/**
 * Test of the {@link MatsEagerCacheClientMock} solution.
 */
public class Test_EagerCache_MockClient {

    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_MockClient.class);

    @Test
    public void mockClient() {
        // :: ARRANGE

        MatsEagerCacheClientMock<DataCarrier> mockClient = MatsEagerCacheClient.mock("Customers");

        // Set mock data, using direct method:
        CustomerData mockSource = DummyFinancialService.createRandomReplyDTO(1234L, 10);
        DataCarrier mockData = new DataCarrier(mockSource.customers);
        mockClient.setMockData(mockData);

        // :: ARRANGE MORE - add listeners and barriers for asserting

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

        // :: ACT

        mockClient.start();

        DataCarrier dataCarrier = mockClient.get();
        log.info("######### Got the data 1! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT

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

        // :: ARRANGE 2, for the next test using supplier

        // Now use the supplier method to set the data
        mockSource = DummyFinancialService.createRandomReplyDTO(21L, 10);
        DataCarrier mockData2 = new DataCarrier(mockSource.customers);

        MatsTestBarrier dataBarrier = new MatsTestBarrier();
        mockClient.setMockDataSupplier(() -> {
            dataBarrier.resolve();
            return mockData2;
        });

        // Reset the other latches
        initialPopulationBarrier.reset();
        cacheUpdatedBarrier.reset();

        // :: ACT 2

        // "Request full update" to trigger update listeners
        mockClient.requestFullUpdate();

        // Get the data again, triggering the supplier
        dataCarrier = mockClient.get();
        log.info("######### Got the data 2! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT 2

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

        // :: ARRANGE 3, to check the CacheUpdate mocking

        // Set the CacheUpdate mock
        MatsTestBarrier cacheUpdateBarrier = new MatsTestBarrier();
        mockClient.setMockCacheUpdatedSupplier(() -> {
            var ret = new CacheUpdated() {
                @Override
                public boolean isFullUpdate() {
                    return false;
                }

                @Override
                public int getDataCount() {
                    return 0;
                }

                @Override
                public long getCompressedSize() {
                    return 0;
                }

                @Override
                public long getUncompressedSize() {
                    return 0;
                }

                @Override
                public String getMetadata() {
                    return "";
                }

                @Override
                public double getUpdateDurationMillis() {
                    return 0;
                }
            };

            cacheUpdateBarrier.resolve(ret);
            return ret;
        });

        // Reset Barriers
        initialPopulationBarrier.reset();
        cacheUpdatedBarrier.reset();
        dataBarrier.reset();

        // :: ACT 3

        // "Request full update" to trigger update listeners
        mockClient.requestFullUpdate();

        // Get the data again, triggering the supplier
        dataCarrier = mockClient.get();
        log.info("######### Got the data 3! Size:[" + dataCarrier.customers.size() + "]");

        // :: ASSERT 3

        // Assert that the CacheUpdate mock was invoked
        CacheUpdated cacheUpdatedMocked = cacheUpdateBarrier.await();

        // Assert that the data callback was invoked
        dataBarrier.await();

        // Assert data is the same
        Assert.assertSame(mockData2, dataCarrier);

        // Assert that the CacheUpdated listener was called
        cacheUpdated = cacheUpdatedBarrier.await();

        // Assert that the CacheUpdated was as expected
        Assert.assertSame(cacheUpdatedMocked, cacheUpdated);
        Assert.assertFalse(cacheUpdated.isFullUpdate());

        // Assert that we've queried the data thrice now
        Assert.assertEquals(3, mockClient.getCacheClientInformation().getNumberOfAccesses());
    }
}
