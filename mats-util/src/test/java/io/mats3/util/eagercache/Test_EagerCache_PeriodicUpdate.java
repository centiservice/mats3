package io.mats3.util.eagercache;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test of the periodic full update feature of the {@link MatsEagerCacheServer} - it is here set to 3 seconds, which is
 * absurdly low, but a test taking the default 111 minutes would be a tad harsh. Make note: The intention is that the
 * cache system should be push-on-changes. The periodic full update is just meant as a safety net, and should preferably
 * not be the main way of updating the cache.
 */
public class Test_EagerCache_PeriodicUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_PartialUpdate.class);

    @Test
    public void periodicUpdate() throws InterruptedException, IOException {
        // ## ARRANGE:

        int originalCount = 10;
        CommonSetup_TwoServers_TwoClients serversClients = new CommonSetup_TwoServers_TwoClients(originalCount, (
                server) -> {
            server.setPeriodicFullUpdateIntervalMinutes(0.05d); // 3 seconds. (Should be *hours* in production!)
        });

        serversClients.reset();

        // ## ACT

        // The "act" is just to wait for the periodic updates to happen.
        serversClients.waitForClientsUpdate();

        // ## ASSERT:

        serversClients.assertUpdateAndConsistency(true, originalCount);

        // Assert that we've only gotten two updates for each cache, one initial, and one periodic.
        Assert.assertEquals(2, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(2, serversClients.cacheClient2_updateCount.get());

        // Shutdown
        serversClients.close();
    }
}