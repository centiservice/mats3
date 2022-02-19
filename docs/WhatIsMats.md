## What is Mats?

To explain Mats, we'll set up an example of an inter-service communication flow:

![Standard Example Mats Flow](img/StandardExampleMatsFlow-halfsize-pagescaled.svg)

*For a moment now, just to set the context, envision that the diagram above represents a multi-service setup using synchronous, blocking HTTP-calls ("REST services"). Each of the Endpoint A-D is a different REST service, while the Initiator+Terminator represent the initial synchronous, blocking HTTP call to EndpointA. The dashed lines in the diagram would then represent the blocking wait times each of the service threads would experience - where the initial call would block until the entire processing was finished, finally getting its response from EndpointA. Pretty standard stuff, right?*


> ### Attempt at a condensed explanation
> You've got a system being made up of multiple services, in a "microservice" architecture. You run at least two instances of each service, to ensure high availability. These services need to communicate. You have a set of different Mats Endpoints on each service, which the other services may invoke (there might also be a set of "service-private" Mats Endpoints). A Mats Endpoint can be a multi-staged service, where each Stage is an independent consumer and producer of messages to and from a message broker. A Stage of an Endpoint may invoke another Mats Endpoint by way of sending a Request-message targeting the invoked Endpoint's id, which is directly mapped over to a message queue name. The passed message is an envelope which contains a call stack, in addition to the Request call data for the targeted Endpoint. On the call stack, the calling Endpoint's "state object" is serialized, as well as the StageId of the next Stage which the reply value from the invoked Endpoint should go to when a Stage of the invoked Endpoint wants to return its result, thus sending a Reply-message (StageIds are again just directly mapped to a queue name). The state object, by being carried "on the wire" along the Request and Reply flow of messages, provides "local variables" that persist through the different stages of a particular Endpoint. A Mats Endpoint can, as explained, be invoked by a Stage of another Endpoint, or can be targeted by an Initiator. A message from an Initiator initiates a Mats Flow. The Mats system thus provides "invokable", general services, which are coded in a very straight down, linear manner, and thus resembles ordinary synchronous HTTP/"REST" endpoints both in coding style and usage. By running multiple instances of the microservice containing Mats Endpoints, you gain high availability. The ideas behind Mats are inspired by [SEDA](https://en.wikipedia.org/wiki/Staged_event-driven_architecture) and elements of the [Actor model](https://en.wikipedia.org/wiki/Actor_model), as well as the [EIP book](https://www.enterpriseintegrationpatterns.com/), where many of the patterns/features are relevant to Mats (and most other message oriented architectures), but the ["Process Manager"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ProcessManager.html), and ["Request-Reply"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/RequestReply.html) with ["Return Address"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ReturnAddress.html) probably relates the most to a Mats Endpoint - where Mats adds a call stack and state to the Request-Reply pattern, packaging it in an API that lets you forget about these intricacies.
>

A *Mats Endpoint* is a messaging-based service that contains one or multiple *Stages*. Each stage is a small independent multi-threaded "server" that listens to incoming messages on a queue, and performs some processing on the incoming DTO. For *Request* messages it will then typically either send a *Reply* message to the queue specified below it on the incoming message's call stack (i.e. to the caller's next stage), or perform a Request by sending a message to a different Mats endpoint, who's Reply will then be received on the next stage of the present endpoint.

So, in the above diagram, we have 5 independent Mats Endpoints (A to D + Terminator), and an Initiator. Each of the Endpoints would typically reside on a different (micro-)service, i.e. in different codebases, running inside different processes, albeit the Terminator would typically reside in the same codebase as the initiating code. EndpointA and EndpointC consist of multiple stages.

In a Mats Endpoint consisting of multiple stages, the stages of the endpoint are connected with an Endpoint-specified *state object* ("STO") which is transparently passed along with the message flow. This is what the dashed lines represent - but the actual content of the state object follows the continuous lines, representing the logical message flows. (Actually, all those messages go to and from the message broker, but logically they go as shown). This state object gives the effect of having "local variables" that are present through all the different Stages of a particular Mats Endpoint.

The incoming messages to a Mats Endpoint is either from another Mats Stage, or they are sent from an *Initiator*. When an Initiator sends a message, it initiates a *Mats flow*, which typically end with a Reply to a *Terminator* endpoint, which is just an Endpoint whose final stage does not perform any further request or reply.

## Code example for EndpointA

