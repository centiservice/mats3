# Mats Composition, Client-wrapping and MatsFuturizer

When using Mats in a service that exposes data over HTTP, for example over REST endpoints using e.g. Servlets or
Spring's `@RequestMapping` (e.g. as backing for a UI, or to expose a REST-based API), one typically uses
the `MatsFuturizer` to bridge between the synchronous nature of the REST-endpoint, and the asynchronous world of Mats
and its message based nature. The MatsFuturizer lets you invoke a Mats service by endpointId, and returns
a `CompletableFuture` which is completed when the Reply comes back (The MatsFuturizer uses a little hack to ensure that
the final reply comes back to the same host, read more about that elsewhere).

> Note that you also have a much more interesting option for end-user client communications by using the MatsSocket
> system, which gives you a WebSocket-based bidirectional bridge between an end-user client and a service employing
> Mats, pulling the asynchronous nature of Mats all the way out to the client.

Let's say you have a CustomerService that needs to talk to an external AccountService. When coming from a REST-based
world, one might be tempted to write "Client wrappers" around such external data dependencies. This so that
CustomerService's data exposing REST services can call into a nice and clean little client, for example named
AccountClient, instead of dealing with messy HTTP right there in the method. In the REST-based world, this AccountClient
would use a HttpClient or similar to synchronously call the AccountService's HTTP-based endpoints to get the needed
data, abstracting away the HTTP invocation. In a Mats-based architecture, such an AccountClient would instead need to
employ a MatsFuturizer to talk to the backing Mats Endpoint, returning the answer directly (or possibly as a
CompletableFuture), thus abstracting away the interaction with Mats.

As further improvements to this abstraction, you might then do some pre-operations inside that client, massaging some
arguments, looking up some identifiers from a database, before invoking the MatsFuturizer, and possibly also attach a
couple of `.thenApply(...)` as post-operations to the CompletableFuture before returning it.

**Please do not do this!**

The problem with this is that it ruins the composability of Mats endpoints.

Instead, if you need to do such abstractions when employing Mats, you create a _"service private"_ Mats endpoint, which
performs those pre-operations in a Stage, request the external AccountService Mats Endpoint, and perform the
post-operations in the next Stage which receives the Reply - and then return the finished data. **You then invoke this
private endpoint directly inside the data exposing REST endpoint, e.g. directly inside the @RequestMapping-method, using
a MatsFuturizer.**

The reason for this is that if you at a later point need a new Mats Endpoint in CustomerService,
e.g. `CustomerService.createInvoice`, it might be relevant and tempting to reuse that AccountClient abstraction. If you
now have done this the synchronous way, you will in some Mats Stage's code have to invoke a synchronous method. However,
that AccountClient abstraction is backed by a MatsFuturizer which creates a new and separate Mats flow. So instead of
having a single nice and clean continuous Mats flow, you now end up in a blocking wait inside some Mats stage _while
waiting for another Mats flow to run and finish_.

Had you instead chosen to create this abstraction using a private Mats endpoint, you could simply have invoked
(`context.request(..)`) the private Mats endpoint from within your new flow. This would not have "broken" the Mats flow
into two completely separate flows where the first synchronously have to wait for the other to complete.

> If you desperately want to have an AccountClient, you could still make it, but then this should _only_ abstract away
> the MatsFuturizer call to the private Mats endpoint, with absolutely no pre- or post-operations or other logic, which
> might tempt a later reuse. All such logic should be inside the private Mats endpoint, to enable Mats composability.
> A relevant compromise might be to define the service-private Mats Endpoint inside such an AccountClient class, so
> that this Mats Endpoint with its pre- and post-operations, _as well as_ the "client" method utilizing the
> MatsFuturizer, are contained in the same class.

In one sense, a Stage invoking such a synchronous method which employs MatsFuturizer is not that different from
performing a synchronous SQL query to a database or invoking a synchronous HTTP endpoint inside a Stage. But in several
other ways - all having to do with handling adverse situations - such a solution is way worse, at least compared to how
it could have been:

1. If the "inner" Mats flow (the one behind the AccountClient abstraction, that uses a MatsFuturizer, which creates a
   new Mats flow) crashes and throws, the message broker will start redelivery to try to get that flow through.
   Eventually it might end up on the Dead Letter Queue.
2. On the "outer" Mats flow you are synchronously waiting in a Stage for a CompletableFuture to complete. This won't
   ever complete, and will eventually time out. You now have an exceptional situation here too, and if that stage also
   throws out, this outer flow will start redelivery - which once again invokes the Client and starts a new inner flow.
3. (If you are unlucky, you'll end up with multiple inner flows running at the same time.)
4. Instead of having a single, easy to reason about Mats flow, you now have multiple disjunct Mats flows depending on
   each other - and the debugging reasoning will become much harder. If you had a single flow, the reason for the DLQ
   would have been immediately obvious, while you now have a DLQ in one flow, which just loosely correlates with that
   outer flow - which might also have DLQed.
6. When this codebase lives for multiple years, you might even end up with a new OrderService, with a new Mats flow,
   which needs to invoke CustomerService's `CustomerService.createInvoice` Mats flow. To keep consistency, this has been
   abstracted away behind a CustomerClient. When invoking that OrderService Mats flow from an end-user REST endpoint,
   you'll now have disjunct Mats flows and synchronous MatsFuturizers three levels deep.

All in all, you end up with the same type of nightmare and brittleness that a REST-based architecture results in, where
synchronous REST-endpoints invokes other synchronous REST-endpoints which again invokes yet other synchronous
REST-endpoints - _exactly what Mats set out to avoid_.

**The MatsFuturizer shall only be used on the very outer edges, where you actually have a synchronous call that needs to
bridge into the asynchronous Mats fabric. Do not use it as part of your service-internal API!**