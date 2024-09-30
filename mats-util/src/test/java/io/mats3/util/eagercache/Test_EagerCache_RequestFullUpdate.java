package io.mats3.util.eagercache;

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

public class Test_EagerCache_RequestFullUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_RequestFullUpdate.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _replyWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void onlyClientSide() throws InterruptedException, JsonProcessingException {
        run(0, 3);
    }

    @Test
    public void onlyServerSide() throws InterruptedException, JsonProcessingException {
        run(3, 0);
    }

    @Test
    public void fromBothServerAndClient() throws InterruptedException, JsonProcessingException {
        run(3, 3);
    }

    private void run(int serverSideCount, int clientSideCount) throws InterruptedException, JsonProcessingException {
        // ## ARRANGE:

        int customerCount = 10;
        CommonSetup_TwoServers_TwoClients serversClients = new CommonSetup_TwoServers_TwoClients(customerCount);

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
            serversClients.cacheClient1.requestFullUpdate();
            serversClients.cacheClient2.requestFullUpdate();
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
}
