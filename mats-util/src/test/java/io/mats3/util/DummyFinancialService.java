package io.mats3.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Dummy "financial service" for creating DTOs that can be serialized and deserialized in tests. Provides
 * methods to create random data for customers, orders, and transactions.
 */
public class DummyFinancialService {

    public static class ReplyDTO {
        public List<CustomerDTO> customers;

        public ReplyDTO() {
            this.customers = new ArrayList<>();
        }
    }

    public static class CustomerDTO {
        public String customerId;
        public String name;
        public LocalDate birthDate;
        public String address;
        public String email;
        public String phone;
        public List<OrderDTO> orders;

        public CustomerDTO() {
            this.orders = new ArrayList<>();
        }
    }

    public static class OrderDTO {
        public String orderId;
        public LocalDateTime orderDateTime;
        public BigDecimal totalAmount;
        public String status;
        public String paymentMethod;
        public List<OrderItemDTO> items;
        public List<TransactionDTO> transactions;

        public OrderDTO() {
            this.items = new ArrayList<>();
            this.transactions = new ArrayList<>();
        }
    }

    public static class OrderItemDTO {
        public String itemId;
        public String productName;
        public int quantity;
        public BigDecimal unitPrice;
        public BigDecimal totalPrice;
    }

    public static class TransactionDTO {
        public String transactionId;
        public LocalDateTime transactionDateTime;
        public BigDecimal amount;
        public String currency;
        public String transactionType;
        public String status;
        public LocalDateTime settlementDateTime;
        public String description;
    }

    // Method to create a ReplyDTO with random data using a fixed seed
    public static ReplyDTO createRandomReplyDTO(long seed, int numCustomers) {
        Random random = new Random(seed);
        ReplyDTO reply = new ReplyDTO();
        for (int i = 0; i < numCustomers; i++) {
            reply.customers.add(createRandomCustomerDTO(random));
        }
        return reply;
    }

    // Helper method to create a CustomerDTO with random data
    private static CustomerDTO createRandomCustomerDTO(Random random) {
        CustomerDTO customer = new CustomerDTO();
        customer.customerId = UUID.nameUUIDFromBytes(("customer-" + random.nextInt()).getBytes()).toString();
        customer.name = generateRandomName(random);
        customer.birthDate = generateRandomBirthDate(random);
        customer.address = generateRandomAddress(random);
        customer.email = generateRandomEmail(customer.name, random);
        customer.phone = generateRandomPhone(random);

        int numOrders = random.nextInt(4) + 1; // Between 1 and 5 orders
        for (int i = 0; i < numOrders; i++) {
            customer.orders.add(createRandomOrderDTO(random));
        }
        return customer;
    }

