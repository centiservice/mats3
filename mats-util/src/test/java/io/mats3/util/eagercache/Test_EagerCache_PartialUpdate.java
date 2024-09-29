package io.mats3.util.eagercache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.mats3.MatsFactory;
import io.mats3.test.MatsTestFactory;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.DummyFinancialService;
import io.mats3.util.DummyFinancialService.CustomerDTO;
import io.mats3.util.DummyFinancialService.CustomerData;
import io.mats3.util.FieldBasedJacksonMapper;
import io.mats3.util.compression.ByteArrayDeflaterOutputStreamWithStats;
import io.mats3.util.compression.InflaterInputStreamWithStats;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheReceivedPartialData;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheUpdated;
import io.mats3.util.eagercache.MatsEagerCacheServer.SiblingCommand;

/**
 * Tests the {@link MatsEagerCacheServer} and {@link MatsEagerCacheClient} with partial updates. Quite involved test,
 * read the comments in the code.
 */
public class Test_EagerCache_PartialUpdate {
    private static final Logger log = LoggerFactory.getLogger(Test_EagerCache_PartialUpdate.class);

    private final ObjectMapper _objectMapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();
    private final ObjectWriter _customerDataWriter = _objectMapper.writerFor(CustomerData.class);

    @Test
    public void run() throws InterruptedException, IOException {
        // ## ARRANGE:

        int originalCount = 10;

        // Create source data, one set for each server.
        CustomerData sourceData1 = DummyFinancialService.createRandomReplyDTO(1234L, originalCount);
        CustomerData sourceData2 = DummyFinancialService.createRandomReplyDTO(1234L, originalCount);

        // Assert that they are identical
        Assert.assertEquals("The source datas should be the same",
                _customerDataWriter.writeValueAsString(sourceData1), _customerDataWriter.writeValueAsString(
                        sourceData2));

        // :: Create the four MatsFactories, representing two different instances of the server-side service, and
        // two instances of the client-side service.
        MatsTestBroker matsTestBroker = MatsTestBroker.create();
        MatsFactory serverMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory serverMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory1 = MatsTestFactory.createWithBroker(matsTestBroker);
        MatsFactory clientMatsFactory2 = MatsTestFactory.createWithBroker(matsTestBroker);

        // :: Create the CacheServers:
        MatsEagerCacheServer cacheServer1 = new MatsEagerCacheServer(serverMatsFactory1,
                "Customers", CustomerTransmitDTO.class,
                () -> (consumeTo) -> sourceData1.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        MatsEagerCacheServer cacheServer2 = new MatsEagerCacheServer(serverMatsFactory2,
                "Customers", CustomerTransmitDTO.class,
                () -> (consumeTo) -> sourceData2.customers.forEach(consumeTo),
                CustomerTransmitDTO::fromCustomerDTO);

        // :: Create the CacheClients:
        MatsEagerCacheClient<DataCarrier> cacheClient1 = new MatsEagerCacheClient<>(clientMatsFactory1,
                "Customers", CustomerTransmitDTO.class,
                DataCarrier::new);

        MatsEagerCacheClient<DataCarrier> cacheClient2 = new MatsEagerCacheClient<>(clientMatsFactory2,
                "Customers", CustomerTransmitDTO.class,
                DataCarrier::new);

        CountDownLatch[] cacheClient1_latch = new CountDownLatch[1];
        CountDownLatch[] cacheClient2_latch = new CountDownLatch[1];

        CacheUpdated[] cacheClient1_updated = new CacheUpdated[1];
        CacheUpdated[] cacheClient2_updated = new CacheUpdated[1];

        AtomicInteger cacheClient1_updateCount = new AtomicInteger();
        AtomicInteger cacheClient2_updateCount = new AtomicInteger();

        // .. recording the updates - and then latching, so that the test can go to next phase.
        cacheClient1.addCacheUpdatedListener((cacheUpdated) -> {
            log.info("Cache 1 updated! " + (cacheUpdated.isFullUpdate() ? "Full" : "PARTIAL") + " " + cacheUpdated);
            cacheClient1_updateCount.incrementAndGet();
            cacheClient1_updated[0] = cacheUpdated;
            cacheClient1_latch[0].countDown();
        });
        cacheClient2.addCacheUpdatedListener((cacheUpdated) -> {
            log.info("Cache 2 updated! " + (cacheUpdated.isFullUpdate() ? "Full" : "PARTIAL") + " " + cacheUpdated);
            cacheClient2_updateCount.incrementAndGet();
            cacheClient2_updated[0] = cacheUpdated;
            cacheClient2_latch[0].countDown();
        });

        // Changing delays (towards shorter), as we're testing. But also handle CI, which can be dog slow.
        int shortDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE; // On CI: 1 sec
        int longDelay = MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE * 2; // On CI: 2 sec
        cacheServer1._setDelays(shortDelay, longDelay);
        cacheServer2._setDelays(shortDelay, longDelay);

        log.info("\n\n######### Starting the CacheServers and CacheClient, waiting for CacheServers receiving.\n\n");

        cacheClient1_latch[0] = new CountDownLatch(1);
        cacheClient2_latch[0] = new CountDownLatch(1);

        cacheServer1.startAndWaitForReceiving();
        cacheServer2.startAndWaitForReceiving();
        cacheClient1.start();
        cacheClient2.start();

        // .. initial population is automatically done, so we must get past this.

        cacheClient1_latch[0].await(30, TimeUnit.SECONDS);
        cacheClient2_latch[0].await(30, TimeUnit.SECONDS);

        // Serialize the source data, to compare with the CacheClient's data.
        String serializedSourceData = _customerDataWriter.writeValueAsString(sourceData1);

        DataCarrier dataCarrier1 = cacheClient1.get();
        Assert.assertNotNull(dataCarrier1);
        Assert.assertNotNull(cacheClient1_updated[0]);
        Assert.assertTrue(cacheClient1_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData1.customers.size(), cacheClient1_updated[0].getDataCount());
        // Assert serialized data
        CustomerData cacheData = new CustomerData();
        cacheData.customers = dataCarrier1.customers;
        String serializedCacheData = _customerDataWriter.writeValueAsString(cacheData);
        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache.", serializedSourceData, serializedCacheData);

        // ------

        DataCarrier dataCarrier2 = cacheClient1.get();
        Assert.assertNotNull(dataCarrier2);
        Assert.assertNotNull(cacheClient2_updated[0]);
        Assert.assertTrue(cacheClient2_updated[0].isFullUpdate());
        Assert.assertEquals(sourceData2.customers.size(), cacheClient2_updated[0].getDataCount());

        // Assert serialized data
        cacheData = new CustomerData();
        cacheData.customers = dataCarrier2.customers;
        serializedCacheData = _customerDataWriter.writeValueAsString(cacheData);
        Assert.assertEquals("The serialized data should be the same from source, via server-to-client,"
                + " and from cache.", serializedSourceData, serializedCacheData);

        // Assert that we've only gotten one update for each cache (even though both of them requested full update)
        Assert.assertEquals(1, cacheClient1_updateCount.get());
        Assert.assertEquals(1, cacheClient2_updateCount.get());

        // ## ARRANGE EVEN MORE!

        // :: Make a partial update mapper for the CacheClients
        Function<CacheReceivedPartialData<CustomerTransmitDTO, DataCarrier>, DataCarrier> partialUpdateMapper = (
                partialUpdate) -> {
            // Pick out AND COPY the existing data structures from the DATA element from the cache client
            DataCarrier previousDataCarrier = partialUpdate.getPreviousData();
            ArrayList<CustomerDTO> result = new ArrayList<>(previousDataCarrier.customers);
            // Go through the received data, and update the existing data with the new data - or add new data.
            partialUpdate.getReceivedDataStream().forEach((newCustomer) -> {
                // Find the existing customer in the cache, and replace it with the new one.
                boolean found = false;
                for (int i = 0; i < result.size(); i++) {
                    if (result.get(i).customerId.equals(newCustomer.customerId)) {
                        result.set(i, newCustomer.toCustomerDTO());
                        found = true;
                        break;
                    }
                }
                // ?: Did we find the customer in the cache?
                if (!found) {
                    // -> No, we did not find it, so it is a new customer, and we add it.
                    result.add(newCustomer.toCustomerDTO());
                }
            });
            return new DataCarrier(result);
        };

        // Use this mapper for both Cache Clients (it is stateless, so we can use the same for both).
        cacheClient1.setPartialUpdateMapper(partialUpdateMapper);
        cacheClient2.setPartialUpdateMapper(partialUpdateMapper);

        // :: Add a SiblingCommand consumer to the CacheServers, which will apply the partial update to CacheServer's
        // source data, and then propagate the partial update to the CacheClients.
        ObjectReader replyReader = _objectMapper.readerFor(CustomerData.class);
        // This is NOT stateless, so we need to create two different instances - one for each CacheServer.
        cacheServer1.addSiblingCommandListener(new SiblingUpdateCommand(replyReader, cacheServer1, sourceData1));
        cacheServer2.addSiblingCommandListener(new SiblingUpdateCommand(replyReader, cacheServer2, sourceData2));

        // Make ready for the new flurry of updates.
        cacheClient1_updated[0] = null;
        cacheClient2_updated[0] = null;
        cacheClient1_latch[0] = new CountDownLatch(1);
        cacheClient2_latch[0] = new CountDownLatch(1);

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
        CustomerData newData = DummyFinancialService.createRandomReplyDTO(93054050L, 4);

        // So now we're taking the UUIDs of the customers we want to update, and put them into the partial update data.
        // We're now *emulating* that we have gotten new data for these 3 customers + a new customer.
        // Note: This partial update was done by a user on the CacheServer1!
        var partialUpdateData = new ArrayList<>(newData.customers);
        partialUpdateData.get(0).customerId = sourceData1.customers.get(2).customerId;
        partialUpdateData.get(1).customerId = sourceData1.customers.get(5).customerId;
        partialUpdateData.get(2).customerId = sourceData1.customers.get(8).customerId;
        // .. The fourth is "a new customer", so we just its customerId as is.

        // This partial Update must now then be applied to our servers' sourceData sets - but we will do it as a
        // SiblingCommand so that all Cache Servers have the same source data.

        // Making a CustomerData from the partial update data, and then compressing it.
        CustomerData carrier = new CustomerData(partialUpdateData);
        ByteArrayDeflaterOutputStreamWithStats out = new ByteArrayDeflaterOutputStreamWithStats();
        _customerDataWriter.writeValue(out, carrier);
        byte[] partialUpdateDataCompressed = out.toByteArray();

        // :: SEND SIBLING COMMAND

        // Sending the SiblingCommand from CacheServer1 - which both CacheServers will receive.
        // The SiblingCommand listener will also send the partial update to the CacheClients (only the one that
        // initiated the sibling command will do that), which will then apply the partial update to their cached data.
        cacheServer1.sendSiblingCommand(SiblingUpdateCommand.COMMAND, null, partialUpdateDataCompressed);

        // Now we wait! First the two CacheServers will receive the SiblingCommand, and will then apply the partial
        // update to their source data, and then one of them (the one that originated the SiblingCommand, i.e.
        // CacheServer1) will propagate the partial update to the CacheClients - which is what we're waiting for.
        cacheClient1_latch[0].await(30, TimeUnit.SECONDS);
        cacheClient2_latch[0].await(30, TimeUnit.SECONDS);

        // ## ASSERT:

        // Assert that we've only gotten two updates for each cache - one full, and one partial.
        // (Even though both Clients requested full update at boot, and the partial update was applied to both Servers
        // - but only one of them propagated the partial update to the Clients)
        Assert.assertEquals(2, cacheClient1_updateCount.get());
        Assert.assertEquals(2, cacheClient2_updateCount.get());

        // Assert that we now have one more customer in the CacheServers' data
        Assert.assertEquals("The CacheServer's data should now have one more customer",
                originalCount + 1, sourceData1.customers.size());
        Assert.assertEquals("The CacheServer's data should now have one more customer",
                originalCount + 1, sourceData2.customers.size());

        // Assert that both CacheServers have identical data.
        Assert.assertEquals("The two CacheServer's data should be the same",
                _customerDataWriter.writeValueAsString(sourceData1), _customerDataWriter.writeValueAsString(
                        sourceData2));

        // Assert that the two CacheClients have identical data.
        CustomerData cacheData1 = new CustomerData(cacheClient1.get().customers);
        CustomerData cacheData2 = new CustomerData(cacheClient2.get().customers);
        Assert.assertEquals("The two CacheClient's data should be the same",
                _customerDataWriter.writeValueAsString(cacheData1), _customerDataWriter.writeValueAsString(cacheData2));

        // Assert that the CacheClient's data is the same as the CacheServer's data.
        Assert.assertEquals("The CacheClient's data should be the same as the CacheServer's data",
                _customerDataWriter.writeValueAsString(sourceData1), _customerDataWriter.writeValueAsString(
                        cacheData1));

        // Shutdown
        cacheServer1.close();
        cacheServer2.close();
        cacheClient1.close();
        cacheClient2.close();
        serverMatsFactory1.close();
        serverMatsFactory2.close();
        clientMatsFactory1.close();
        clientMatsFactory2.close();
        matsTestBroker.close();
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
