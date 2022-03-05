# Developing with Mats - Basics - Endpoints and Stages

_Note: This document assumes you have a `MatsFactory` available - but is still a good starting point to get the gist of
how Mats Endpoints work._

The intention with Mats is to make it possible to code message-based endpoints in a manner which feels like developing
synchronous HTTP-like endpoints. You get a Request which includes a request object, and should produce a Reply object
which you return, but you might have to interact with a second service to calculate the reply. To further this effect,
if you develop with Spring, you may employ the SpringConfig tool of Mats, which includes the annotations `@MatsMapping`
and `@MatsClassMapping`. These are meant to be analogous to Spring's `@RequestMapping` (+ `@ResponseBody`) annotation,
to create single- and multi-stage Mats endpoints.

## Single stage Endpoint

For a single-stage endpoint, things are really straightforward: You define a single-stage endpoint, and provide a lambda
which should be executed when the endpoint is invoked. This lambda is provided the incoming message's request object,
and it should produce and return the Reply object. If you need to invoke any database operation to produce this reply,
you just do that. This is a synchronous operation, and will block the incoming message handler thread. This is expected,
the system is not meant to be fully asynchronous. To handle this synchronous, blocking aspect, every stage of an
endpoint have a set of StageProcessors, which is small thread pool handling just this one stage. The "concurrency" of
these thread pools are default set to number-of-cpus x 2, but can be tailored to the processing of the stage, e.g. if
the database can handle 10 concurrent queries of whatever happens in this stage, the total concurrency of this stage -
taking into account how many replicas of the service is run - can be set to 10.

To compare, in a Servlet-style world, the servlet container would be set up with a thread pool, e.g. 100 or 200 threads,
and all HTTP endpoints defined in this service will share this pool of threads. If you instead had a smaller thread pool
per HTTP endpoint, which possibly was tailored to the load and handling capacity of the operations in that endpoint, it
would be very similar to Mats.

Here's a single endpoint set up with plain Java _(As mentioned at the top, this assumes that you have the MatsFactory
already available):_

```java
class EndpointSetup {
    static void javaMatsSingleStageEndpoint(MatsFactory matsFactory) {
        // This service is very simple, where it just returns with an alteration of what it gets input.
        matsFactory.single("Service.calculate", ServiceReply.class, ServiceRequest.class, (context, msg) -> {
            // Calculate the resulting values
            double resultNumber = msg.number * 2;
            String resultString = msg.string + ":FromService";
            // Return the reply DTO
            return new ServiceReply(resultNumber, resultString);
        });
    }
}
```

And here is the same example set up with SpringConfig _(The MatsFactory must be present in the Spring context, and some
`@Configuration` class must have the `@EnableMats` annotation to enable Mats' SpringConfig's annotation scanning)_.

```java
// Note: Some @Configuration-class has the @EnableMats annotation set.

@Service
class MatsEndpoints {

    @MatsMapping("Service.calculate")
    public ServiceReply springMatsSingleStageEndpoint(ServiceRequest msg) {
        // Calculate the resulting values
        double resultNumber = msg.number * 2;
        String resultString = msg.string + ":FromService";
        // Return the reply DTO
        return new ServiceReply(resultNumber, resultString);
    }
}
```

Notice the difference between the two examples: In the first example we programmatically define the endpoint and provide
the handling lambda for the initial (and sole) Stage. This setup method is only invoked once at boot, and then the Mats
"server" invokes the lambda when new messages arrive on the Endpoint's Stage's message queue. In the second example, the
method itself is the handling code, being invoked when new messages arrive. This is similar to the difference between
programmatically defining a Servlet, compared to using Spring Web MVC's `@RequestMapping` annotation on a method. Behind
the scenes Mats' SpringConfig just does the java config for you, utilizing the bean's `@MatsMapping`-annotated method as
the handling lambda - similarly to how there actually is a DispatcherServlet underlying the @RequestMapped methods of
Spring Web MVC. SpringConfig is a small side-library utilizing only the Mats API, scanning beans for the relevant
annotations and doing the equivalent java operations to define the endpoints.

If an Endpoint can answer "locally", i.e. by performing some calculation or lookup, and can do that with just local
resources, calculating some result, possibly based on some SQL queries, this will typically involve an Endpoint with
just a single Stage. This situation is common enough to have its own convenience methods as shown.

## Multi stage Endpoint

If you in an endpoint need to communicate with other Mats endpoints, things become a tad more interesting. If you for
example are in a shipping service, and the endpoint in question should calculate shipping, but needs to query the order
service to calculate rebate based on previous orders, you will have to employ a multi stage Mats endpoint.

If this was a HTTP-based endpoint, you'd typically receive the request, and midway you'd do a request over to the order
service. Based on the response you would then do the calculation and return your response.

