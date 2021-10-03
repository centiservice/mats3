# Mats<sup>3</sup> - Message-based Asynchronous Transactional Staged Stateless Services

*Introducing "MOARPC" - Message-Oriented Asynchronous Remote Procedure Calls!*

*Mats* is a Java library that facilitates the development of asynchronous, stateless (or stateful, depending on your point of view), multi-stage, message-based services. Mats Endpoints immediately provide all the benefits you get from a fully asynchronous messaging-based architecture, while being *almost* as simple to code as blocking, synchronous JSON-over-HTTP "REST" endpoints.

*Currently, to play around with the library, the simplest option is to clone it and run `./gradlew clean check test`. After this pans out, fire up your IDE and head over to the unit/integration tests of [API](mats-api-test/src/test/java/io/mats3/api_test), [Spring](mats-spring/src/test/java/io/mats3/spring), [MatsFuturizer](mats-util/src/test/java/io/mats3/util/futurizer), and tests of testing tools [JUnit](mats-test-junit/src/test/java/io/mats3/test/junit)/[Jupiter](mats-test-jupiter/src/test/java/io/mats3/test/jupiter)/[Spring](mats-spring-test/src/test/java/io/mats3/spring/test). There's also a very rudimentary "dev area" for the Metrics Interceptor [MatsMetricsJettyServer](mats-intercept-micrometer/src/test/java/io/mats3/test/metrics/MatsMetricsJettyServer.java), and same for Local Introspector [LocalHtmlInspectTestJettyServer](mats-localinspect/src/test/java/io/mats3/localinspect/LocalHtmlInspectTestJettyServer.java), both of which you may start from your IDE.*