    // Helper method to create an OrderDTO with random data
    private static OrderDTO createRandomOrderDTO(Random random) {
        OrderDTO order = new OrderDTO();
        order.orderId = UUID.nameUUIDFromBytes(("order-" + random.nextInt()).getBytes()).toString();
        order.orderDateTime = generateRandomOrderDateTime(random);
        order.status = generateRandomOrderStatus(random);
        order.paymentMethod = generateRandomPaymentMethod(random);

        int numItems = random.nextInt(4) + 1; // Between 1 and 5 items
        for (int i = 0; i < numItems; i++) {
            order.items.add(createRandomOrderItemDTO(random));
        }

        // Calculate total amount
        order.totalAmount = order.items.stream()
                .map(item -> item.totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Add transactions
        int numTransactions = random.nextInt(2) + 1; // Between 1 and 3 transactions
        for (int i = 0; i < numTransactions; i++) {
            order.transactions.add(createRandomTransactionDTO(order.totalAmount, random, order.orderDateTime));
        }

        return order;
    }

    // Helper method to create an OrderItemDTO with random data
    private static OrderItemDTO createRandomOrderItemDTO(Random random) {
        OrderItemDTO item = new OrderItemDTO();
        item.itemId = UUID.nameUUIDFromBytes(("item-" + random.nextInt()).getBytes()).toString();
        item.productName = generateRandomProductName(random);
        item.quantity = random.nextInt(9) + 1; // Between 1 and 10
        item.unitPrice = BigDecimal.valueOf(5 + (500 - 5) * random.nextDouble())
                .setScale(2, RoundingMode.HALF_UP);
        item.totalPrice = item.unitPrice.multiply(BigDecimal.valueOf(item.quantity))
                .setScale(2, RoundingMode.HALF_UP);
        return item;
    }

    // Helper method to create a TransactionDTO with random data
    private static TransactionDTO createRandomTransactionDTO(BigDecimal amount, Random random,
            LocalDateTime orderDateTime) {
        TransactionDTO transaction = new TransactionDTO();
        transaction.transactionId = UUID.nameUUIDFromBytes(("transaction-" + random.nextInt()).getBytes()).toString();
        transaction.transactionDateTime = generateRandomTransactionDateTime(random, orderDateTime);
        transaction.amount = amount;
        transaction.currency = generateRandomCurrency(random);
        transaction.transactionType = generateRandomTransactionType(random);
        transaction.status = generateRandomTransactionStatus(random);
        transaction.settlementDateTime = transaction.transactionDateTime.plusDays(random.nextInt(4) + 1);
        transaction.description = generateRandomTransactionDescription(random);
        return transaction;
    }

    // Random data generation methods with Random parameter
    private static String generateRandomName(Random random) {
        String[] firstNames = { "John", "Jane", "Alex", "Emily", "Chris", "Katie", "Michael", "Sarah", "David",
                "Laura" };
        String[] lastNames = { "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
                "Rodriguez", "Martinez" };

        String firstName = firstNames[random.nextInt(firstNames.length)];
        String lastName = lastNames[random.nextInt(lastNames.length)];

        return firstName + " " + lastName;
    }

    private static LocalDate generateRandomBirthDate(Random random) {
        LocalDate baseDate = LocalDate.of(1950, 1, 1);
        int daysToAdd = random.nextInt(365 * 70); // Up to 70 years
        return baseDate.plusDays(daysToAdd);
    }

    private static String generateRandomAddress(Random random) {
        String[] streets = { "Main St", "Oak Ave", "Pine Rd", "Maple Dr", "Cedar Ln" };
        String[] cities = { "New York", "Los Angeles", "Chicago", "Houston", "Phoenix" };
        String[] states = { "NY", "CA", "IL", "TX", "AZ" };
        int streetNumber = random.nextInt(9900) + 100; // Between 100 and 9999

        String street = streets[random.nextInt(streets.length)];
        String city = cities[random.nextInt(cities.length)];
        String state = states[random.nextInt(states.length)];
        String zip = String.format("%05d", random.nextInt(89999) + 10000);

        return streetNumber + " " + street + ", " + city + ", " + state + " " + zip;
    }

    private static String generateRandomEmail(String name, Random random) {
        String[] domains = { "example.com", "test.com", "mail.com", "email.com" };
        String domain = domains[random.nextInt(domains.length)];
        String emailName = name.toLowerCase().replaceAll(" ", ".");
        return emailName + "@" + domain;
    }

    private static String generateRandomPhone(Random random) {
        int areaCode = random.nextInt(799) + 200; // Between 200 and 999
        int exchange = random.nextInt(799) + 200;
        int lineNumber = random.nextInt(9000) + 1000;
        return String.format("(%03d) %03d-%04d", areaCode, exchange, lineNumber);
    }

    private static LocalDateTime generateRandomOrderDateTime(Random random) {
        LocalDateTime baseDateTime = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        int daysToAdd = random.nextInt(365 * 3); // Up to 3 years
        int secondsToAdd = random.nextInt(86400); // Seconds in a day
        return baseDateTime.plusDays(daysToAdd).plusSeconds(secondsToAdd);
    }

    private static LocalDateTime generateRandomTransactionDateTime(Random random, LocalDateTime orderDateTime) {
        // Transaction date-time after order date-time
        int daysToAdd = random.nextInt(30); // Up to 30 days after order
        int secondsToAdd = random.nextInt(86400); // Seconds in a day
        return orderDateTime.plusDays(daysToAdd).plusSeconds(secondsToAdd);
    }

    private static String generateRandomOrderStatus(Random random) {
        String[] statuses = { "Pending", "Processing", "Shipped", "Delivered", "Cancelled", "Returned" };
        return statuses[random.nextInt(statuses.length)];
    }

    private static String generateRandomPaymentMethod(Random random) {
        String[] methods = { "Credit Card", "Debit Card", "PayPal", "Bank Transfer", "Cash" };
        return methods[random.nextInt(methods.length)];
    }

    private static String generateRandomProductName(Random random) {
        String[] products = { "Laptop", "Smartphone", "Tablet", "Headphones", "Camera", "Watch", "Printer", "Monitor",
                "Keyboard", "Mouse" };
        return products[random.nextInt(products.length)];
    }

    private static String generateRandomCurrency(Random random) {
        String[] currencies = { "USD", "EUR", "GBP", "JPY", "AUD", "CAD" };
        return currencies[random.nextInt(currencies.length)];
    }

    private static String generateRandomTransactionType(Random random) {
        String[] types = { "Debit", "Credit", "Refund", "Chargeback", "Adjustment" };
        return types[random.nextInt(types.length)];
    }

    private static String generateRandomTransactionStatus(Random random) {
        String[] statuses = { "Pending", "Completed", "Failed", "Cancelled" };
        return statuses[random.nextInt(statuses.length)];
    }

    private static String generateRandomTransactionDescription(Random random) {
        String[] descriptions = { "Payment for order", "Refund issued", "Chargeback processed", "Adjustment applied" };
        return descriptions[random.nextInt(descriptions.length)];
    }
}
