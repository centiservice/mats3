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
public class CustomerTransmitDTO {
    public String customerId;
    public String name;
    public LocalDate birthDate;
    public String address;
    public String emailAddress;
    public String phone;
    public List<OrderDTO> orders;

    public CustomerTransmitDTO() {
        this.orders = new ArrayList<>();
    }

    // Static factory method to create a CustomerTransferDTO from a CustomerDTO
    public static CustomerTransmitDTO fromCustomerDTO(CustomerDTO customer) {
        CustomerTransmitDTO customerTransfer = new CustomerTransmitDTO();
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