Illustrative example of such a HTTP endpoint, employing Spring and its (deprecated!) RestTemplate:

```java

@RestController
class ShippingControllerClass {

    private final String _orderServiceUrl;
    private final ShippingService _shippingService;
    private final RestTemplate _restTemplate;

    // We need to know the URL for the OrderService
    @AutoWired
    ShippingControllerClass(@Value("${orderservice.url}") String orderServiceUrl,
            RestTemplate restTemplate, ShippingService shippingService) {
        _orderServiceUrl = orderServiceUrl;
        _shippingService = shippingService;
        _restTemplate = restTemplate;
    }

    @GetMapping("shipping/calculate")
    public ShippingCostResponse calculateShipping(@RequestBody ShippingCostRequest request) {
        // Check if this is one of our special customers
        if (_shippingService.isSpecialCustomer(request.customerId)) {
            // Yes, so he always gets free shipping - return early.
            return ShippingCostResponse.freeShipping();
        }

        // :: Ask order-service for total value last year
        // BLOCKING request to OrderService
        // Note: This should probably be wrapped up in a OrderServiceClient-type class.
        OrderServiceTotalValueResponse orderServiceResponse;
        try {
            orderServiceResponse = _restTemplate.getForObject(_orderServiceUrl
                    + "/orders/totalValueLastYear/" + request.customerId);
        }
        catch (Exception wce) {
            // Handle errors or broken connections.
            // Retry? Or fail the request? What then happens upstream? How do you catch this?
        }

        // Based on response, we'll give rebate or not.
        // Notice how this uses the 'request.orderLines' parameter from the original request.
        return orderServiceResponse.getValue() > 1000
                ? _shippingService.rebated(request.orderLines)
                : _shippingService.standard(request.orderLines);
    }
}
```

***(Notice: We could have used e.g. WebClient and thus asynchronous processing, but read note below.)***

Mats-setup of the same service, using plain Java:

```java
class ShippingEndpointSetup {

    // State class for the Endpoint
    private static CalculateShippingState {
        List<OrderLine> orderLines;
    }

    public void setupCalculateShippingMatsEndpoint(ShippingService shippingService,
            MatsFactory matsFactory) {
        // Create the staged Endpoint, specifying the Reply type and state class
        MatsEndpoint<ShippingCostReply, CalculateShippingState> ep = matsFactory
                .staged("ShippingService.calculateShipping",
                        ShippingCostReply.class, CalculateShippingState.class);

        // Initial stage, receives incoming message to this Endpoint
        ep.stage(ShippingCostRequest.class, (context, state, msg) -> {
            // Check if this is one of our special customers
            if (shippingService.isSpecialCustomer(request.customerId)) {
                // Yes, so he always gets free shipping - reply early.
                context.reply(ShippingCostReply.freeShipping());
                return;
            }

            // Store the values we need in next stage in state-object
            state.orderLines = msg.orderLines;

            // Perform request to the totalValueLastYear Endpoint...
            context.request("OrderService.orders.totalValueLastYear",
                    new OrderTotalValueRequest(customerId));
        });

        // Next, and last, stage, receives replies from the totalValueLastYear endpoint,
        // utilizes the state variable, and returns a Reply
        ep.lastStage(OrderServiceTotalValueReply.class,
                (context, state, orderServiceResponse) -> {
                    // Based on OrderService's response, we'll give rebate or not.
                    return orderServiceResponse.getValue() > 1000
                            ? shippingService.rebated(state.orderLines)
                            : shippingService.standard(state.orderLines);
                });
    }
}
```

Using Mats, this would be divided into two separate stages, 0 and 1. The zeroth stage is the initial stage, which
receives the requests targeted to the calculate shipping endpoint. This stage would do the special-customer check and
possibly do early reply. Otherwise, it'll store away any state that needs to be present on the subsequent stage, and
then perform the request to the order service. Technically, what happens now is that a message is put on the
incoming-queue of the specified order service's endpoint, and the thread that ran the processing of the initial stage of
calculate shipping goes back to listening for new incoming messages on this endpoint - the stage processing is now
finished. The Mats Flow and its state lives on _"on the wire"_, and the Flow continues when a thread on the order
service endpoint picks up the message on its incoming queue. Whether this order endpoint is a single-stage Endpoint, or
is a 20-stage endpoint requesting 19 other endpoints, is of absolutely no concern for the shipping endpoint.

When the order endpoint eventually produces a Reply message, Mats will know based on the call stack who should get the
Reply. It thus ends up on the incoming queue of stage1 (the second stage) of the shipping endpoint. One of the
StageProcessors of this stage will pick up the message, reconstitute the State object which was present on the initial
stage, and invoke the processing lambda with the State object and the Reply object from the order endpoint. This stage
then does the final calculation, and returns the finished Reply to whoever invoked it.

