# Mats<sup>3</sup> - Message-based Asynchronous Transactional Staged Stateless Services

*Introducing "MOARPC" - Message-Oriented Asynchronous Remote Procedure Calls!*

Webpage: https://mats3.io/

<img src="docs/img/Mats3Logo-text-to-path.svg" alt="Mats3 logo" width="100"/>

*Mats<sup>3</sup>* is a Java library that facilitates the development of asynchronous, stateless (or stateful, depending
on your point of view), multi-stage, message-based services. Mats Endpoints immediately provide all the benefits you get
from a fully asynchronous messaging-based architecture, while being *almost* as simple to code as blocking, synchronous
JSON-over-HTTP "REST" endpoints.

*To explore the library, check out the [Explore Mats<sup>3</sup>](https://mats3.io/explore/) page. In particular, there
is [tooling for Mats<sup>3</sup> using JBang](https://mats3.io/explore/jbang-mats/): JBang makes it possible to make
small self-executable source files. With the all-in-one `mats-jbangkit` dependency, you can literally pull up a Mats
Endpoints in 10 lines, and start an ActiveMQ Classic instance with a single command: `jbang activemq@centiservice`.
Also, the
source is a good place to learn about Mats<sup>3</sup>, explained [here](https://mats3.io/explore/mats-source-code/).*

To use Mats in a project, fetch [`mats-impl-jms`](https://mvnrepository.com/artifact/io.mats3/mats-impl-jms)
from [Maven Central](https://mvnrepository.com/artifact/io.mats3).

Versioning:

* **v2**: Java 21+, `jakarta.jms`
* **v1**: Java 11+, `javax.jms`

License: [Apache License, Version 2.0](LICENSE)

If you find Mats<sup>3</sup> interesting, you might want to check out the companion
project [MatsSocket](https://github.com/centiservice/matssocket), which provides WebSocket based bidirectional
communication between a JavaScript/Web or Dart/Flutter client, and a backend using Mats<sup>3</sup>.<br/>
There's also the
[MatsBrokerMonitor](https://github.com/centiservice/matsbrokermonitor) project, which provides a
Mats<sup>3</sup>-specific HTML-based monitor and control surface of the queues and DLQs on the message broker.

# Documentation

Go to the [Documentation](https://mats3.io/docs/) page of [mats3.io](https://mats3.io/).

The different modules are described in the [README-development.md](README-development.md), which also contains
instructions on how to build, develop, run unit tests and integration tests, of the library itself.

# What is Mats?

There's a document going into details [here](https://mats3.io/background/what-is-mats/), and for the rationale behind
Mats [here](https://mats3.io/background/rationale-for-mats/).

Description of what Mats is, in multiple short forms, from several different angles:

### A picture is worth...

![Standard Example Mats Flow](docs/img/StandardExampleMatsFlow-halfsize-pagescaled.svg)

The above diagram illustrates a set of endpoints A to D, residing in different services.

It can be viewed as an "outer" service invoking some REST EndpointA, which as part of its execution again invokes REST
EndpointB and EndpointC, and EndpointC as part of its execution invoking EndpointD. The dotted lines then represent
blocking waits on the other endpoints' responses.

Or it can be viewed as _Mats Endpoints_ (light blue boxes) invoking other Mats Endpoints. The dotted lines now
represents the state which are transparently passed between the otherwise completely independent _Stages_ (dark blue
boxes) of the Endpoints. However, the state is actually passed along in the envelopes of the messages' flow through the
different Endpoints' Stages. It thus doesn't matter if the `initial` stage of `EndpointA` is running on the Server1
replica, while `stage1` of the same `EndpointA` is running on the Server2 replica, and the last stage `stage2` is
running on Server3, or back on Server1: The state is always carried along with the message, using a literal call stack
to keep track of the different Endpoints' states. Even if Server1 goes down after having processed the `initial` stage
of `EndpointA`, this doesn't matter: The state is still carried along with the message, and the `stage1` of `EndpointA`
will pick up and get its state from the message's envelope when it performs the processing of the reply from
`EndpointB`.

Thus, any Endpoint-local state variables present on the `initial` stage of `EndpointA` will still be present for
`stage1` of `EndpointA`, and continue to be present on `stage2` of `EndpointA` - just as if you were coding
synchronously with blocking calls.

The full execution from Initiator to Terminator (where no new message is issued) is called a _Mats Flow_.

Unit test code for this example can be
found [here](mats-api-test-2/src/test/java/io/mats3/api_test/stdexampleflow/Test_StandardExampleMatsFlow.java).

### Better Inter Service Communication with Messaging

In a system made up of multiple services, e.g. a microservice architecture, asynchronous messaging gives many benefits
compared to the more classical synchronous JSON-over-HTTP-based communications - read more about this in
[Rationale For Mats](docs/RationaleForMats.md).

Mats lets you use messaging without having to massively change your mental model of how to create inter service
message based endpoints: You might code your Mats Endpoint to receive a request, invoke a few collaborating services,
calculate the result based on values both from the request and the responses from the services you invoked, and then
return the result, very much like you would code a synchronous HTTP endpoint.

### Multi-stage, message-based Endpoints

A Mats Endpoint may consist of multiple _Stages_, where each Stage is an independent consumer and producer of
messages, each stage consuming from a stage-specific queue on a message broker. The message queue name is simply a
direct mapping from the StageId, prefixed with (default) "mats.".

### Invokable message-based Endpoints

A Mats Endpoint's Stage may invoke another Mats Endpoint by issuing a Request-call (`processContext.request(..)`)
targeting the EndpointId of the Endpoint you want to invoke. The Reply from this Request will be received by next Stage
of the Endpoint.

### Messaging with a Call Stack!

Invoked Mats Endpoints does not need to know who called them, they just return their result which is packaged up in a
message called a Reply. The Stage that performs the invocation doesn't have to specify which StageId the Reply should go
to, as Mats already knows this: It is the next stage of the Endpoint. The invoked Endpoint doesn't have to look up
the Reply queue address, as it is automatically fetched from the message's envelope's call stack.

When a `Stage N` performs such a Request to another Mats Endpoint, a State Object is left behind on the call stack
within the message's envelope, which carries the information that is needed from `Stage N` to `Stage N+1`. When
`Stage N+1` receives the Reply from the invoked Endpoint, the state object which was present at `Stage N` will
"magically" be reconstituted and provided to it, _as if it was a blocking call with local variables._

There is no theoretical limit to the nesting level of the call stack - but there is a "stack overflow" protection
mechanism which kicks in if an Endpoint for example ends up invoking itself, to protect the system from coding errors.

### Stateless

The entire state of a _Mats Flow_ is kept in the messages' envelope. No state is kept on the service instances
(nodes / pods / hosts / servers - whatever unit carries the service instances' JVMs).

This means that a service instance may be shut down without any consequences, and without the need of taking it out of
any cluster or load balancer - it simply disconnects from the message broker. If there are multiple instances, the other
instances will just continue consuming from those queues as if nothing has happened (they will get some more load than
when they shared it with the now-offline instance). If there are no instances of this service left, inbound messages
targeting this service's Endpoints' Stages will just be queued up on the broker's queues - and when a service instance
comes back online, its Mats Endpoints will start consuming again, and the Mats Flows will continue as if nothing
happened. This makes maintenance, high availability, and deployment of new versions of the services effortless.

### Stateful

The multiple Stages of a Mats Endpoint are connected with a State Object, which are meant to emulate the local variables
you'd have in the handling method of an ordinary HTTP-endpoint which performs synchronous HTTP calls to other services.

### Truly asynchronous, "message-driven continuations"

The _asynchronous_ aspect of Mats3 isn't about "non-blocking threads", as in async-await, or Futures/Promises. It is
about each stage of the Mats Flow being a completely disparate execution engine, having no knowledge of the other stages
in the Endpoint other than as metadata in the message's envelope. Each Stage of each Endpoint is a small and separate
server with its own separate threads, where it doesn't matter if there are one, or a hundred, JVMs on separate machines
running those threads. (You should obviously have more than one machine due to failure resilience, but that's not the
point of this discussion!)

When a `Stage N` of some Endpoint performs a request to another Endpoint, no thread will explicitly be waiting for the
Reply. The thread that executed `Stage N` for this Flow will go back to pick up new messages for the same `Stage N`.
When the requested Endpoint eventually replies, the Reply will be put on the incoming queue for `Stage N+1`, where
another thread of a StageProcessor for `Stage N+1`, possibly on another node, will pick it up and continue the execution
for this Flow.

However, when coding, the Mats Endpoint _feels like_ **synchronously** invoking another Endpoint: You "invoke" (i.e.
send a request message to) another Endpoint, and then continue the code with the reply of that invoked Endpoint right
below the invocation. _Visually_ and _mentally_ it feels like a synchronous RPC call.

Contrast this with the traditional meaning of _asynchronous_ (callbacks, async/await, Futures/Promises), where the key
point is to not block an expensive OS-thread while waiting for some external resource. However, even though you
alleviate the blocking of threads, you will still have state stored in memory on that node in some async context object.
If the server crashes, all those processes either kept in thread's call stack, or in async contexts, are lost -
typically without trace, so you'll have to find some way to pinpoint which process instances went down along with the
server. In Mats, the entire process state is kept in the messages' envelope. After the message has been sent, the thread
is free to pick up new messages, since the state is kept in the message's envelope which is stored on the message
broker. The server that did the processing is now free to crash!

And indeed, if the server crashes _while_ executing a Stage, the message broker will ensure that the message will be
retried on another server, and the state will again be reconstituted from the message's envelope residing on the MQ. As
long as you've managed to initiate a Mats Flow, the message broker has taken over the responsibility of getting it to
some finish line: Either retries will get it past the hiccup, or the message will eventually be DLQed, so that a human
can investigate the problem. **A Mats Flow will _never_ be lost.**

### Distributed

A Mats Flow's execution will be spread out in pieces, over the Stages of the Endpoints involved in the Flow, and over
the instances of the services that holds those Endpoints. If you could visually follow along, each Mats Flow weaves a
single line through the _Mats Fabric_, jumping from StageProcessor to StageProcessor through the different instances of
the services' Endpoints' Stages. However, this is just an emergent property of the system: When you code an Endpoint,
you code and reason locally within that Endpoint. The complexity of the system is efficiently abstracted away by the
Mats library, and you don't need to take it in while coding and focusing on your business logic.

When the abstraction breaks, it happens transparently, by chucking the affected message onto a DLQ. The DLQed messages
carries a lot of metadata about the initiation, flow, what Stage failed, exception that happened, inbound message, etc!
Also, every Mats Flow has a traceId, so that you can use your logging system to find the complete processing history of
the Mats Flow through the different services it has passed through, before succeeding or failing.

Each Stage's concurrency, i.e. the number of StageProcessors running, can be tuned independently to handle the total
load of all the Mats Flows. If there is a choke point, you'll find it using metrics: There will typically be a build-up
of messages on that Stage's queue (which also makes the head-of-queue message old). Intermittent small build-ups are
totally fine if the overall total latency of the involved Mats Flows doesn't become a problem - but otherwise you may
increase the concurrency for this particular Stage, or even increase the number of instances of the service holding the
problematic Endpoint. Or, it might be external resources at this stage making the problem, e.g. the database which is
employed: The query might be slow, or the database isn't beefy enough - this you will understand by looking at the stage
metrics, the StageProcessing being slow.

_Interactive_ Mats Flows, defined as "a human is waiting for the result" typically in some GUI, are given a "cut the
line"-flag, and it carries this through all the stages it passes through. This means that even if a massive batch of
Mats Flows is currently being processed with queues forming on several high-demand Endpoints, a user logging in to your
system's user portal, or looking up a customer on the backoffice application, will still get "immediate" response,
pretty much as if there was no load.

### Centralized error handling

Since Mats3 is built on top of an ordinary message broker system, you get the standard benefits of messaging: If a Mats
Flow, while weaving its line through the Mats Fabric, encounters an error, the message broker initiates automatic
retrying. But if this doesn't pan out either, the current message will "pop out" of the Mats Fabric, ending up on the
broker's Dead Letter Queue. (The broker should preferably be set up to have individual DLQs per queue). This is
monitored on the message broker.

If the problem was intermittent (e.g. a database was down for a while), and the error is now cleared, you can now
reissue the messages by moving them from the DLQ back to the original queue. The Mats Flow will simply continue as
if nothing happened - outside of a larger-than-normal latency spike!

There is a separate project providing a Mats3-specific view of the queues, topics and DLQs on the message
broker: [MatsBrokerMonitor](https://github.com/centiservice/matsbrokermonitor). With this tool, you can browse all
queues (and topics), seeing their queue length and age of head message. You can drill into a queue (typically DLQ) and
get an overview over all messages residing there, with some metadata for each message. You can then drill into a
specific message, and get all details about it including all their metadata, as well as the state objects in the call
stack, and current call DTO, and an outline of the path through the different Endpoints and Stages the mats flow has
passed through up till the DLQ point. You can reissue messages from the DLQ back to the original queue, or delete them
if they are no longer needed. There are hooks to provide links to the service-spanning logging system you employ, where
the messageId, flowId and "traceId" of the Mats Flow is available, so that you can easily "click into" your system-wide,
service-spanning logs for the specific Mats Flow in your logging system.

While a Mats3 system is developed and run in an exceptionally distributed fashion, any _error handling_ that inevitably
must be done by humans is centralized, with very good introspection and tooling.

### Familiar feel

The intention is that coding a message-based Mats Endpoint _feels like_ coding an ordinary HTTP-based endpoint where you
may synchronously invoke other endpoints as part of the processing. You code "linearly" and reason locally - but you
gain all features of a fully _Asynchronous Message Oriented Architecture_.

### Production Ready

Mats3 has since 2017 been running in production as the sole inter-service communication layer in a large financial
system consisting of >50 services and applications and well over thousand Mats Endpoints. The system has continuously
been expanded and altered through this time, being developed by a good set of developers, with very close to 100%
uptime. It was even moved from on-prem to one of the large cloud providers with no downtime, simply spinning up
instances in the cloud, and taking down the instances on-prem, without even the developers noticing when their service
was moved! It has multiple customer frontends, both web and apps, and several backend UIs for backoffice functions. Tens
of millions of messages are produced and consumed each day. The Message Broker in use in this production setup is Apache
ActiveMQ Classic, but all unit and integration tests also run on Apache ActiveMQ Artemis (formerly JBoss HornetQ, and
what Red Hat AMQ 7+ is built on). For more, read [this](docs/WhatIsMats.md), then [this](docs/RationaleForMats.md) - and
the documentation at [mats3.io](https://mats3.io/docs/).

## What Mats is not

Mats is not meant for e.g. stream processing of large amounts of small events. Mats is geared more towards "weighty"
processes and messages, and trades some performance for developer friendliness, transactionality and debugging features.
Solutions like Kafka might be what you want if you need to receive tens-of-thousands of events per second from your
massive fleet of IoT devices. Use Mats for the subsequent, more coarse-grained handling of the results of such
ingestion. _(That said, the throughput of a large Mats fabric with lots of Endpoints scales very well, and is only
limited by the throughput of your message broker setup, which scales pretty far. You can even use multiple MQs if need
be. Small "GET-style" request-replies to a single stage fetching some information for e.g. a frontend is typically
faster than over HTTP due to smaller header overhead, using non-persistent messaging over persistent TCP connections. It
will be your services' own databases and queries that limits the system you build._)

Also, Mats is not meant for events which require handling in a specific order, e.g. arrival order. The intention with
Mats is that you run multiple instances/replicas of each service. And on each instance, there are multiple
StageProcessors for each Stage. A Mats Flow typically consist of processing on multiple Endpoints with multiple Stages,
and there is no way to guarantee that two flows which are started in some close order, will run through those stages,
and finish in the same order they were started. This is exactly the same as with HTTP-based services, where you have
multiple instances of each service, with multiple threads handling the requests - but it is worth spelling out since
message-based systems are often associated with "first in, first out".

# Key Points

## No External Orchestration: "Just Code!"

Orchestration tools, in particular the external ones, divorce the overall business process logic from the code that
implements the actual operations. You then have to maintain the process in the orchestration tool, and the code in the
services. This is a recipe for complexity and opacity, where you cannot see the full flow in any one place, and you will
have to maintain two codebases: code and config.

Mats is a library, not a framework. You code your services as ordinary Java classes, and you use the Mats API to define
your Endpoints and Stages. You don't need to write any YAML, XML, or JSON to define your services, and you don't need to
write any orchestration code or config, possibly in some external orchestration tool, to direct the process logic. You
just code your services in a linear, "transaction script"-alike fashion, using sequential reasoning, where state
"magically" persists through any request-reply steps. The Mats library takes care of the rest.

## Decoupled, "Black Box" Endpoints

Each Endpoint is an independent entity, residing in a Service. They can be developed, tested, deployed, scaled, and
monitored independently of the other Endpoints in other services. Each Endpoint is decoupled from any other, only
defined by their Request DTO and Reply DTO. Any other services may invoke them (from an Endpoint, or via Initiation), as
long as they adhere to the contract of the DTOs. You can make "verify address" and "send email" Endpoints which multiple
services and flows may invoke (E.g. "Employee Onboarding", "Customer registration", "Order processing", "Billing").

The other services don't need to care about how the invoked service works, they just need to know the contract of the
DTOs for the Endpoint they are invoking. This is the same as with ordinary method calls in Java: You don't need to know
how the method you are calling works, you just need to know the method signature.

Contrast this with how messaging often is employed: Each processing point consumes from a queue, does some of the
process's total work, and put the intermediate result back on another queue, where the next processing point consumes
from. This however leads to processing points which are tightly coupled to the total flow, and you need to develop and
maintain the overall flow over several codebases. Any such processing point is thus not generic: Even if one of the
points are "verify address", there is no way to reuse this logic in another flow, as it is tightly coupled to the
overall process flow it was made for.

## Abstraction - Concentration of Complexity

Mats abstracts away the complexity of a distributed, asynchronous, message-driven, stateful, multi-stage processing
system. The complexity is hidden and concentrated in the Mats library, letting the developer focus on the business
logic.

Since it is Mats3 that produces the messages, and Mats3 that consumes the messages - the developer only seeing the
Request DTO and Reply DTO, as well as the state DTOs - Mats3 can add quite a bit of functionality to the messages and
the processing of them. For example, transactionality, error handling, metrics, tracing, and monitoring. There's
a boatload of metadata on the messages, and the processing of the messages is heavily instrumented.

There's a plugin solution, where you can add "interceptors" to the processing, which can do things like logging,
metrics, error handling, and even modify incoming and outgoing messages. This is a powerful tool for adding
cross-cutting concerns to the processing layer without having to add this to the actual business logic. The default
logging and metrics solutions are implemented as such plugins.

The instrumentation and metadata of the messages is utilized in the MatsBrokerMonitor project, which provides a
Mats3-specific view of the queues, topics, DLQs, and the actual messages residing on the message broker.

## Alternatives & Key Comparisons

_Orchestration and Workflow Tools_ (e.g., Camunda, Temporal, AWS Step Functions, Azure Logic Apps/Durable Functions)
were touched upon above, and is directly what Mats3 aims not to be!

_EIP pattern implementations_ (e.g., Apache Camel, Spring Integration) may be seen like a sub-species of orchestration
tools, where the process orchestration is defined in code, but still separate from the operation code. Mats3 is more
like a "transaction script" pattern, where the process is defined in the service code - with the ability to
asynchronously invoke other services' endpoints.

An alternative to Mats3's state handling is using _Sagas_ (e.g., Libraries like Eventuate Tram Sagas, Axon Framework
Sagas, NServiceBus Sagas - .NET) to handle the state, with _Correlation Ids_ to get hold of a specific process
instance's current state. These are more explicit and complex, needs to be implemented on each service, possibly in
diverging ways, and it may become difficult to see the full flow of the process. Mats3's state handling is more like a
call stack in a synchronous method where the state is kept in the method's local variables. It is abstracted away from
the developer, and the complexity is hidden and concentrated in the Mats library, letting the developer focus on the
actual business logic.

Another alternative is _Event Sourcing_, often in conjunction with Kafka as the event log, with the total state being
the sum of all events. While the concept on the surface seems extremely beautiful and simple, with many powerful
effects, and also being simple to develop and test, it is also complex to implement correctly - in particular over time.
Compared to the more mentally simple "do this, then that" for a process, event sourcing demands a completely different
way of thinking. Over time the complexity increases: Events can be emitted by anyone, and effectively any service can
"listen in" if they need that data. Events will over time change many times to accommodate new demands, and you need to
replay all events to get the current state, so all consumers of the events need to be able to handle all versions of the
events - or you must update the events in the store. When a system is new and small, the positive aspects of Event
Sourcing shines, but as you churn through many versions, over many years, with many developers, the complexity of the
system grows - and the "distributed schema" that the full set of events becomes is hard to manage and reason about.

_Actors_ (often associated with Erlang), in particular the _Akka_ framework for Java/Scala, is another alternative.
Actors, along with the _SEDA_ (Staged Event-Driven Architecture) pattern, is actually the inspiration for Mats3!
However, the Actor model is more complex than Mats3, with supervision hierarchies, and the need to handle the state
explicitly. Mats3 is a simpler abstraction, letting the developer focus on the business logic. Mats3 adds real
distribution using bog-standard message brokers, compared to the default in-memory distribution of Akka. Akka also has
ways to communicate over the network, but that is using a proprietary solution. Mats3 also adds transactionality, which
is not present in Akka.

Bottom line: Mats3 does not demand a new mental model - quite the opposite: It is made to be as close to bog-standard
synchronous coding as possible, while still being fully message-driven and asynchronous. It is pure Java (albeit with an
optional SpringConfig module for defining Endpoints via annotations), with a disarmingly simple API to ease you into the
concepts: You'll be writing business code pretty much immediately, and you'll be able to reason about the code in a
linear fashion, just as you would with synchronous code.

## Philosophical Alignment with How Humans Think

Mats3 is designed to align with how humans think about processes:

* Sequential steps: _“First do X, then Y.”_
* Local reasoning: _“At this step, I need these inputs.”_
* Black-box collaborators: _“I don’t care how Service B works, just do the job and give me the answer.”_

You get this with synchronous blocking HTTP calls: They to a high degree resemble ordinary method calls. With Java 21's
_Virtual Threads_ (Project Loom), you can even forget about the problem of too many native threads and thus forget about
the pain of asynchronous callback/async-await programming. But you will get all the problems with blocking calls, and
you don't get the benefits of asynchronous message passing. Mats3 gives you the best of both worlds: You code as if you
were coding a blocking, synchronous method, but you get the benefits of asynchronous messaging.

Most other distributed architectures trying to alleviate the problems of synchronous HTTP calls, heavily leak the
underlying distribution principles and architecture, whether it is event sourcing, sagas, messaging, or external
orchestration. Mats3 abstracts this away in a significant and meaningful way, letting developers focus on what needs to
happen - the business logic - not how it’s coordinated.

# Examples

Some examples taken from the API-level unit tests [api-tests-1](mats-api-test-1/src/test/java/io/mats3/api_test),
[api-tests-2](mats-api-test-2/src/test/java/io/mats3/api_test). Notice the use of the JUnit Rule `Rule_Mats`, which
sets up an ActiveMQ Classic in-memory server and creates a JMS-backed MatsFactory based on that.

In these examples, all the endpoints and stages are set up in one test class, and when invoked by the JUnit runner,
obviously runs on the same machine - but in actual usage, you would typically have each endpoint run in a different
process on different nodes. The DTO-classes, which acts as the interface between the different endpoints' requests and
replies, would in real usage be copied between the projects (in this testing-scenario, one DTO class is used as all
interfaces).

It is important to appreciate that each of the stages - also each stage in multi-stage endpoints - are handled by
separate threads. The state, request and reply objects are not shared references within a JVM, as they are marshalled
with the message that are passed between each stage of the endpoints. *All information for a process (a Mats Flow)
resides as contents of the Mats envelope which is being passed with the message.* In particular, if you have a process
running a three-stage Mats endpoint which is deployed on two nodes A and B, for a particular request, the first stage
might execute on node A, while the next stage on node B, and the last stage on node A again.

This means that what *looks like* a blocking, synchronous request/reply "method call" is actually fully asynchronous,
where a reply from an invoked service will be handled in the next stage by a different thread, quite possibly on a
different node if you have deployed the service in multiple instances.

## Simple send-receive

The following class exercises the simplest functionality: Sets up a Terminator endpoint, and then an initiator sends a
message to that endpoint. *(This example neither demonstrate the stack (request/reply), nor state keeping - it is just
the simplest possible message passing, sending some information directly from an initiator to a terminator endpoint)*

ASCII-artsy, it looks like this:
<pre>
[Initiator]   {sends to}
[Terminator]
</pre>

```java
public class Test_SimplestSendReceive {
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

Exercises the simplest request functionality: A single-stage service is set up. A Terminator is set up. Then an
initiator does a request to the service, setting replyTo(Terminator). *(This example demonstrates one stack level
request/reply, and state keeping between initiator and terminator)*

**Again, remember that having both these endpoints (service and terminator) in the same (micro)service, in the same
codebase, doesn't make sense in a real-world scenario:** The service endpoint would typically reside in a different
service, in a different codebase - this is how you would communicate with it.

ASCII-artsy, it looks like this, the line (pipe-char) representing the state that goes between Initiator and Terminator,
but which is kept "on the wire" along with the message flow, i.e. along with the request out to Service, and then reply
back to Terminator:
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

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        // This service is very simple, where it simply returns with an alteration of what it gets input.
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
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
                        .to(ENDPOINT)
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

Sets up a somewhat complex test scenario, testing request/reply message passing and state keeping between stages in
several multi-stage endpoints, at different levels in the stack. The code is a tad long, but it should be simple to read
through.

The main aspects of Mats are demonstrated here, notice in particular how the code of
<code>setupMainMultiStagedService()</code> *looks like* - if you squint a little - a linear "straight down" method with
two "blocking requests" out to other services, where the last stage ends with a return statement, sending off the Reply
to whoever invoked it.

Sets up these services:

* Leaf service: Single stage: Replies directly.
* Mid service: Two stages: Requests "Leaf" service, then replies.
* Main service: Three stages: First requests "Mid" service, then requests "Leaf" service, then replies.

A Terminator is also set up, and then the initiator sends a request to "Main", setting replyTo(Terminator).

ASCII-artsy, it looks like this, the lines representing the state that goes between the Initiator and Terminator and the
stages of the endpoints, but which is kept "on the wire" along with the message flow through the different requests and
replies:
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

**Again, it is important to realize that the three stages of the Main service (and the two of the Mid service,
and the single of the Leaf service, and the single of the Terminator!) are actually fully independent messaging
endpoints (with their own separate JMS queue when run on a JMS backend). If you've deployed the service to multiple
nodes/instances, each stage in a particular invocation flow might run on a different node.**

**Also, having all of the Main service, Mid service and Leaf service in the same (micro)service, in the same codebase as
shown here in this test only makes sense for testing purposes**. In a real-world scenario, these services would
typically reside in different services, in different codebases. The Terminator would typically reside in the same
code base as the initiating code ("Initiator" in the ASCII diagram).

The Mats API and implementation sets up a call stack that can be of arbitrary depth, along with stack frames whose state
flows along with the message passing, so that you can code *as if* you were coding a normal service method that invokes
remote services synchronously.

```java
public class Test_MultiLevelMultiStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT_MAIN = MatsTestHelp.endpoint("MAIN");
    private static final String ENDPOINT_MID = MatsTestHelp.endpoint("MID");
    private static final String ENDPOINT_LEAF = MatsTestHelp.endpoint("LEAF");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupLeafService() {
        // Create single-stage "Leaf" endpoint. Single stage, thus the processor is defined directly.
        MATS.getMatsFactory().single(ENDPOINT_LEAF, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Returns a Reply to the calling service with a alteration of incoming message
                    return new DataTO(dto.number * 2, dto.string + ":FromLeafService");
                });
    }

    @BeforeClass
    public static void setupMidMultiStagedService() {
        // Create two-stage "Mid" endpoint
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory()
                .staged(ENDPOINT_MID, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Mid" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = 10;
            sto.number2 = Math.PI;
            // Perform request to "Leaf" Service...
            context.request(ENDPOINT_LEAF, dto);
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
                .staged(ENDPOINT_MAIN, DataTO.class, StateTO.class);

        // Initial stage, receives incoming message to this "Main" service
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // State object is "empty" at initial stage.
            Assert.assertEquals(0, sto.number1);
            Assert.assertEquals(0, sto.number2, 0);
            // Setting state some variables.
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            // Perform request to "Mid" Service...
            context.request(ENDPOINT_MID, dto);
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
            context.request(ENDPOINT_LEAF, dto);
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
                        .to(ENDPOINT_MAIN)
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

This class tests the most basic [`MatsFuturizer`](mats-util/src/main/java/io/mats3/util/MatsFuturizer.java) situation:
Sets up a single-stage endpoint, and then uses the MatsFuturizer to invoke this service, which returns
a `CompletableFuture` that will be completed when the Reply comes back from the requested endpoint.

How does this work in a multi-node setup, where the Reply should randomly come to this node, or any other node? Behind
the scenes, the MatsFuturizer sets up a `subscriptionTerminator` (a topic) that has the nodename as a part of the topic
name. Furthermore, the request uses the special `replyToSubscription` feature of an initiation, targeting this
node-specific topic. Thus, the Reply will only be picked up by this node.

Why a topic? Not because of a topic's "broadcast" functionality (it is only a single consumer on this specific node that
listens to this specific topic), but the topic's "if you're not there, the message is gone" effect: If the node is gone
in the time since the Mats flow was initiated, then so is obviously the Future's waiting thread, so no need to have any
messages stuck on the MQ.

There are multiple modes of operation of the MatsFuturizer, but all of them share the fact that completion of the future
cannot be guaranteed - simply because the node holding the future might be booted or die while the Mats flow is running.
You can however decide whether the Mats flow itself (all other message passings except for the one back to the future
holder) should run using persistent ("guaranteed") or non-persistent messaging.

**The futurizer should _only_ be employed on the "edges" of the Mats fabric**, where synchronous processes like
REST-endpoints needs to communicate with the asynchronous Mats Fabric. _Never_ use the MatsFuturizer as a part of your
application-internal API - [read this](docs/developing/MatsComposition.md).

> As an alternative to the MatsFuturizer, you should check out [MatsSocket](https://github.com/centiservice/matssocket),
> which bridges the asynchronous nature of Mats all the way out to the end-user clients by using WebSockets, in a
> bidirectional fashion: In addition to request+reply and send semantics initiated from the client to the server, you
> can also perform request+reply and send semantics initiated from the server to a specific client, in addition to
> broadcast to multiple clients using pub/sub (topics) semantics.

```java
public class Test_MatsFuturizer_Basics {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, msg) -> new DataTO(msg.number * 2, msg.string + ":FromService"));
    }

    @Test
    public void normalMessage() throws ExecutionException, InterruptedException, TimeoutException {
        MatsFuturizer futurizer = MATS.getMatsFuturizer();

        DataTO dto = new DataTO(42, "TheAnswer");
        CompletableFuture<Reply<DataTO>> future = futurizer.futurizeNonessential(
                "traceId", "OneSingleMessage", ENDPOINT, DataTO.class, dto);

        Reply<DataTO> result = future.get(1, TimeUnit.SECONDS);

        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.reply);
    }
}
```

# Try it out!

If you want to try this out in your project, I will support you!

-Endre, endre@stolsvik.com

