# Mats Composition, Client-wrapping and MatsFuturizer

When using Mats in a service that exposes data over "REST" endpoints (JSON-over-HTTP), e.g. using Spring's
`@RequestMapping`, one typically uses the `MatsFuturizer` to bridge between the synchronous nature of the REST-endpoint,
and the asynchronous world of Mats and its message based nature. The MatsFuturizer lets you invoke a Mats service
by endpointId, and returns a `CompletableFuture` which is completed when the reply comes back (The MatsFuturizer uses a
little hack to ensure that the final reply comes back to the same host/node/pod, read more about that elsewhere).

> Note that you also have a much more interesting option for end-user client communications by using the MatsSocket
> system, which gives you a WebSocket-based bidirectional bridge between an end-user client and a service employing
> Mats, pulling the asynchronous nature of Mats all the way out to the client.

When coming from a REST-based world, one might be tempted to write "Client wrappers" around Mats services. This so that
your end-user REST services, e.g. Spring @RequestMappings, can call into a clean and nice little client, for example
named AccountClient. In a Mats-based architecture, this AccountClient would then employ a MatsFuturizer to talk to the
backing Mats Endpoint, returning a CompletableFuture - abstracting away the interaction with Mats behind a concise
interface.

As further improvements to this abstraction, you might then do some pre-operations inside that client, massaging some
arguments, looking up some ids from a database, before invoking the MatsFuturizer, and possibly also attach a few
`.then(...)` as post-operations to the CompletableFuture before returning it.

**Please do not do this!**

The problem with this is that it ruins the composability of Mats endpoints.

To do such abstractions with Mats, you create an _private_ Mats endpoint, which performs those pre-operations and
post-operations in separate stages, invoking the external Mats endpoint between those stages. **And then you invoke this
private endpoint directly using a MatsFuturizer inside the end-user client REST endpoint, i.e. directly inside the
@RequestMapping-method.**

The reason for this is that if you at a later stage needs a new Mats flow, where it would be nice to reuse that
AccountClient's abstraction, you will now have to invoke a method which returns a CompletableFuture. However, that
Client method is backed by a MatsFuturizer which creates a new and separate Mats flow. So instead of having a clean and
nice continuous Mats flow, you now end up in a blocking wait inside one Mats stage, _waiting for another Mats flow to
run and finish_.

Had you instead chosen to create this abstraction using an private Mats endpoint, you could simply have invoked
(`context.request(..)`) the private Mats endpoint from within your new flow. This would not have "broken" the Mats flow
into two completely separate flows where the first synchronously have to wait for the other to complete.

> If you desperately want to have an AccountClient, you could still make it, but then this should _only_ abstract away
> the MatsFuturizer call to the private Mats endpoint, with absolutely no pre- or post-operations or other logic, which
> might tempt a later reuse. All such logic should be inside the private Mats endpoint, to enable Mats composability.

In one sense such an "inner request" is not that different from performing a SQL query to a database, or invoking an
HTTP endpoint, inside a Mats Stage. But in several other ways - all having to do with handling adverse situations - such
a solution is way worse:

1. If the "inner" Mats flow (the one behind the Client abstraction, that uses a MatsFuturizer, which creates a new Mats
   flow) crashes and throws, the message broker will start redelivery to try to get that flow through. Eventually it
   might end up on the Dead Letter Queue.
2. On the "outer" Mats flow you are synchronously waiting in a Stage for a CompletableFuture to complete. This won't
   ever complete, and will eventually time out. You now have an exceptional situation here too, and if that stage also
   throws out, this outer flow will start redelivery - which once again invokes the Client and starts a new inner flow.
3. (If you are unlucky, you'll end up with multiple inner flows running at the same time.)
4. Instead of having a single Mats flow, you now have multiple disjunct flows depending on each other - and the
   debugging reasoning will become much harder. If you had a single flow, the reason for the DLQ would have been
   immediately obvious, while you now have a DLQ in one flow, which just loosely correlates with that outer flow - which
   might also have DLQed.
5. When this codebase lives for multiple years, you'll might even end up with a new OrderClient which invokes the
   AccountClient. When invoking the OrderClient from an end-user REST endpoint, you'll now have synchronous
   MatsFuturizers with corresponding disjunct Mats flows three levels deep.

All in all, you end up with the same type of nightmare that a REST-based architecture results in, where REST-endpoints
invokes other REST-endpoints which invokes other REST-endpoints - exactly what Mats set out to avoid.