To use Mats in a project, fetch [`mats-impl-jms`](https://mvnrepository.com/artifact/io.mats3/mats-impl-jms) from [Maven Central](https://mvnrepository.com/artifact/io.mats3).

License: [Polyform Perimeter 1.0.0 with examples](LICENSE.md)

If you find Mats interesting, you might want to check out the "companion project" [MatsSocket](https://github.com/centiservice/matssocket).

## What is Mats?

To explain Mats, we'll set up an example of an inter-service communication flow:

![Standard Example Mats Flow](docs/img/StandardExampleMatsFlow-halfsize-pagescaled.svg)

*For a moment now, just to set the context, envision that the diagram above represents a multi-service setup using synchronous, blocking HTTP-calls ("REST services"). Each of the Endpoint A-D is a different REST service, while the Initiator+Terminator represent the initial synchronous, blocking HTTP call to EndpointA. The dashed lines in the diagram would then represent the blocking wait times each of the service threads would experience - where the initial call would block until the entire processing was finished, finally getting its response from EndpointA. Pretty standard stuff, right?*


> ### Attempt at a condensed explanation
> In e.g. a microservice, you may run one or more Mats Endpoints. A Mats Endpoint can be a multi-staged service, where each Stage is an independent consumer and producer of messages to and from a message broker. A Stage of an Endpoint may invoke another Mats Endpoint by way of sending a Request-message targeting the invoked Endpoint's id, which is directly mapped over to a message queue name. The passed message is an envelope which contains a call stack, in addition to the Request call data for the targeted Endpoint. On the call stack, the current "state object" is serialized, as well as the StageId of the next Stage which the reply value from the invoked Endpoint should go to when a Stage of the invoked Endpoint performs a reply, thus sending a Reply-message (StageIds are again just directly mapped to a queue name). The state object, by being carried "on the wire" along the Request and Reply flow of messages, provides "local variables" that persist through the different stages of a particular Endpoint. A Mats Endpoint can, as explained, be invoked by a Stage of another Endpoint, or can be targeted by an Initiator. A message from an Initiator initiates a Mats Flow. The Mats system thus provides "invokable", general services, which are coded in a very straight down, linear manner, and thus resembles ordinary synchronous HTTP/"REST" endpoints both in coding style and usage. By running multiple instances of the microservice containing Mats Endpoints, you gain high availability. The ideas behind Mats are inspired by [SEDA](https://en.wikipedia.org/wiki/Staged_event-driven_architecture) and elements of the [Actor model](https://en.wikipedia.org/wiki/Actor_model), as well as the [EIP book](https://www.enterpriseintegrationpatterns.com/), where many of the patterns/features are relevant to Mats (and most other message oriented architectures), but the ["Process Manager"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ProcessManager.html), and ["Request-Reply"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/RequestReply.html) with ["Return Address"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/ReturnAddress.html) probably relates the most to a Mats Endpoint - where Mats adds a call stack and state to the Request-Reply pattern, packaging it in an API that lets you forget about these intricacies.
>

A *Mats Endpoint* is a messaging-based service that contains one or multiple *Stages*. Each stage is a small independent multi-threaded "server" that listens to incoming messages on a queue, and performs some processing on the incoming DTO. For *Request* messages it will then typically either send a *Reply* message to the queue specified below it on the incoming message's call stack (i.e. to the caller's next stage), or perform a Request by sending a message to a different Mats endpoint, who's Reply will then be received on the next stage of the present endpoint.

So, in the above diagram, we have 5 independent Mats Endpoints (A to D + Terminator), and an Initiator. Each of the Endpoints would typically reside on a different (micro-)service, i.e. in different codebases, running inside different processes, albeit the Terminator would typically reside in the same codebase as the initiating code. EndpointA and EndpointC consist of multiple stages.

In a Mats Endpoint consisting of multiple stages, the stages of the endpoint are connected with an Endpoint-specified *state object* ("STO") which is transparently passed along with the message flow. This is what the dashed lines represent - but the actual content of the state object follows the continuous lines, representing the logical message flows. (Actually, all those messages go to and from the message broker, but logically they go as shown). This state object gives the effect of having "local variables" that are present through all the different Stages of a particular Mats Endpoint.

The incoming messages to a Mats Endpoint is either from another Mats Stage, or they are sent from an *Initiator*. When an Initiator sends a message, it initiates a *Mats flow*, which typically end with a Reply to a *Terminator* endpoint, which is just an Endpoint whose final stage does not perform any further request or reply.

## Code example for EndpointA

Here's an example of code for EndpointA, using plain Java to define it *(You may also define endpoints via the annotation-based [SpringConfig](mats-spring) if Spring is your cup of tea)*:
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
*(The full example is available in the [unit tests](mats-api-test/src/test/java/io/mats3/api_test/stdexampleflow).)*

First, we use the MatsFactory to create a "staged" Endpoint definition: The id of the Endpoint (which will become the queue name for the initial stage, with a prefix of "mats."), which class it will Reply with (the "return type"), and what class is used as the State object.

We then add stages to this Endpoint. The first Stage will become the entry point for the Endpoint, and is called the *initial Stage*. When an initiation or another Stage targets this Endpoint, it is the initial Stage that receives the message. The specified incoming type of this Stage is therefore what the Endpoint expects (the "parameter type" for the Endpoint). The following lambda is the *user code* for this Stage, receiving a Mats context, the state object, and the incoming request argument value. This lambda performs some logic, and then performs a Request to EndpointB using `context.request(<endpointId>, <requestDTO>)`.

The next stage again specifies which incoming type it expects, which is the type of the Reply from EndpointB, and then the user code lambda, receiving the Mats context, the state object, and the reply value from EndpointB. Again, the user code performs some logic, and then performs a Request to EndpointC.

The next and final stage is specified using `lastStage(..)`, which is a convenience that lets you Reply using a normal `return` statement, as well as invoking `ep.finishSetup()` for you to tell Mats that all stages are now added. The stage again specifies which incoming type it expects, which is the type of the Reply from EndpointC, and then the user code lambda, which gets the context, the state object, and the reply value from EndpointC. And again, the user code performs some logic, and simply `return` its finished Reply DTO instance, which will be passed to whoever invoked EndpointA.

*(Note: The return-functionality is literally a shorthand for invoking `context.reply(..)` - you could instead have added this stage by using `ep.stage(..)` as with the two previous Stages, and used `context.reply(..)` within the user code - but aside from not looking as nice, you'd then also have to manually tell Mats that the endpoint was finished set up by invoking `ep.finishSetup()` after the last Stage was added. You can also use `context.reply(..)` instead of `context.request(..)` in an earlier Stage to do early exit from this Endpoint.)*

The State type that the endpoint was specified with appears in all three stages' user code lambda as an instance passed in from Mats: For the initial stage, this will be a newly created instance of the State class - all fields having their default value. However, if you set any fields on this state object in a Stage, they will "magically" be present in the subsequent Stages.

Notice that you do not need to explicitly handle the state object - it is just provided to you. Also, you do not need to state which queue a requested service should post its Reply to - this is known by the Mats system, since it will simply be "this stage + 1". This also means that you do not need to think of which queue this Endpoint's Reply should go to - this is handled by Mats and the message's stack, where the requesting stage will (automatically, by Mats) have specified the correct queue to post the Reply to. And this immediately points out that this service is *general* in that any initiation, or any stage of other Endpoints, can target it, as long as it can create a Request DTO and accept the resulting Reply DTO.

If you squint really hard, this could look like a single service method, receiving a DTO, performing two synchronous/blocking service calls, and then return a DTO as response.

*(Note: There are several other features, like fire-and-forget style `initiator.send(..)`, "broadcast" pub/sub (topic) `matsFactory.subscriptionTerminator(..)` with corresponding `initiator.publish(..)`, `context.next()` to jump to the next stage which can be good if you conditionally need to perform a request or not, "sideloads" for larger Strings or byte arrays, "trace properties" which works a bit like thread locals or "flow scoped variables", interactive and non-persistent flags which concerns the entire Mats flow, and much more..)*

## Stages are independent

As mentioned earlier, each of the stages of a Mats Endpoint is an independent "server", which means that each stage runs with a small, independent thread pool, each thread competing to pick off messages from its specific queue from the message broker, and sticking new messages onto other Endpoints' queues. Furthermore, to ensure high availability and scalability, you should run multiple nodes of each service. *(Notice, for added insight, that the queue names in the JMS implementation of Mats are straightforward mappings: An Endpoint's initial (or sole) Stage uses the queue name `mats.<endpointId>`, while for any subsequent stages, the queue name is `mats.<endpointId>.stage<sequence#>`)*

The big difference between this setup using a set of Mats Endpoints, and the alternative employing a set of REST services, is that every Stage of every Mats Endpoint is completely independent and stateless - and that all request/reply logic is fully asynchronous. All state within a Mats Flow is contained in the passed messages. A Mats flow where the message to the initial Stage of EndpointA was picked up by node1, could easily continue on node2 if the Reply message from EndpointB happened to be picked up by one of that node's StageProcessors for "EndpointA.stage1". If you midway in a whole heap of Mats flows running through these Endpoints suddenly take down "node1" of all services, *nothing happens*: All messages would then just not be picked up by any node1 anymore, but instead the StageProcessors on all services' "node2" instance would get some more to do. Even if such take down happened midway in the processing of some Stages running on a node1, still nothing would happen: The messages would (transactionally) be rolled back by the Message Broker, and retried on the surviving node(s). 

## Messages and envelopes

Technically, the Request and Reply, and well as the State objects, are serialized within an envelope, and this envelope is the actual message passed on the queues. The system uses the envelope to carry the call stack with the different state objects, in addition to the actual message DTO, but also gives the ability to tack on information that carries through the Mats flow from start to finish. An immediate benefit for developers is the *TraceId* string, which is a mandatory parameter for an initiation. This ties together all processing throughout the flow, and with a common logging system for all your services, you immediately gain full insight into the processing of this Mats flow through the different parts of the distributed system.

*(Note: Since the envelope contains all the data and state, this should imply that if you just serialized a message envelope to disk, you could "stop" a Mats Flow, and then at a later time - possibly after having rebooted your entire server park - "restart" it by deserializing the message envelope and put it onto the correct queue. This functionality is indeed available, called "stash" and "unstash".)*

## Simple Message-based Inter Service Communications 

The intention is that coding a Mats service Endpoint *feels like* coding a normal service method that performs synchronous RPC (e.g. REST) calls to other services, while reaping all the benefits of an asynchronous Message-Oriented communication paradigm. In addition, each stage is independently transactional, making a system made up of these services exceptionally resilient against any type of failure. The use of message queues, along with the stateless nature of the system, makes high availability and scalability as simple as running the same endpoint on more than one node.

The Mats API consist nearly solely of interfaces, not depending on any specific messaging platform's protocol or API. In the current sole implementation of the Mats API, which employs the Java Message Service API (JMS v1.1) as the queue interface, each stage has a set of threads that receives messages using a JMS MessageConsumer.

## Production Ready

This code has for several years been running in production as the sole inter service communication layer in a quite large financial system consisting of >40 "micro" services and several hundred Mats Endpoints, and before that (since 2014) the system ran on a previous incarnation of the underlying ideas. Several million messages are produced and consumed each day. The Message Broker in use in this production setup is Apache ActiveMQ, but all unit and integration tests also run on Apache Artemis (formerly JBoss HornetQ, and what RedHat AMQ is built on). *(RabbitMQ with its JMS client is not yet advised, due to some differing semantics in this broker, e.g. infinite, tight-loop retries without DLQ)*.

# Rationale

Going a bit deeper into the rationale behind Mats, we'll look at Message Oriented architectures vs. the more standard synchronous blocking RPC typically employed in a multi-service architecture. 

In a multi-service architecture (e.g. using <i>Micro Services</i>) one needs to communicate between the different services. The golden hammer for such communication is REST-ish services employing JSON over HTTP.

*(A note here: The arguments here are for service-mesh internal IPC/RPC communications. For e.g. REST endpoints facing the user/client, there is a "synchronous-2-Mats-bridge" called the [MatsFuturizer](mats-util/src/main/java/io/mats3/util/MatsFuturizer.java), from which you can perform a Mats Request initiation which returns a Future, which will be resolved when the final Reply message comes back to the same node. There is also the library [MatsSockets](https://github.com/centiservice/matssocket) that is built on top of Mats and WebSockets, which pulls the asynchronousness of Mats all the way out to the end-user client, with client libraries available for JavaScript and Dart/Flutter)*

*Asynchronous Message Oriented architectures* are superior to synchronous REST-based systems in a number of ways, for example:

* **High Availability**: For each queue, you can have listeners on several service instances on different physical servers, so that if one service instance or one server goes down, the others are still handling messages.
* **[Location Transparency](http://www.reactivemanifesto.org/glossary#Location-Transparency)**: *Service Location Discovery* is avoided, as messages only targets the logical queue name, without needing information about which nodes are currently consuming from that queue.
* **Scalability** / **Elasticity**: It is easy to [increase the number of nodes](http://www.reactivemanifesto.org/glossary#Replication) (or listeners per node) for a queue, thereby increasing throughput, without any clients needing reconfiguration. This can be done runtime, thus you get *elasticity* where the cluster grows or shrinks based on the load, e.g. by checking the size of queues.
* **Handles load peaks** / **Prioritization**: Since there are queues between each part of the system and each stage of every process, a sudden influx of work (e.g. a batch process) will not deplete the thread pool of some service instance, bringing on cascading failures backwards in the process chain. Instead messages will simply backlog in a queue, being processed as fast as possible. At the same time, message prioritization will ensure that even though the system is running flat out with massive queues due to some batch running, any humans needing replies to queries will get in front of the queue. *Back-pressure* (e.g. slowing down the entry-points) can easily be introduced if queues becomes too large.
* **Transactionality**: Each endpoint has either processed a message, done its work (possibly including changing something in a database), and sent a message, or none of it.
* **Resiliency** / **Fault Tolerance**: If a node goes down mid-way in processing, the transactional aspect kicks in and rolls back the processing, and another node picks up. Due to the automatic retry-mechanism you get in a message based system, you also get *fault tolerance*: If you get a temporary failure (database is restarted, network is reconfigured), or you get a transient error (e.g. a concurrency situation in the database), both the database change and the message reception is rolled back, and the message broker will retry the message.
* **Monitoring**: All messages pass by the Message Broker, and can be logged and recorded, and made statistics on, to whatever degree one wants.
* **Debugging**: The messages between different parts typically share a common format (e.g. strings and JSON), and can be inspected centrally on the Message Broker.
* **Error/Failure handling**: Unprocessable messages (both in face of coded-for conditions (validation), programming errors, and sudden external factors, e.g. db going down) will be refused and, after multiple retries, eventually put on a *Dead Letter Queue*, where they can be monitored, ops be alerted, and handled manually - centrally. Often, if the failure was some external resource being unavailable, a manual (or periodic attempts at) retry of the DLQ'ed message will restart the processing flow from the point it DLQ'ed, as if nothing happened.

However, the big pain point with message-based communications is that to reap all these benefits, one need to fully embrace asynchronous, multi-staged distributed processing, where each stage is totally stateless, but where one still needs to maintain a state throughout the flow.

## Standard Multi Service Architecture employing REST RPC 

When coding service-mesh internal REST endpoints, one often code locally within a single service, typically in a straight down, linear, often "transaction script" style - where you reason about the incoming Request, and need to provide a Response. It is not uncommon to do this in a synchronous fashion. This is very easy on the brain: It is a linear flow of logic. It is probably just a standard Java method, using e.g. a Servlet or Spring's @RequestMapping. All the code for this particular service method resides in the same project. You can structure your code, invoke service beans or similar. When you need information from external sources, you go get it: Either from a database, or from other services by invoking them using some HttpClient. *(Of course, there are things that can be optimized here, e.g. asynchronous processing instead of old-school blocking Servlet-style calls, invoking the external resources asynchronously by using e.g. Futures or other asynchronous/reactive mechanisms - but that is not really the point here!)*. For a given service, all logic can be mostly be reasoned about locally, within a single codebase.

In particular, *state* is not a problem - it is not even a thought - since that is handled by the fact that the process starts, runs, and finishes on the same JVM - typically within a single method. If you first need to get something from one service, then use that information to get something from another service, and finally collate the information you have gathered into a response, you simply just do that: The implicit state between the stages of the process is naturally handled by local variables in the method, along with any incoming parameters of the method which also just naturally sticks around until the service returns the response. *(Unless you start with the mentioned asynchronous optimizations, which typically complicate such matters - but nevertheless: State is handled by the fact that this process is running within a single JVM)*.

However, the result is brittle: So, lets say your service invokes a second service. And then that second service again needs to invoke a third service. You now have three services that all have state on their respective stacks (either blocking a thread, or at any rate, having an asynchronous representation of the state on all three services). And, there is multiple stateful TCP connections: From the initial requester, to the receiving service, then to the second service, and then to the third service. The process is not finished until all services have provided their response, and thus the initial requester finally gets its response. If one service intermittently becomes slow, you face the situation of resource starvation with subsequent cascading failures percolating backwards in your call chains, which now might resource-starve other services that otherwise wouldn't be affected. If any of the service nodes fail, this will typically impact many ongoing processes, all of them failing, and the currently ongoing processes potentially being left in an intermediate state. Blindly doing retries in the face of such errors is dangerous too: You may not know whether the receiver actually processed your initial request or not: Did the error occur before the request was processed, while it was processed, or was it a problem with the network while the response was sent back? Even deploys of new versions can become a hazard, and you must at least carefully perform graceful shutdowns letting all pending requests finish while not accepting any new requests.

## Message-Oriented Multi Service Architecture

With a Messaging-Oriented Architecture, each part of the total process would actually be a separate subprocess. Instead of the above described nested call based logic (which is easy on the brain!), you now get a bunch of message processing stages which will be invoked in sequence: The process initiator puts a message on a queue, and another processor picks that up (probably on a different service, on a different host, and in different code base) - does some processing, and puts its (intermediate) result on another queue. This action may need to happen multiple times, traversing multiple services. This is the ["Pipes and Filters"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PipesAndFilters.html) architectural style as defined in the EIP book. This multiple *"consume message from inbound queue - process the message - post new message to outbound queue"* processing flow is harder to reason about: State is now not implicit, but needs to be passed along with the messages. And these stages will typically be within separate code bases, as they reside in different services.

You gain much: Each of these stages are independent processes. There is no longer a distributed blocking state residing through multiple services, as the queues acts as intermediaries, each stage processor having fully performed its part of the total process before posting the (intermediate) result on a queue (and then goes back fetching a new message to process from its incoming queue). Each service can fire up a specific number of queue processors utilizing the available resources optimally. There cannot be a cascading failure scenario, at least not until the message queue broker is exhausted of space to store messages - which has a considerably higher ceiling than the thread and memory resources on individual processing nodes. The operations of consuming and posting messages are transactional operations, and if you don't mess too much up, each stage is independently transactional: If a failure occurs on a node, all processing having occurred on that node which have not run to completion will be rolled back, and simply retried on another node: The total process flow does not fail.

However, you also loose much: The simple service method where you could reason locally and code in a straight down manner, with state implicitly being handled by the ordinary machinery of the thread stack and local variables, now becomes a distributed process spread over several code bases, and any state needed between the process stages must be handled explicitly. If you realize that you in the last stage need some extra information that was available in the initial stage, you must wade through multiple code bases to make sure this piece of information is forwarded through those intermediate stages. This also give a result that such processing stages aren't generic - they are bespoke to a particular flow. And let's just hope that you don't want to refactor the entire total process.

Thus, the overall structure in a message oriented architecture typically ends up being hard to grasp, quickly becomes unwieldy, and is difficult to implement due to the necessary multiple code bases that needs to be touched to implement/improve/debug a flow. Most projects therefore choose to go for the much easier model of synchronous processing using some kind of blocking REST-like RPC: In this model one can code linearly, employing blocking calls out to other, often *general* services that are needed - a model which every programmer intuitively know due to it closely mimicking local method invocations. However, the familiarity is basically the only positive aspect of this code style.

## What MATS brings to the table

**The main idea of the Mats library is to let developers code message-based endpoints that themselves may "invoke" other such endpoints, in a manner that closely resembles the familiar synchronous "straight down" linear code style.** In this familiar code style, all the state built up before the call out to the requested service is present after the service returns: Envision a plain Java method that invokes other methods, or more relevant, a REST service which invokes other REST services. With Mats, such an endpoint, while *looking* like a "straight down" synchronous method, is actually a multi-stage *(each stage processed by consumers on a specific queue)*, asynchronous *(the flow passes from queue to queue, never synchronously waiting for a reply)*, fully stateless *(as state resides "on the wire")* message oriented distributed process.
**Effectively, the Mats library gives you a mental coding model which feels like home, and coding is really straightforward (both literally and figuratively!), while you reap all the benefits of a Message-Oriented Architecture.**

# Examples

Some examples taken from the [unit tests](mats-api-test). Notice the use of the JUnit Rule *Rule_Mats*, which sets up an ActiveMQ in-memory server and creates a JMS-backed MatsFactory based on that.

In these examples, all the endpoints and stages are set up in one test class, and when invoked by the JUnit runner obviously runs on the same machine - but in actual usage, you would typically have each endpoint run in a different process on different nodes. The DTO-classes, which acts as the interface between the different endpoints' requests and replies, would in real usage be copied between the projects (in this testing-scenario, one DTO class is used as all interfaces).

It is important to realize that each of the stages - also each stage in multi-stage endpoints - are handled by separate threads, and the state, request and reply objects are not shared references within a JVM - they are marshalled with the message that are passed between each stage of the services. *All information for a process resides as contents of the Mats envelope and data ("MatsTrace") which is being passed with the message.* In particular, if you have a process running a three-stage Mats endpoint which is deployed on two nodes A and B, for a particular request, the first stage might execute on node A, while the next stage on node B, and the last stage on node A again.

This means that what *looks like* a blocking, synchronous request/reply "method call" is actually fully asynchronous, where a reply from an invoked service will be handled in the next stage by a different thread, quite possibly on a different node if you have deployed the service in multiple instances.

## Simple send-receive

The following class exercises the simplest functionality: Sets up a Terminator endpoint, and then an initiator sends a message to that endpoint. *(This example neither demonstrate the stack (request/reply), nor state keeping - it is just the simplest possible message passing, sending some information directly from an initiator to a terminator endpoint)*

ASCII-artsy, it looks like this:
<pre>
[Initiator]   {sends to}
[Terminator]
</pre>

```java
public class Test_SimplestSendReceive  {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupTerminator() {
        // A "Terminator" is a service which does not reply, i.e. it "consumes" any incoming messages.
        // However, in this test, it countdowns the test-latch, so that the main test thread can assert.
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @Test
    public void doTest() {
        // Send message directly to the "Terminator" endpoint.
        DataTO dto = new DataTO(42, "TheAnswer");
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .send(dto));

        // Wait synchronously for terminator to finish. NOTE: Such synchronicity is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(dto, result.getData());
    }
}
```

## Simple request to single stage "leaf-service"

Exercises the simplest request functionality: A single-stage service is set up. A Terminator is set up. Then an initiator does a request to the service, setting replyTo(Terminator). *(This example demonstrates one stack level request/reply, and state keeping between initiator and terminator)*

ASCII-artsy, it looks like this, the line (pipe-char) representing the state that goes between Initiator and Terminator, but which is kept "on the wire" along with the message flow, i.e. along with the request out to Service, and then reply back to Terminator:
<pre>
[Initiator]    {request}
 |  [Service]  {reply}
[Terminator]
</pre>

```java
public class Test_SimplestServiceRequest {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        // This service is very simple, where it simply returns with an alteration of what it gets input.
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        // A "Terminator" is a service which does not reply, i.e. it "consumes" any incoming messages.
        // However, in this test, it resolves the test-latch, so that the main test thread can assert.
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void doTest() {
        // Send request to "Service", specifying reply to "Terminator".
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish. NOTE: Such synchronicity is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());
    }
}
```

## Multi-stage service and multi-level requests

Sets up a somewhat complex test scenario, testing request/reply message passing and state keeping between stages in several multi-stage endpoints, at different levels in the stack. The code is a tad long, but it should be simple to read through.

The main aspects of Mats are demonstrated here, notice in particular how the code of <code>setupMainMultiStagedService()</code> *looks like* - if you squint a little - a linear "straight down" method with two "blocking requests" out to other services, where the last stage ends with a return statement, sending off the Reply to whoever invoked it.
<p>
Sets up these services:
<ul>
<li>Leaf service: Single stage: Replies directly.
<li>Mid service: Two stages: Requests "Leaf" service, then replies.
<li>Main service: Three stages: First requests "Mid" service, then requests "Leaf" service, then replies.
</ul>
A Terminator is also set up, and then the initiator sends a request to "Main", setting replyTo(Terminator).
<p>
ASCII-artsy, it looks like this, the lines representing the state that goes between the Initiator and Terminator and the stages of the endpoints, but which is kept "on the wire" along with the message flow through the different requests and replies:
<pre>
[Initiator]              {request}
 |  [Main S0 (init)]   {request}
 |   |  [Mid S0 (init)]  {request}
 |   |   |  [Leaf]       {reply}
 |   |  [Mid S1 (last)]  {reply}
 |  [Main S1]          {request}
 |   |  [Leaf]           {reply}
 |  [Main S2 (last)]   {reply}
[Terminator]
</pre>

**Again, it is important to realize that the three stages of the Main service (and the two of the Mid service) are actually fully independent messaging endpoints (with their own JMS queue when run on a JMS backend), and if you've deployed the service to multiple nodes, each stage in a particular invocation flow might run on a different node.**

The Mats API and implementation sets up a call stack that can be of arbitrary depth, along with "stack frames" whose state flows along with the message passing, so that you can code *as if* you were coding a normal service method that invokes remote services synchronously.


```java
public class Test_MultiLevelMultiStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE_MAIN = MatsTestHelp.endpointId("MAIN");
    private static final String SERVICE_MID = MatsTestHelp.endpointId("MID");
    private static final String SERVICE_LEAF = MatsTestHelp.endpointId("LEAF");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupLeafService() {
        // Create single-stage "Leaf" endpoint. Single stage, thus the processor is defined directly.
        MATS.getMatsFactory().single(SERVICE_LEAF, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Returns a Reply to the calling service with a alteration of incoming message
                    return new DataTO(dto.number * 2, dto.string + ":FromLeafService");
                });
    }

    @BeforeClass
    public static void setupMidMultiStagedService() {
        // Create two-stage "Mid" endpoint
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory()
                .staged(SERVICE_MID, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Mid" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = 10;
            sto.number2 = Math.PI;
            // Perform request to "Leaf" Service...
            context.request(SERVICE_LEAF, dto);
        });

        // Next, and last, stage, receives replies from the "Leaf" service, and returns a Reply
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Leaf" Service has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(new StateTO(10, Math.PI), sto);
            // Returning Reply to calling service.
            return new DataTO(dto.number * 3, dto.string + ":FromMidService");
        });
    }

    @BeforeClass
    public static void setupMainMultiStagedService() {
        // Create three-stage "Main" endpoint
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory()
                .staged(SERVICE_MAIN, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Main" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            // Perform request to "Mid" Service...
            context.request(SERVICE_MID, dto);
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Mid" Service has replied.
            // Assert that state variables set in previous stage are still with us.
            Assert.assertEquals(Integer.MAX_VALUE, sto.number1);
            Assert.assertEquals(Math.E, sto.number2, 0);
            // Changing the state variables.
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            // Perform request to "Leaf" Service...
            context.request(SERVICE_LEAF, dto);
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // .. "continuing" after the "Leaf" Service has replied.
            // Assert that state variables changed in previous stage are still with us.
            Assert.assertEquals(Integer.MIN_VALUE, sto.number1);
            Assert.assertEquals(Math.E * 2, sto.number2, 0);
            // Returning Reply to "caller"
            // (in this test it will be what the initiation specified as replyTo; "Terminator")
            return new DataTO(dto.number * 5, dto.string + ":FromMainService");
        });
    }

    @BeforeClass
    public static void setupTerminator() {
        // A "Terminator" is a service which does not reply, i.e. it "consumes" any incoming messages.
        // However, in this test, it resolves the test-latch, so that the main test thread can assert.
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });
    }

    @Test
    public void doTest() {
        // :: Arrange
        // State object for "Terminator".
        StateTO sto = new StateTO((int) Math.round(123 * Math.random()), 321 * Math.random());
        // Request object to "Main" Service.
        DataTO dto = new DataTO(42 + Math.random(), "TheRequest:" + Math.random());

        // :: Act
        // Perform the Request to "Main", setting the replyTo to "Terminator".
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(SERVICE_MAIN)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish.
        // NOTE: Such synchronous wait is not a typical Mats flow!
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();

        // :: Assert
        // Assert that the State to the "Terminator" was what we wanted him to get
        Assert.assertEquals(sto, result.getState());
        // Assert that the Mats flow has gone through the stages, being modified as it went along
        Assert.assertEquals(new DataTO(dto.number * 2 * 3 * 2 * 5,
                        dto.string + ":FromLeafService" + ":FromMidService"
                                + ":FromLeafService" + ":FromMainService"),
                result.getData());
    }
}
```

## MatsFuturizer - the sync-async bridge

This class tests the most basic [`MatsFuturizer`](mats-util/src/main/java/io/mats3/util/MatsFuturizer.java) situation: Sets up a single-stage endpoint, and then uses the MatsFuturizer to invoke this service, which returns a `CompletableFuture` that will be completed when the Reply comes back from the requested endpoint.

How does this work in a multi-node setup, where the Reply should randomly come to this node, or any other node? Behind the scenes, the MatsFuturizer sets up a `subscriptionTerminator` (a topic) that has the nodename as a part of the topic name. Furthermore, the request uses the special `replyToSubscription` feature of an initiation, targeting this node-specific topic. Thus, the Reply will only be picked up by this node.

Why a topic? Not because of a topic's "broadcast" functionality (it is only a single consumer on this specific node that listens to this specific topic), but the topic's "if you're not there, the message is gone" effect: If the node is gone in the time since the Mats flow was initiated, then so is obviously the Future's waiting thread, so no need to have any messages stuck on the MQ.

There are multiple modes of operation of the MatsFuturizer, but all of them share the fact that completion of the future cannot be guaranteed - simply because the node might be booted or die while the Mats flow is running. You can however decide whether the Mats flow should run using persistent ("guaranteed") or non-persistent messaging.

The futurizer should mostly be used for "GET-style" requests.

```java
public class Test_MatsFuturizer_Basics {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, msg) -> new DataTO(msg.number * 2, msg.string + ":FromService"));
    }

    @Test
    public void normalMessage() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                "traceId", "OneSingleMessage", SERVICE, DataTO.class, dto);

        Reply<DataTO> result = future.get(1, TimeUnit.SECONDS);

        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.reply);
   }
}
```

# Try it out!

If you want to try this out in your project, I will support you!

-Endre, endre@stolsvik.com
