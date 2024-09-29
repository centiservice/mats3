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

    DataCarrier(List<CustomerDTO> customers) {
        this.customers = customers;
    }

    DataCarrier(CacheReceivedData<CustomerTransmitDTO> receivedData) {
        log.info("Creating DataCarrier from " + receivedData);
        customers = receivedData.getReceivedDataStream()
                .map(CustomerTransmitDTO::toCustomerDTO)
                .collect(Collectors.toList());
    }
}
