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

    DataCarrier(CacheReceivedData<CustomerTransferDTO> receivedData) {
        log.info("Creating DataCarrier from " + receivedData);
        customers = receivedData.getReceivedDataStream()
                .map(CustomerTransferDTO::toCustomerDTO)
                .collect(Collectors.toList());
    }
}