Here's an example of code for EndpointA, using plain Java to define it *(You may also define endpoints via the annotation-based [SpringConfig](../mats-spring) if Spring is your cup of tea)*:
```java
    private static class EndpointAState {
        double result_a_multiply_b;
        double c, d, e;
    }
    
    // Vulgarly complex Mats Endpoint to calculate 'a*b - (c/d + e)'.
    static void setupEndpoint(MatsFactory matsFactory) {
        MatsEndpoint<EndpointAReplyDTO, EndpointAState> ep = matsFactory
                .staged("EndpointA", EndpointAReplyDTO.class, EndpointAState.class);

        ep.stage(EndpointARequestDTO.class, (ctx, state, msg) -> {
            // State: Keep c, d, e for next calculation
            state.c = msg.c;
            state.d = msg.d;
            state.e = msg.e;
            // Perform request to EndpointB to calculate 'a*b'
            ctx.request("EndpointB", _EndpointBRequestDTO.from(msg.a, msg.b));
        });
        ep.stage(_EndpointBReplyDTO.class, (ctx, state, msg) -> {
            // State: Keep the result from 'a*b' calculation
            state.result_a_multiply_b = msg.result;
            // Perform request to Endpoint C to calculate 'c/d + e'
            ctx.request("EndpointC", _EndpointCRequestDTO.from(state.c, state.d, state.e));
        });
        ep.lastStage(_EndpointCReplyDTO.class, (ctx, state, msg) -> {
            // We now have the two sub calculations to perform final subtraction
            double result = state.result_a_multiply_b - msg.result;
            // Reply with our result
            return EndpointAReplyDTO.from(result);
        });
    }
```
*(The full example is available in the [unit tests](../mats-api-test/src/test/java/io/mats3/api_test/stdexampleflow).)*

First, we use the MatsFactory to create a "staged" Endpoint definition: The id of the Endpoint (which will become the queue name for the initial stage, with a prefix of "mats."), which class it will Reply with (the "return type"), and what class is used as the State object.

We then add stages to this Endpoint. The first Stage will become the entry point for the Endpoint, and is called the *initial Stage*. When an initiation or another Stage targets this Endpoint, it is the initial Stage that receives the message. The specified incoming type of this Stage is therefore what the Endpoint expects (the "parameter type" for the Endpoint). The following lambda is the *user code* for this Stage, receiving a Mats context, the state object, and the incoming request argument value. This lambda performs some logic, and then performs a Request to EndpointB using `context.request(<endpointId>, <requestDTO>)`.

The next stage again specifies which incoming type it expects, which is the type of the Reply from EndpointB, and then the user code lambda, receiving the Mats context, the state object, and the reply value from EndpointB. Again, the user code performs some logic, and then performs a Request to EndpointC.

The next and final stage is specified using `lastStage(..)`, which is a convenience that lets you Reply using a normal `return` statement, as well as invoking `ep.finishSetup()` for you to tell Mats that all stages are now added. The stage again specifies which incoming type it expects, which is the type of the Reply from EndpointC, and then the user code lambda, which gets the context, the state object, and the reply value from EndpointC. And again, the user code performs some logic, and simply `return` its finished Reply DTO instance, which will be passed to whoever invoked EndpointA.

