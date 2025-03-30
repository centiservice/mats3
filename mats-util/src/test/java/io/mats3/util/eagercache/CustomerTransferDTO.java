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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import io.mats3.util.DummyFinancialService.CustomerDTO;
import io.mats3.util.DummyFinancialService.OrderDTO;

/**
 * A DTO for transferring Customer data, which is a copy of the CustomerDTO from the DummyFinancialService. Just to have
 * a difference, the {@link #address} field is named differently in this DTO.
 */
public class CustomerTransferDTO {
    public String customerId;
    public String name;
    public LocalDate birthDate;
    public String address;
    public String emailAddress;
    public String phone;
    public List<OrderDTO> orders;

    public CustomerTransferDTO() {
        this.orders = new ArrayList<>();
    }

    // Static factory method to create a CustomerTransferDTO from a CustomerDTO
    public static CustomerTransferDTO fromCustomerDTO(CustomerDTO customer) {
        CustomerTransferDTO customerTransfer = new CustomerTransferDTO();
        customerTransfer.customerId = customer.customerId;
        customerTransfer.name = customer.name;
        customerTransfer.birthDate = customer.birthDate;
        customerTransfer.address = customer.address;
        customerTransfer.emailAddress = customer.email;
        customerTransfer.phone = customer.phone;
        customerTransfer.orders = new ArrayList<>(customer.orders);
        return customerTransfer;
    }

    // Factory method to create a CustomerDTO from this CustomerTransferDTO
    public CustomerDTO toCustomerDTO() {
        CustomerDTO customer = new CustomerDTO();
        customer.customerId = this.customerId;
        customer.name = this.name;
        customer.birthDate = this.birthDate;
        customer.address = this.address;
        customer.email = this.emailAddress;
        customer.phone = this.phone;
        customer.orders = new ArrayList<>(this.orders);
        return customer;
    }

}