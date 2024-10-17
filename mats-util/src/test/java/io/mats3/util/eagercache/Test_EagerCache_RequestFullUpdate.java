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
            serversClients.cacheServer1.scheduleFullUpdate();
            serversClients.cacheServer2.scheduleFullUpdate();
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

        Assert.assertFalse(cacheUpdatedO.isPresent());

        // ---------------------------
        // ## ARRANGE 2:

        // Wait for the update we know is coming.
        serversClients.waitForClientsUpdate();

        // ## ACT 2:

        cacheUpdatedO = serversClients.cacheClient1.requestFullUpdate(30_000);

        // ## ASSERT 2:

        Assert.assertTrue(cacheUpdatedO.isPresent());

        CacheUpdated updated = cacheUpdatedO.get();
        Assert.assertNull(updated.getMetadata());
        Assert.assertTrue(updated.isFullUpdate());
        Assert.assertEquals(15, updated.getDataCount());
        Assert.assertTrue(updated.getCompressedSize() > 8_000);
        Assert.assertTrue(updated.getDecompressedSize() > 25_000);

        Assert.assertTrue("We should have Round Trip Time, as we were the initiator, with no"
                + " other concurrent requests.", updated.getRoundTripTimeMillis().isPresent());
        Assert.assertTrue("Round Trip Time should take at least some time.",
                updated.getRoundTripTimeMillis().getAsDouble() > 0.1);

        // ## FINAL ASSERT:

        // Assert that the CacheClients were updated, and that they now have the new data.
        serversClients.assertUpdateAndConsistency(true, customerCount + newDataCount);

        // Assert that we've only gotten one extra update total for each cache (initial + requested full update)
        Assert.assertEquals(3, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(3, serversClients.cacheClient2_updateCount.get());

        // Shutdown
        serversClients.close();
    }

}
