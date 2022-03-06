# Mats Composition, Client-wrapping and MatsFuturizer

When using Mats in a service that exposes data over HTTP, for example over REST-endpoints using e.g. Servlets or
Spring's `@RequestMapping` (e.g. as backing for a UI, or to expose a REST-based API), one typically uses
the `MatsFuturizer` to bridge between the synchronous nature of the REST-endpoint, and the asynchronous world of Mats
and its message based nature. The MatsFuturizer lets you invoke a Mats Endpoint by endpointId, and returns
a `CompletableFuture` which is completed when the Reply comes back (The MatsFuturizer uses a little hack to ensure that
the final reply comes back to the same host, read more about that elsewhere).

> Note that you also have a much more interesting option for end-user client communications by using the
> [MatsSocket](https://github.com/centiservice/matssocket) system, which gives you a WebSocket-based bidirectional
> bridge between an end-user client and a service employing Mats, pulling the asynchronous nature of Mats all the way
> out to the client.

Let's say you have a CustomerService that needs to talk to some AccountService. When coming from a REST-based world, one
might be tempted to write "Client wrappers" around such external data dependencies. This so that CustomerService's data
exposing REST-endpoints can call into a nice and clean little client, for example named AccountClient, instead of
dealing with messy HTTP right there in the REST-endpoint method. In the REST-based world, this AccountClient would use a
HttpClient or similar to synchronously call the AccountService's REST-endpoints to get the needed data, abstracting away
the HTTP invocation (and possibly error handling and retrying). In a Mats-based architecture, such an AccountClient
would instead need to employ a MatsFuturizer to talk to the backing Mats Endpoint, returning the answer directly (or
possibly as a CompletableFuture), thus abstracting away the interaction with Mats.

As further improvements to this abstraction, you might then do some pre-operations inside that client, massaging some
arguments, looking up some identifiers from a database, before invoking the MatsFuturizer, and possibly also attach a
couple of `.thenApply(...)` as post-operations to the CompletableFuture before returning it.

**Please don't do this!**

The problem with this is that it ruins the composability of Mats Endpoints.

Instead, if you need to do such abstractions when employing Mats, you create a _"service-private"_ Mats Endpoint, which
performs those pre-operations in a Stage, request the external AccountService Mats Endpoint, and perform the
post-operations in the next Stage which receives the Reply (any number of Stages are obviously OK) - and then return the
finished data. **You then invoke this private Endpoint directly inside the data exposing REST-endpoint, e.g. directly
inside the @RequestMapping-method, using a MatsFuturizer.** (A "service-private" Endpoint is just an ordinary Endpoint
where you by convention include `.private.` as part of the EndpointId. Some tools display these Endpoints in a separate
section.)

The reason for this is that if you at a later point need a new Mats Endpoint in CustomerService,
e.g. `CustomerService.createInvoice`, it might be relevant and tempting to reuse that AccountClient abstraction. If you
now have done this the synchronous way, you will in some Mats Stage's code have to invoke a synchronous method of
AccountClient. However, that AccountClient abstraction is backed by a MatsFuturizer which creates a new and separate
Mats flow. So instead of having a single nice and clean continuous Mats flow, you now end up in a blocking wait inside
some Mats stage _while waiting for another Mats flow to run and finish_.

Had you instead chosen to create this abstraction using a service-private Mats Endpoint, you could simply have invoked
(`context.request(..)`) the private Mats Endpoint from within your new flow. This would not have "broken" the Mats flow
into two completely separate flows where the first synchronously have to wait for the other to complete.

> If you desperately want to have an AccountClient, you could still make it, but then this should _only_ abstract away
> the MatsFuturizer call to the private Mats Endpoint, with absolutely no pre- or post-operations or other logic, which
> might tempt a later reuse. All such logic should be inside the private Mats Endpoint, to enable Mats composability.
> A relevant compromise might be to define the service-private Mats Endpoint inside such an AccountClient class, so
> that this Mats Endpoint with its pre- and post-operations, _as well as_ the "client" method utilizing the
> MatsFuturizer to invoke this private Mats Endpoint, are contained in the same class.
>
> Another way is to just accept that the @RequestMapping REST-Endpoint does have a bit of pre- and post-processing code.
> If your services are distinct and small enough to easily comprehend, introducing lots of abstractions just makes it
> more difficult to understand the code. Private endpoints do incur more overhead in message passings. Refactor when
> there is an actual need.

In one sense, a Stage invoking such a synchronous method which employs MatsFuturizer is not that different from
performing a synchronous SQL query to a database or invoking a synchronous HTTP-endpoint inside a Stage. But in several
other ways - all having to do with handling adverse situations - such a solution is way worse, at least compared to how
it could have been:

1. If the "inner" Mats flow (the one behind the AccountClient abstraction, that uses a MatsFuturizer, which creates a
   new Mats flow) crashes and throws, the message broker will start redelivery to try to get that flow through.
   Eventually it might end up on the Dead Letter Queue.
2. On the "outer" Mats flow you are synchronously waiting in a Stage for a CompletableFuture to complete. This won't
   ever complete, and will eventually time out. You now have an exceptional situation here too, and if that stage also
   throws out, this outer flow will start redelivery - which once again invokes the Client and starts a new inner flow.
3. If the outer MatsFuturizer times out and retries before the inner flow is finished with its redeliveries, you'll end
   up with multiple inner flows running at the same time.
4. Another situation is that your "outer" flow's MatsFuturizer times out, while the "inner" flow actually completes,
   just very slow (due to some temporary adverse situation). You now have gotten into a situation where you have lost
   track of which elements of a process has completed, and which have not - and must use valuable time and resources to
   establish this.
5. Instead of having a single, easy to reason about Mats flow, you now have multiple disjunct Mats flows depending on
   each other - and the debugging reasoning will become much harder. If you had a single flow, the reason for the DLQ
   would have been immediately obvious, while you now have a DLQ in one flow, which just loosely correlates with that
   outer flow - which might also have DLQed.
6. When this codebase lives for multiple years, you might later code up a new OrderService, with a new Mats flow, which
   needs to invoke CustomerService's `CustomerService.createInvoice` Mats flow. To keep consistency, the communication
   with CustomerService has been abstracted away behind a synchronous CustomerClient. When invoking that OrderService
   Mats flow from a new data exposing REST-endpoint, you'll have disjunct Mats flows and synchronous MatsFuturizers
   three levels deep.

All in all, you end up with the same type of nightmare and brittleness that a REST-based architecture results in, where
synchronous REST-endpoints invokes other synchronous REST-endpoints which again invokes yet other synchronous
REST-endpoints, which may end up with spectacular cascading failures, where you do not know which operations has
actually completed. _This is exactly what Mats set out to avoid!_

**The MatsFuturizer shall only be used on the very outer edges, where you actually have a synchronous call that needs to
bridge into the asynchronous Mats fabric. Do not use it as part of your service-internal API!**