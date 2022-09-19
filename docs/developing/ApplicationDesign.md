# Designing services and endpoints using Mats
_by: Ståle Undheim_

Mats is inteded to be used in a MicroService environment, where each service has its distinct responsibility, and
the RPC mechanism between these services happens via Mats. Each service thus has a set of public endpoints that
other services rely on. When creating individual endpoints, its largely up to the developer how to architect this.
You can easily start simple, and just do all the work, including SQL updates right in the endpoint. Thus reducing
the number of layers and abstractions in the application, and making the code base smaller and more accessible.

However, as you see the need to increase abstractions in order to do code reuse, Mats endpoints should then be
refactored to serve as orchestrators, rather than executors. Avoid creating private endpoints that encapsulate
some functionality, that other endpoints within the same service uses. This can quickly escalate into several
layers of private endpoints that could much more easily be solved by local method calls instead.

A common scenario is that in executing an operation in your service, you need additional information from other
service via Mats. The worst approach for doing this, is to have a MatsFuturizer inside your internal service
that calls out to other servces. See [Mats Composition](MatsComposition.md) for more details on this.

```java
public class BadOrderService {

    @Inject private MatsFuturizer _futurizer;
    @Inject private OrderRepository _orderRepository;

    public void createOrder(String customerId, String shoppingCartId) {
        // Call out to CustomerService and ShoppingCartService
        CustomerDto customerDto = _futurizer.futurize(...).join().getReply();
        ShoppingCartDto shoppingCartDto = _futurizer.futurize(...).join().getReply();

        // Convert the domain entities to local domain representations
        Customer customer = toDomain(customerDto);
        ShoppingCart shoppingCart = toDomain(shoppingCartDto);

        Order order = Order.create(customer, shoppingCart);

        _orderRepository.save(order);
    }
}
```

The problem with the above service becomes apparent if we integrate it in a Mats Flow. As then the Mats Flow will be
in a blocking wait on 2 other Mats Flows. If this is in turn called via a Futurizer in another Mats flow, we start
getting into problems. A much better approach is in stead something like this:


```java
public class BetterOrderService {
    @Inject private OrderRepository _orderRepository;

    public void createOrder(Customer customer, ShoppingCart shoppingCart) {
        Order order = Order.create(customer, shoppingCart);

        _orderRepository.save(order);
    }
}
```

This is both simpler, and communicates its dependencies (Customer and ShoppingCart) via its API. This also means we
can reuse the Order.create function in a preview in a Controller like this:

```java
@Controller
public class OrderController {

    @RequestMapping
    public CompletableFuture<OrderDto> previewOrder(String customerId, String shoppingCartId) {
        // Call out to CustomerService and ShoppingCartService
        CompletableFuture<CustomerDto> customerDto = _futurizer.futurize(...).join().getReply();
        CompletableFuture<ShoppingCartDto> shoppingCartDto = _futurizer.futurize(...).join().getReply();

        return customerDto.thenCombine(shoppingCartDto, (customerDto, shoppingCartDto) -> {
            // Convert the domain entities to local domain representations
            Customer customer = toDomain(customerDto);
            ShoppingCart shoppingCart = toDomain(shoppingCartDto);

            Order order = Order.create(customer, shoppingCart);
            return order;
        });
    }
}
```


In this instance, it is perfectly acceptable to use a Futurizer, as we are in a synchronous request call. We can even
take the advantage of Sprigns async support to not block any Http threads while waiting for the reply. This works for
a preview of order, but if we actually want to create an order, we should instead forward to a private endpoint that
handles this, that is specific for the OrderController:

```java
@Controller
public class OrderController {

    @Inject private MatsFuturizer _futurizer;

    @MatsClassMapping("OrderService.private.createOrder")
    public static class PrivateCreateOrderEndpoint {

        private CreateOrderRequest _request;
        private CustomerDto _customerDto;
        private ProcessContext<OrderDto> _processContext;
        @Inject private BetterOrderService _orderService;

        @Stage(Stage.INITIAL)
        public void receiveRequest(CreateOrderRequest request) {
            _request = request;
            _processContext.request("CustomerService.getCustomer", _request.customerId);
        }

        @Stage(10)
        public void receiveCustomer(CustomerDto customerDto) {
            _customerDto = customerDto;
            _processContext.request("ShoppingCartService.getShoppingCart", _request.shoppingCartId);
        }

        @Stage(20)
        public OrderDto reply(ShoppingCartDto shoppingCartDto) {
            Customer customer = toDomain(customerDto);
            ShoppingCart shoppingCart = toDomain(shoppingCartDto);

            Order order = _orderService.createOrder(customer, shoppingCart);

            return toDto(order);
        }

    }

    @RequestMapping(method = RequestMethod.POST)
    public CompletableFuture<OrderDto> createOrder(String customerId, String shoppingCartId) {
        return _futurizer.futurizeNonessential(
                "http.createOrder",
                "OrderController.createOrder",
                "OrderService.private.createOrder",
                OrderDto.class,
                new CreateOrderRequest(customerId, shoppingCartId)
        ).thenApply(Reply::getReply);
    }
}
```

Here we have a specific endpoint for the controller, that takes care of creating an order. The actual domain logic
for creating an order is implemented inside the BetterOrderService, while this MatsFlow only serves as an orchestrator
to fetch necessary dependencies. By having the actual endpoint as an inner class of the controller, it clearly
communicates the scope of the endpoint.

Private endpoints are a tool to bridge the gap between the synchronous Rest world, and the asynchronous
and distributed Mats world. However, this private createOrder endpoint should not be reused within the same service
by other Mats Flows. These should then instead fetch the necessary dependencies and then call the OrderService. This
is because each call to a Mats function adds 2 requests to the Queue, one for the request, and one for the reply back.

Instead, prefer to move domain logic to services that communicate their dependencies, and then the caller is responsible
for retrieveing these dependencies. While we could have called the OrderService directly from the Controller method
in the later example as well, this example actually does change the state of the application. Having such services
invoked via Mats instead of the Controller lets us re-use all the nice features in Mats wrt. retries, logging,
transaction management and DLQ when things go horribly wrong.
