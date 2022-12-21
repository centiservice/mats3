# Rationale for Mats

Going a bit deeper into the rationale behind Mats, we'll look at Message Oriented architectures vs. the more standard
synchronous blocking RPC typically employed in a multi-service architecture.

In a multi-service architecture (e.g. using <i>Micro Services</i>) one needs to communicate between the different
services. The golden hammer for such communication is REST-ish services employing JSON over HTTP.

*(A note here: The arguments here are for service-mesh internal IPC/RPC communications. For e.g. REST endpoints facing
the user/client, there is a "synchronous-2-Mats-bridge" called
the [MatsFuturizer](https://mats3.io/docs/sync-async-bridge/), from which you can perform a Mats Request
initiation which returns a Future, which will be resolved when the final Reply message comes back to the same node.
There is also the library [MatsSockets](https://matssocket.io) that is built on top of Mats and
WebSockets, which pulls the asynchronousness of Mats all the way out to the end-user client, with client libraries
available for JavaScript and Dart/Flutter)*

*Asynchronous Message Oriented architectures* are superior to synchronous REST-based systems in a number of ways, for
example:

* **High Availability**: For each queue, you can have listeners on several service instances on different physical
  servers, so that if one service instance or one server goes down, the others are still handling messages.
* **[Location Transparency](http://www.reactivemanifesto.org/glossary#Location-Transparency)**: *Service Location
  Discovery* is avoided, as messages only targets the logical queue name, without needing information about which nodes
  are currently consuming from that queue.
* **Scalability** / **Elasticity**: It is easy
  to [increase the number of nodes](http://www.reactivemanifesto.org/glossary#Replication) (or listeners per node) for a
  queue, thereby increasing throughput, without any clients needing reconfiguration. This can be done runtime, thus you
  get *elasticity* where the cluster grows or shrinks based on the load, e.g. by checking the size of queues.
* **Handles load peaks** / **Prioritization**: Since there are queues between each part of the system and each stage of
  every process, a sudden influx of work (e.g. a batch process) will not deplete the thread pool of some service
  instance, bringing on cascading failures backwards in the process chain. Instead messages will simply backlog in a
  queue, being processed as fast as possible. At the same time, message prioritization will ensure that even though the
  system is running flat out with massive queues due to some batch running, any humans needing replies to queries will
  get in front of the queue. *Back-pressure* (e.g. slowing down the entry-points) can easily be introduced if queues
  becomes too large.
* **Transactionality**: Each endpoint has either processed a message, done its work (possibly including changing
  something in a database), and sent a message, or none of it.
* **Resiliency** / **Fault Tolerance**: If a node goes down mid-way in processing, the transactional aspect kicks in and
  rolls back the processing, and another node picks up. Due to the automatic retry-mechanism you get in a message based
  system, you also get *fault tolerance*: If you get a temporary failure (database is restarted, network is
  reconfigured), or you get a transient error (e.g. a concurrency situation in the database), both the database change
  and the message reception is rolled back, and the message broker will retry the message.
* **Monitoring**: All messages pass by the Message Broker, and can be logged and recorded, and made statistics on, to
  whatever degree one wants.
* **Debugging**: The messages between different parts typically share a common format (e.g. strings and JSON), and can
  be inspected centrally on the Message Broker.
* **Error/Failure handling**: Unprocessable messages (both in face of coded-for conditions (validation), programming
  errors, and sudden external factors, e.g. db going down) will be refused and, after multiple retries, eventually put
  on a *Dead Letter Queue*, where they can be monitored, ops be alerted, and handled manually - centrally. Often, if the
  failure was some external resource being unavailable, a manual (or periodic attempts at) retry of the DLQ'ed message
  will restart the processing flow from the point it DLQ'ed, as if nothing happened.

However, the big pain point with message-based communications is that to reap all these benefits, one need to fully
embrace asynchronous, multi-staged distributed processing, where each stage is totally stateless, but where one still
needs to maintain a state throughout the flow.

## Standard Multi Service Architecture employing REST RPC

When coding service-mesh internal REST endpoints, one often code locally within a single service, typically in a
straight down, linear, often "transaction script" style - where you reason about the incoming Request, and need to
provide a Response. It is not uncommon to do this in a synchronous fashion. This is very easy on the brain: It is a
linear flow of logic. It is probably just a standard Java method, using e.g. a Servlet or Spring's @RequestMapping. All
the code for this particular service method resides in the same project. You can structure your code, invoke service
beans or similar. When you need information from external sources, you go get it: Either from a database, or from other
services by invoking them using some HttpClient. *(Of course, there are things that can be optimized here, e.g.
asynchronous processing instead of old-school blocking Servlet-style calls, invoking the external resources
asynchronously by using e.g. Futures or other asynchronous/reactive mechanisms - but that is not really the point
here!)*. For a given service, all logic can be mostly be reasoned about locally, within a single codebase.

In particular, *state* is not a problem - it is not even a thought - since that is handled by the fact that the process
starts, runs, and finishes on the same JVM - typically within a single method. If you first need to get something from
one service, then use that information to get something from another service, and finally collate the information you
have gathered into a response, you simply just do that: The implicit state between the stages of the process is
naturally handled by local variables in the method, along with any incoming parameters of the method which also just
naturally sticks around until the service returns the response. *(Unless you start with the mentioned asynchronous
optimizations, which typically complicate such matters - but nevertheless: State is handled by the fact that this
process is running within a single JVM)*.

However, the result is brittle: So, lets say your service invokes a second service. And then that second service again
needs to invoke a third service. You now have three services that all have state on their respective stacks (either
blocking a thread, or at any rate, having an asynchronous representation of the state on all three services). And, there
is multiple stateful TCP connections: From the initial requester, to the receiving service, then to the second service,
and then to the third service. The process is not finished until all services have provided their response, and thus the
initial requester finally gets its response. If one service intermittently becomes slow, you face the situation of
resource starvation with subsequent cascading failures percolating backwards in your call chains, which now might
resource-starve other services that otherwise wouldn't be affected. If any of the service nodes fail, this will
typically impact many ongoing processes, all of them failing, and the currently ongoing processes potentially being left
in an intermediate state. Blindly doing retries in the face of such errors is dangerous too: You may not know whether
the receiver actually processed your initial request or not: Did the error occur before the request was processed, while
it was processed, or was it a problem with the network while the response was sent back? Even deploys of new versions
can become a hazard, and you must at least carefully perform graceful shutdowns letting all pending requests finish
while not accepting any new requests.

## Message-Oriented Multi Service Architecture

With a Messaging-Oriented Architecture, each part of the total process would actually be a separate subprocess. Instead
of the above described nested call based logic (which is easy on the brain!), you now get a bunch of message processing
stages which will be invoked in sequence: The process initiator puts a message on a queue, and another processor picks
that up (probably on a different service, on a different host, and in different code base) - does some processing, and
puts its (intermediate) result on another queue. This action may need to happen multiple times, traversing multiple
services. This is
the ["Pipes and Filters"](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PipesAndFilters.html)
architectural style as defined in the EIP book. This multiple *"Consume message from inbound queue; Process the message;
Post new message to outbound queue"*-style processing flow is harder to reason about: State is now not implicit, but
needs to be passed along with the messages. And these stages will typically be within separate code bases, as they
reside in different services.

You gain much: Each of these stages are independent processes. There is no longer a distributed blocking state residing
through multiple services, as the queues acts as intermediaries, each stage processor having fully performed its part of
the total process before posting the (intermediate) result on a queue (and then goes back fetching a new message to
process from its incoming queue). Each service can fire up a specific number of queue processors utilizing the available
resources optimally. There cannot be a cascading failure scenario, at least not until the message queue broker is
exhausted of space to store messages - which has a considerably higher ceiling than the thread and memory resources on
individual processing nodes. The operations of consuming and posting messages are transactional operations, and if you
don't mess too much up, each stage is independently transactional: If a failure occurs on a node, all processing having
occurred on that node which have not run to completion will be rolled back, and simply retried on another node: The
total process flow does not fail.

However, you also loose much: The simple service method where you could reason locally and code in a straight down
manner, with state implicitly being handled by the ordinary machinery of the thread stack and local variables, now
becomes a distributed process spread over several code bases, and any state needed between the process stages must be
handled explicitly. If you realize that you in the last stage need some extra information that was available in the
initial stage, you must wade through multiple code bases to make sure this piece of information is forwarded through
those intermediate stages. This also give a result that such processing stages aren't generic - they are bespoke to a
particular flow. And let's just hope that you don't want to refactor the entire total process.

Thus, the overall structure in a message oriented architecture typically ends up being hard to grasp, quickly becomes
unwieldy, and is difficult to implement due to the necessary multiple code bases that needs to be touched to
implement/improve/debug a flow. Most projects therefore choose to go for the much easier model of synchronous processing
using some kind of blocking REST-like RPC: In this model one can code linearly, employing blocking calls out to other,
often *general* services that are needed - a model which every programmer intuitively know due to it closely mimicking
local method invocations. However, the familiarity is basically the only positive aspect of this code style.

## What MATS brings to the table

**The main idea of the Mats library is to let developers code message-based endpoints that themselves may "invoke" other
such endpoints, in a manner that closely resembles the familiar synchronous "straight down" linear code style.** In this
familiar code style, all the state built up before the call out to the requested service is present after the service
returns: Envision a plain Java method that invokes other methods, or more relevant, a REST service which invokes other
REST services. With Mats, such an endpoint, while *looking* like a "straight down" synchronous method, is actually a
multi-stage *(each stage processed by consumers on a specific queue)*, asynchronous *(the flow passes from queue to
queue, never synchronously waiting for a reply)*, fully stateless *(as state resides "on the wire")* message oriented
distributed process.
**Effectively, the Mats library gives you a mental coding model which feels like home, and coding is really
straightforward (both literally and figuratively!), while you reap all the benefits of a Message-Oriented
Architecture.**
