package io.mats3.util.eagercache;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.util.DummyFinancialService.CustomerDTO;
import io.mats3.util.eagercache.MatsEagerCacheClient.CacheReceivedData;

public class DataCarrier {
    private static final Logger log = LoggerFactory.getLogger(DataCarrier.class);

    public final List<CustomerDTO> customers;

    DataCarrier(CacheReceivedData<CustomerCacheDTO> receivedData) {
        log.info("Creating DataCarrier! Meta:[" + receivedData.getMetadata()
                + "], Size:[" + receivedData.getDataCount() + "]");
        customers = receivedData.getReceivedDataStream()
                .map(CustomerCacheDTO::toCustomerDTO)
                .collect(Collectors.toList());
    }
}