Compared to the HTTP endpoint variant, the obvious difference is that the endpoint body is spread out over two lambdas,
the initial ending with the request to the collaborating service, and the next starting from the reply from the
collaborating service. But in addition, we need to be explicit about the state being kept between stages, instead of
being able to rely on the standard Java stack machinery (or, in case of lambda-utilizing asynchronous mechanisms, the
capturing of variables).

The `ep.lastStage(..)` is a convenience, and is exactly equivalent to adding the stage using `ep.stage(..)`, sending the
reply using `context.reply(..)`, **and then after adding that final stage, invoking `ep.finishSetup()`**.

Setup using Mats' SpringConfig:

```java

@MatsClassMapping("ShippingService.calculateShipping")
class ShippingEndpointClass {

    private final ShippingService _shippingService;

    @Autowired
    ShippingEndpointClass(ShippingService shippingService) {
        _shippingService = shippingService;
    }

    // This is the state field
    List<OrderLine> _orderLines;

    @Stage(Stage.INITIAL)
    void initialStage(ProcessContext<ShippingCostReply> context,
            ShippingCostRequest msg) {
        // Check if this is one of our special customers
        if (shippingService.isSpecialCustomer(request.customerId)) {
            // Yes, so he always gets free shipping - reply early.
            context.reply(ShippingCostReply.freeShipping());
            return;
        }

        // Store the values we need in next stage in state-object ('this').
        _orderLines = msg.orderLines;

        // Perform request to the totalValueLastYear Endpoint...
        context.request("OrderService.orders.totalValueLastYear",
                new OrderTotalValueRequest(customerId));
    }

    @Stage(1)
    ShippingCostReply calculate(OrderServiceTotalValueReply orderServiceReply) {
        // Based on OrderService's response, we'll give rebate or not.
        return orderServiceResponse.getValue() > 1000
                ? shippingService.rebated(state.orderLines)
                : shippingService.standard(state.orderLines);
    }
}
```

A multi-stage, Spring-annotated variant of setting up a multi-stage endpoint is a bit _"magic"_. The `@MatsClassMapping`
is _meta-annotated_ as a `@Service`, and Spring thus instantiates a single instance of it. This bean singleton acts _as
a template_ for the injected fields - and is otherwise never used. The other fields of the class acts as state fields.
What it internally does, is again simply invoking the relevant Mats API methods, with a tad of glue-code. The class
itself is set as the State-class of the multi-stage endpoint, and the `@Stage`-annotated methods as the lambdas for each
stage, gleaning the incoming message types from the method's parameter. The return type is gotten from the
single `@Stage`-method with a non-void return type. When receiving messages, before the stage methods are invoked, the
injected fields from the template bean is set on the state instance. And before a message is sent, those fields are
nulled out, thus leaving only the state to be serialized.

The shade of magic is maybe a bit too dark for some, and there is also a `@MatsEndpointSetup`, which is much simpler,
effectively falling back to plain Java config. Only the initial `matsFactory.staged(..)` call is done for you. However,
the unit testability of `@MatsClassMapping` is pretty good, and if you get past the shadiness of the solution, it is
pretty nice to code with.

