package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectReader;

import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerDTO;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.compression.ByteArrayDeflaterOutputStreamWithStats;
import io.mats3.util.compression.InflaterInputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheReceivedPartialData;
import io.mats3.util.eagercache.MatsEagerCacheServer.SiblingCommand;

/**
 * Tests the {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient} with partial updates. Quite involved test,
 * read the comments in the code.
 */
public class Test_EagerCache_PartialUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_PartialUpdate.class);

    @Test
    public void partialUpdate() throws InterruptedException, IOException {
        // ## ARRANGE:

        int originalCount = 10;
        CommonSetup_TwoServers_TwoClients serversClients = new CommonSetup_TwoServers_TwoClients(originalCount);

        // ## ARRANGE EVEN MORE!

        // :: Make a partial update mapper for the CacheClients
        Function<CacheReceivedPartialData<CustomerTransmitDTO, DataCarrier>, DataCarrier> partialUpdateMapper = (
                partialUpdate) -> {
            // Pick out AND COPY the existing data structures from the DATA element from the cache client
            DataCarrier previousDataCarrier = partialUpdate.getPreviousData();
            ArrayList<CustomerDTO> ret = new ArrayList<>(previousDataCarrier.customers);
            // Go through the received data, and update the existing data with the new data - or add new data.
            partialUpdate.getReceivedDataStream().forEach((newCustomer) -> {
                // Find the existing customer in the cache, and replace it with the new one.
                boolean found = false;
                for (int i = 0; i < ret.size(); i++) {
                    if (ret.get(i).customerId.equals(newCustomer.customerId)) {
                        ret.set(i, newCustomer.toCustomerDTO());
                        found = true;
                        break;
                    }
                }
                // ?: Did we find the customer in the cache?
                if (!found) {
                    // -> No, we did not find it, so it is a new customer, and we add it.
                    ret.add(newCustomer.toCustomerDTO());
                }
            });
            return new DataCarrier(ret);
        };

        // Use this mapper for both Cache Clients (it is stateless, so we can use the same for both).
        serversClients.cacheClient1.setPartialUpdateMapper(partialUpdateMapper);
        serversClients.cacheClient2.setPartialUpdateMapper(partialUpdateMapper);

        // :: Add a SiblingCommand consumer to the CacheServers, which will apply the partial update to CacheServer's
        // source data, and then propagate the partial update to the CacheClients.
        // This is NOT stateless, so we need to create two different instances - one for each CacheServer.
        serversClients.cacheServer1.addSiblingCommandListener(new SiblingUpdateCommand(serversClients.customerDataReader,
                serversClients.cacheServer1, serversClients.sourceData1));
        serversClients.cacheServer2.addSiblingCommandListener(new SiblingUpdateCommand(serversClients.customerDataReader,
                serversClients.cacheServer2, serversClients.sourceData2));

        // Make ready for the new flurry of updates.
        serversClients.reset();

        // ## ACT:

        // :: CREATE PARTIAL UPDATE

        /*
         * What we'll "emulate" here is that a backoffice user on the service instance having CacheServer1 has updated
         * three customers, and added new customer - and we now want to propagate this out. In this scenario, the
         * customer data resides in memory. We thus first need to ensure that this new data is updated on all
         * CacheServers, and then we need to propagate the update to the CacheClients.
         */

        // Make some new data, that we will use a few entries of as partial update.
        // Note that this is a bit of a hack: We'll just create some new CustomerDTOs, and then we'll replace the
        // customerId of these new ones with the customerId of the existing customers we want to update (plus leave
        // one as is, to emulate a new customer).

        // Note: Different seed, so it will be different data.
        int newDataCount = 4;
        CustomerData newData = DummyFinancialService.createRandomReplyDTO(93054050L, newDataCount);

        // So now we're taking the UUIDs of the customers we want to update, and put them into the partial update data.
        // We're now *emulating* that we have gotten new data for these 3 customers + a new customer.
        // Note: This partial update was done by a user on the CacheServer1!
        var partialUpdateData = new ArrayList<>(newData.customers);
        partialUpdateData.get(0).customerId = serversClients.sourceData1.customers.get(2).customerId;
        partialUpdateData.get(1).customerId = serversClients.sourceData1.customers.get(5).customerId;
        partialUpdateData.get(2).customerId = serversClients.sourceData1.customers.get(8).customerId;
        // .. The fourth is "a new customer", so we just its customerId as is.

        // This partial Update must now then be applied to our servers' sourceData sets - but we will do it as a
        // SiblingCommand so that all Cache Servers have the same source data.

        // Making a CustomerData from the partial update data, and then compressing it.
        CustomerData carrier = new CustomerData(partialUpdateData);
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
        serversClients.customerDataWriter.writeValue(out, carrier);
        byte[] partialUpdateDataCompressed = out.toByteArray();

        // :: SEND SIBLING COMMAND

        // Sending the SiblingCommand from CacheServer1 - which both CacheServers will receive.
        // The SiblingCommand listener will also send the partial update to the CacheClients (only the one that
        // initiated the sibling command will do that), which will then apply the partial update to their cached data.
        serversClients.cacheServer1.sendSiblingCommand(SiblingUpdateCommand.COMMAND, null, partialUpdateDataCompressed);

        // Now we wait! First the two CacheServers will receive the SiblingCommand, and will then apply the partial
        // update to their source data, and then one of them (the one that originated the SiblingCommand, i.e.
        // CacheServer1) will propagate the partial update to the CacheClients - which is what we're waiting for.
        serversClients.waitForClientsUpdate();

        // ## ASSERT:

        // Assert that we now have one more customer in the CacheServers' data
        Assert.assertEquals("The CacheServer's data should now have one more customer",
                originalCount + 1, serversClients.sourceData1.customers.size());
        Assert.assertEquals("The CacheServer's data should now have one more customer",
                originalCount + 1, serversClients.sourceData2.customers.size());

        // Assert that the CacheClients were updated, and that they now have the new data.
        serversClients.assertUpdateAndConsistency(false, newDataCount);

        // Assert that we've only gotten two updates for each cache - one full, and one partial.
        // (Even though both Clients requested full update at boot, and the partial update was applied to both Servers
        // - but only one of them propagated the partial update to the Clients)
        Assert.assertEquals(2, serversClients.cacheClient1_updateCount.get());
        Assert.assertEquals(2, serversClients.cacheClient2_updateCount.get());

        // Shutdown
        serversClients.close();
    }

    static class SiblingUpdateCommand implements Consumer<SiblingCommand> {
        private final MatsEagerCacheServer _cacheServer;
        private final ObjectReader _replyReader;
        private final CustomerData _sourceData;

        public static final String COMMAND = "CustomerPartialUpdate";

        public SiblingUpdateCommand(ObjectReader replyReader, MatsEagerCacheServer cacheServer,
                CustomerData sourceData) {
            _cacheServer = cacheServer;
            _replyReader = replyReader;
            _sourceData = sourceData;
        }

        @Override
        public void accept(SiblingCommand siblingCommand) {
            log.info("Received SiblingCommand! [" + siblingCommand + "]");
            if (siblingCommand.getCommand().equals(COMMAND)) {
                Set<String> customerIdsUpdated = new HashSet<>();
                synchronized (_sourceData) {
                    log.info("Applying partial update to source data.");
                    try {
                        // Decompress and deserialize the partial update data
                        InflaterInputStreamWithStats in = new InflaterInputStreamWithStats(siblingCommand
                                .getBinaryData());
                        CustomerData partialUpdate = _replyReader.readValue(in);
                        // Apply the partial update to the source data.
                        for (CustomerDTO partialUpdateCustomer : partialUpdate.customers) {
                            customerIdsUpdated.add(partialUpdateCustomer.customerId);
                            boolean found = false;
                            for (int i = 0; i < _sourceData.customers.size(); i++) {
                                if (_sourceData.customers.get(i).customerId.equals(partialUpdateCustomer.customerId)) {
                                    _sourceData.customers.set(i, partialUpdateCustomer);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                _sourceData.customers.add(partialUpdateCustomer);
                            }
                        }
                    }
                    catch (IOException e) {
                        throw new RuntimeException("Could not deserialize the partial update data.", e);
                    }
                }

                // NOTE: We EXIT the synchronized block here, as we've now updated the source data, and it is IMPERATIVE
                // that we don't hold sync when INVOKING the partial update method on the CacheServer! (We must lock
                // inside)

                // ?: If we are the originator of the command, we should send a partial update to all
                if (siblingCommand.commandOriginatedOnThisInstance()) {
                    _cacheServer.sendPartialUpdate(consumer -> {
                        // Only here we synchronize on the source data, as we're reading it.
                        synchronized (_sourceData) {
                            // Notice how we read from the source data, and not the update data
                            // This ensures that if someone manages to sneak in some update between the exit of synch
                            // above, and going into the synch here, we'll still send the newest data.
                            for (CustomerDTO customer : _sourceData.customers) {
                                if (customerIdsUpdated.contains(customer.customerId)) {
                                    consumer.accept(customer);
                                }
                            }
                        }
                    });
                }
            }
        }
    }
}
