package io.mats3.util.eagercache;

import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;

public class Test_EagerCache_RequestFullUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_RequestFullUpdate.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void onlyClientSide_small() throws InterruptedException, JsonProcessingException {
        run(0, 3, 0);
    }

    @Test
    public void onlyServerSide_small() throws InterruptedException, JsonProcessingException {
        run(3, 0, 0);
    }

    @Test
    public void fromBothServerAndClient_small() throws InterruptedException, JsonProcessingException {
        run(3, 3, 0);
    }

    @Test
    public void onlyClientSide_large() throws InterruptedException, JsonProcessingException {
        run(0, 3, Integer.MAX_VALUE);
    }

    @Test
    public void onlyServerSide_large() throws InterruptedException, JsonProcessingException {
        run(3, 0, Integer.MAX_VALUE);
    }

    @Test
    public void fromBothServerAndClient_large() throws InterruptedException, JsonProcessingException {
        run(3, 3, Integer.MAX_VALUE);
    }

    private void run(int serverSideCount, int clientSideCount, int sizeCutover) throws InterruptedException,
            JsonProcessingException {
        // ## ARRANGE:

        int customerCount = 10;
        CommonSetup_TwoServers_TwoClients serversClients = CommonSetup_TwoServers_TwoClients.createWithClientAdjust(
                customerCount, (client) -> client.setSizeCutover(sizeCutover));

        // ## ACT:

        // Make ready for the new flurry of updates.
        serversClients.reset();

        // Update the source data a tad.
        int newDataCount = 5;
        CustomerData newData = DummyFinancialService.createRandomReplyDTO(93054050L, newDataCount);
        serversClients.sourceData1.customers.addAll(newData.customers);
        serversClients.sourceData2.customers.addAll(newData.customers);

        // From both the client and server, request a full update - do it multiple times, from all of them.
        // (This should still just result in one full update sent in total)
        for (int i = 0; i < serverSideCount; i++) {
            serversClients.cacheServer1.initiateFullUpdate(-1);
            serversClients.cacheServer2.initiateFullUpdate(-1);
        }
        for (int i = 0; i < clientSideCount; i++) {
            serversClients.cacheClient1.requestFullUpdate(-1);
            serversClients.cacheClient2.requestFullUpdate(-1);
        }

        // Wait for the singular update.
        serversClients.waitForClientsUpdate();

        // ## ASSERT:

        // Assert that the CacheClients were updated, and that they now have the new data.
        serversClients.assertUpdateAndConsistency(true, customerCount + newDataCount);

        // Assert that we've only gotten one extra update total for each cache (initial + requested full update)
        Assert.assertEquals(2, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(2, serversClients.cacheClient2_updateCount.get());

        // Shutdown
        serversClients.close();
    }

    @Test
    public void blockingClientRequest() throws InterruptedException,
            JsonProcessingException {
        // ## ARRANGE BASE:

        int customerCount = 10;
        CommonSetup_TwoServers_TwoClients serversClients = CommonSetup_TwoServers_TwoClients.create(customerCount);

        // Make ready for the new flurry of updates.
        serversClients.reset();

        // ---------------------------
        // ## ARRANGE 1:

        // Update the source data a tad.
        int newDataCount = 5;
        CustomerData newData = DummyFinancialService.createRandomReplyDTO(93054050L, newDataCount);
        serversClients.sourceData1.customers.addAll(newData.customers);
        serversClients.sourceData2.customers.addAll(newData.customers);

        // ## ACT 1:

        // This should NOT return, as it is too fast
        Optional<CacheUpdated> cacheUpdatedO = serversClients.cacheClient1.requestFullUpdate(1);

        // ## ASSERT 1:

        // Assert that it is not present, since we requested a blocking update but with very short timeout.
        Assert.assertFalse(cacheUpdatedO.isPresent());

        // Now wait for the update to come in.
        serversClients.waitForClientsUpdate();

        // Assert that we've only gotten one extra update total for each cache (initial + requested full update)
        Assert.assertEquals(2, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(2, serversClients.cacheClient2_updateCount.get());

        // ---------------------------
        // ## ARRANGE 2:

        serversClients.reset();

        // ## ACT 2:

        // This should return, as 30 seconds is plenty of time.
        cacheUpdatedO = serversClients.cacheClient1.requestFullUpdate(30_000);

        // ## ASSERT 2:

        // Assert that this is present.
        Assert.assertTrue(cacheUpdatedO.isPresent());

        CacheUpdated cacheUpdated = cacheUpdatedO.get();
        Assert.assertNull(cacheUpdated.getMetadata());
        Assert.assertTrue(cacheUpdated.isFullUpdate());
        Assert.assertEquals(15, cacheUpdated.getDataCount());
        Assert.assertTrue(cacheUpdated.getCompressedSize() > 8_000);
        Assert.assertTrue(cacheUpdated.getDecompressedSize() > 25_000);

        // NOTE: This check failed once on GHA for Mac OS..? (Run #3544, 2024-11-09 14:56)
        Assert.assertTrue("We should have Round Trip Time, as we were the initiator, with no"
                + " other concurrent requests.", cacheUpdated.getRoundTripTimeMillis().isPresent());
        Assert.assertTrue("Round Trip Time should take at least some time.",
                cacheUpdated.getRoundTripTimeMillis().getAsDouble() > 0.1);

        // ## FINAL ASSERT:

        // Wait for both of the clients to get the update (we just did one of them above).
        serversClients.waitForClientsUpdate();

        // Assert that the CacheClients were updated, and that they now have the new data.
        serversClients.assertUpdateAndConsistency(true, customerCount + newDataCount);

        // Assert that we've all together now gotten 3 updates: Initial, first request, second request.
        Assert.assertEquals(3, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(3, serversClients.cacheClient2_updateCount.get());

        // Shutdown
        serversClients.close();
    }

}