*(Note: The return-functionality is literally a shorthand for invoking `context.reply(..)` - you could instead have added this stage by using `ep.stage(..)` as with the two previous Stages, and used `context.reply(..)` within the user code - but aside from not looking as nice, you'd then also have to manually tell Mats that the endpoint was finished set up by invoking `ep.finishSetup()` after the last Stage was added. You can also use `context.reply(..)` instead of `context.request(..)` in an earlier Stage to do early exit from this Endpoint.)*

The State type that the endpoint was specified with appears in all three stages' user code lambda as an instance passed in from Mats: For the initial stage, this will be a newly created instance of the State class - all fields having their default value. However, if you set any fields on this state object in a Stage, they will "magically" be present in the subsequent Stages.

Notice that you do not need to explicitly handle the state object - it is just provided to you. Also, you do not need to state which queue a requested service should post its Reply to - this is known by the Mats system, since it will simply be "this stage + 1". This also means that you do not need to think of which queue this Endpoint's Reply should go to - this is handled by Mats and the message's call stack, where the requesting stage will (automatically, by Mats) have specified the correct queue to post the Reply to. And this immediately points out that this service is *general* in that any initiation, or any stage of other Endpoints, can target it, as long as it can create a Request DTO and accept the resulting Reply DTO.

If you squint really hard, this could look like a single service method, receiving a DTO, performing two synchronous/blocking service calls, and then return a DTO as response.

*(Note: There are several other features, like fire-and-forget style `initiator.send(..)`, "broadcast" pub/sub (topic) `matsFactory.subscriptionTerminator(..)` with corresponding `initiator.publish(..)`, `context.next()` to jump to the next stage which can be good if you conditionally need to perform a request or not, "sideloads" for larger Strings or byte arrays, "trace properties" which works a bit like thread locals or "flow scoped variables", interactive and non-persistent flags which concerns the entire Mats flow, and much more..)*

## Stages are independent

As mentioned earlier, each of the stages of a Mats Endpoint is an independent "server", which means that each stage runs with a small, independent thread pool, each thread competing to pick off messages from its specific queue from the message broker, and sticking new messages onto other Endpoints' queues. Furthermore, to ensure high availability and scalability, you should run multiple nodes of each service. *(Notice, for added insight, that the queue names in the JMS implementation of Mats are straightforward mappings: An Endpoint's initial (or sole) Stage uses the queue name `mats.<endpointId>`, while for any subsequent stages, the queue name is `mats.<endpointId>.stage<sequence#>`)*

The big difference between this setup using a set of Mats Endpoints, and the alternative employing a set of REST services, is that every Stage of every Mats Endpoint is completely independent and stateless - and that all request/reply logic is fully asynchronous. All state within a Mats Flow is contained in the passed messages. A Mats flow where the message to the initial Stage of EndpointA was picked up by node1, could easily continue on node2 if the Reply message from EndpointB happened to be picked up by one of that node's StageProcessors for "EndpointA.stage1". If you midway in a whole heap of Mats flows running through these Endpoints suddenly take down "node1" of all services, *nothing happens*: All messages would then just _not_ be picked up by any node1 anymore (since they're gone!), but instead the StageProcessors on all Endpoints' "node2" instance would get some more to do. Even if such take down happened midway in the processing of some Stages running on a node1, still nothing would happen: The messages would (transactionally) be rolled back by the Message Broker (and possibly involved databases), and retried on the surviving node(s).

## Messages and envelopes

Technically, the Request and Reply, and well as the State objects, are serialized within an envelope, and this envelope is the actual message passed on the queues. The system uses the envelope to carry the call stack with the different state objects, in addition to the actual message DTO, but also gives the ability to [tack on information that carries through the Mats flow from start to finish](TraceIdsAndInitiatorIds.md). An immediate benefit for developers is the *TraceId* string, which is a mandatory parameter for an initiation. This ties together all processing throughout the flow, and with a common logging system for all your services, you immediately gain full insight into the processing of this Mats flow through the different parts of the distributed system.

*(Note: Since the envelope contains all the data and state, this should imply that if you just serialized a message envelope to disk, you could "stop" a Mats Flow, and then at a later time - possibly after having rebooted your entire server park - "restart" it by deserializing the message envelope and put it onto the correct queue. This functionality is indeed available, called "stash" and "unstash".)*

## Simple Message-based Inter Service Communications

The intention is that coding a Mats service Endpoint *feels like* coding a normal service method that performs synchronous RPC (e.g. REST) calls to other services, while reaping all the benefits of an asynchronous Message-Oriented communication paradigm. In addition, each stage is independently transactional, making a system made up of these services exceptionally resilient against any type of failure. The use of message queues, along with the stateless nature of the system, makes high availability and scalability as simple as running the same endpoint on more than one node.

The Mats API consist nearly solely of interfaces, not depending on any specific messaging platform's protocol or API. In the current sole implementation of the Mats API, which employs the Java Message Service API (JMS v1.1) as the queue interface, each stage has a set of threads that receives messages using a JMS MessageConsumer.

## Production Ready

This code has for several years been running in production as the sole inter service communication layer in a quite large financial system consisting of >40 "micro" services and several hundred Mats Endpoints, and before that (since 2014) the system ran on a previous incarnation of the underlying ideas. Several million messages are produced and consumed each day. The Message Broker in use in this production setup is Apache ActiveMQ, but all unit and integration tests also run on Apache Artemis (formerly JBoss HornetQ, and what RedHat AMQ is built on). *(RabbitMQ with its JMS client is not yet advised, due to some differing semantics in this broker, e.g. infinite, tight-loop retries without DLQ)*.