> **A comment about the meaning of _asynchronous_**: For the HTTP-example, we could have utilized asynchronous
> processing, using e.g. Servlet's _async_ mode, or Spring's _WebFlux_. We'd then use an asynchronous/reactive
> HttpClient (e.g. Spring's `WebClient`), which uses non-blocking IO, to handle the outgoing HTTP call. In our code,
> we'd then use a `thenApply(..)` or `subscribe(..)`-style method, in which we returned and finished the incoming
> HTTP call. The Servlet threads would then not be blocking, as in the HTTP-example above, instead being free to handle
> new incoming requests. However, this is not really the point about the asynchronous nature of Mats.
>
> What if the collaborating service (OrderService) started acting up, either going really slow, or just returning 500's,
> or it looses power? Now, no matter whether you use blocking or non-blocking processing, you sit with a heap of
> unresolved mid-process executions _in the JVM_ of ShippingService. And what if ShippingService itself acts up or
> crashes? With Mats, the state of the processing lives _on the wire_, inside the messages being passed. The flows only
> "occasionally" goes into processing on a node - otherwise living on the message broker. If the OrderService goes slow,
> you'll get a queue built up (which you can monitor). If either of the services crashes (or is rebooted), the messages
> are rolled back, being retried on another node.
>
> If a message is _poison_ - i.e. non-processable - it'll eventually end up on a Dead Letter Queue on the broker, from
> where you could centrally inspect those particular messages (and their log traces through all the different services
> based on their TraceIds), and either ditch them, or retry them again if the underlying error is now cleared.
>
> You could tear down your entire service mesh, and build it up in a different cloud: As long as you carry along the
> state of the message broker, once the broker and services comes online again, they'll effectively continue all the
> ongoing Mats Flows as if nothing happened. The only observable effect would be a bit of extra latency.

## Initiating Mats Flows

With the above concepts, you should now understand the fundamental processing of Mats endpoints. But there is still a
concept to learn: How do we start such Mats flows? All the above assume that there is already a Mats Flow in progress -
i.e. the shipping endpoint was invoked by someone, and will return their reply to the invoker's next stage.

There is basically two distinct situations here: Asynchronous/"Batch" initiation, and synchronous/interactive
invocations. However, both of these employ the concept of initiation, from "the outside" of the Mats fabric. Batch
processing is meant to imply that it is some kind of system internal, non-interactive processes. For example, a new
batch of widgets comes into the warehouse, and therefore we should run through the order tables and see which orders can
now be processed. For each processable order, a Mats Flow is initiated. Interactive invocation is meant to imply that
typically some agent is at the edge, wanting to interact with the Mats fabric - this will typically be a human,
employing a GUI. This will often be a synchronous HTTP endpoint, and we need to invoke a Mats endpoint to e.g. query for
information, or insert or modify some information - thus initiating a Mats Flow.

We'll come back to the synchronous situation after having explained how to initiate a Mats Flow.

### Batch, or asynchronous initiations

This following is a "fire and forget"-style initiation, which just sends a message to a Mats Endpoint, thereby
initiating a Mats Flow. Whether this is dozen-stage endpoint, or a single stage, is of no concern to the initiator.

```java
<basic send initiation,without replyTo>
```

The Mats Flow will terminate when no new Flow messages are produced - or if the endpoint targetted by the
fire-and-forget send-invocation performs a Reply, as there is no one to Reply to. The latter is analogous to invoking a
Java method which return something, but where you do not take its return. For example `map.put(key, value)` returns the
previous value at the key position, but often you do not care about this.

If you in the initiation want a reply from the Mats Flow, you employ the following initiation, where you specify a
replyTo endpoint. Such an Endpoint is called a Terminator, as it will receive the final Reply, and then eventually must
terminate the Mats Flow since there is no one to Reply to. It is typically a single-stage endpoint, but this is not a
requirement. You can supply a state object in the initiation, which will be present on the Terminator.

```java
<basic request initiation,with replyTo>
```

However, it is important to understand that there is no connection between the initiation-point and the terminator,
except for the state object. So, if you fire off a request in a HTTP-endpoint, the final Reply will happen on a thread
of the Terminator-endpoint, without any connection back to your initiatiation. **Crucially, the Reply might even come on
a different service instance (node/replica) than you initiated the Mats Flow from!** This is all well and good in the
"new shipping of widgets arrived" scenario, where you in the terminator want to set the order status in the database
to "delivered". But it will be a bit problematic when wanting to interactively communicate with the Mats fabric, e.g.
from a web user interface.

So, how can we bridge between a HTTP endpoint's synchronous world, and the utterly asynchronous and distributed
processing of Mats?

### Interactive, or synchronous initiations, MatsFuturizer

Based on the logic above, you might see the contour of how to be able to perform an initiation, and then wait for the
Reply to come back. The trick is twofold: We need to ensure that the Reply comes back to the same node that initiated
the Request, and we need set up some synchronization between the initiation and the Terminator that receives the final
Reply.

The first part is done by making a reply destination that includes the nodename (e.g. hostname) of the node we're
initiating from. The second is simply a matter of using Java primitives to wait and notify. I.e. setting up a map of
"outstanding futures" with the key being a correlationId, and then employ the state object to keep this correlationId
through the Mats Flow, so that when the Terminator gets the Reply it knows which future to complete.

All of that is neatly packaged up in a tool called the `MatsFuturizer`. It resides in the package 'mats-util', and is a
fairly simple tool built on the Mats API.

The usage is that you invoke a method that looks very much like an initiation, but get a CompletableFuture back. This
future is resolved when the final Reply comes back.

```java
<simple futurization>
```

In a Servlet-style world, this can be used both in a synchronous way, where you perform the futurization, and
immediately perform .get() on it to get the result and return it as the response from the HTTP endpoint. Or you can
employ an asynchronous dispatch, where you employ a thenApply(...) and complete the request.

**Make sure you understand that you should not construct composite services by chaining MatsFuturizations on top of each
other!** The MatsFuturizer should only be employed on the very edges of the Mats fabric, where there is an actual
interface between synchronous processing and Mats. Do not use it in your application-internal APIs. There's a document
about this [here](../MatsComposition.md).

### Advanced server-client bridging: MatsSocket

There's a sister project called _MatsSocket_ which brings the asynchronous nature of Mats all the way out to the client,
by way of Websockets. Compared to the immediate understandability of the above MatsFuturizer, this is a bit more
involved. But the rewards, after having set up the framework with authentication, are pretty substantial. Please read
up!