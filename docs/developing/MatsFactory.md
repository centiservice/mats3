# The MatsFactory

All interaction with Mats goes through a `MatsFactory`. You need a MatsFactory to create Mats Endpoints, and for
initiating new Mats flows to those Endpoints.

At service startup, you create all the Mats Endpoints the service provides. These are both its public endpoints, to
which other services communicate, and Terminators for receiving the replies from this service's calls out to other
services' endpoints. You might also want service-private endpoints, meant only for the service itself. There is no
difference between these types, other than in naming convention.

During the running of the service, depending on the role of the service, it will probably be sending messages to
endpoints, both endpoints external to itself, and possibly to service-private endpoints it has created itself. Sending a
message initiates a Mats Flow. If an initiation is a fire-and-forget _send_ to a terminating endpoint, the Mats Flow
will only be a single message passing. If it is a _request_ to an endpoint consisting of five stages, each stage
requesting some other service, the Mats Flow might consist of dozens of message passings.

Initiating messages will typically happen either due to some service-internal process kicking off, e.g. _"process
outstanding orders every on-the-hour"_, or due to requests from users, e.g. customer-actions like _"place order"_, or
backoffice actions like _"view outstanding orders"_.

> User initiated actions are typically synchronous in nature where a person is actively waiting for the reply. These code flows might benefit from the `MatsFuturizer` tool, which simplifies the bridging between the asynchronous world of message passing, and the synchronous way of human interaction.

How to code Mats endpoints, and how to initiate Mats Flows, are covered
in [Endpoints, Stages and Initiations](EndpointsAndInitiations.md).

This document covers how a MatsFactory connects to a message broker, and how to get an instance of it.

## MatsFactory connectivity and JmsMatsFactory

`MatsFactory` is an interface. You need an implementation of it. As of 2022, there is only one implementation, the
`JmsMatsFactory`.

As the name indicates, this implementation employs the Java Messaging System API (JMS) to connect to a Message Broker.
It is developed on the JMS v1.1 API, but the v2.0 API is a superset, so it handles that too.

> The JMS implementation of Mats has been run in production with Apache ActiveMQ _Classic_ for years, which implements JMS 1.1. It also fully passes all unit and integration tests when running on top of Apache ActiveMQ _Artemis_, which implements JMS 2.0. (Artemis is a 2015 donation of RedHat's HornetQ to Apache. _RedHat AMQ Broker_ is based on Artemis). It does not work with RabbitMQ's JMS Client, due to RabbitMQ's idiosyncrasies wrt. redelivery and queue vs. DLQ semantics.

The `JmsMatsFactory` lives on top of a JMS `ConnectionFactory`. It is thus the underlying JMS ConnectionFactory which
decides which message broker the Mats Endpoints will receive messages from, and where initiated messages will go - the
JmsMatsFactory is merely a layer between your code and the JMS ConnectionFactory you construct it with.

This is pretty similar to how you code against a database: You get hold of a database specific implementation of a JDBC
DataSource, and then use this to get Connection objects, to which you send your SQL commands. The analogy is even better
if you employ Spring's JdbcTemplate and friends, as those are constructed with an underlying DataSource, abstracting
away the Connection-getting.

## Connecting to the correct JMS broker

The similarity between JMS ConnectionFactory and JDBC DataSource is pretty apt, as both basically are databases. A
Message Broker is just a rather specialized database: One may think of it as having specialized FIFO-tables, where
"rows" are added to named tables at the end, and consumed from the head. _(Indeed, for example PostgreSQL has features
specifically created for using the SQL server as a message broker, blurring the lines between those two types of
products)_

This also means that you have the same primary annoyances when using message brokers as when using databases: State, and
external connections.

In a Mats system (as well as any good messaging oriented architecture!), the ideal is that all queues should basically
always have 0 messages on them: Any new message should immediately be picked up by its consumers, and any new messages
produced by those consumers' processing should again immediately be consumed by the next consumer. There should
therefore never be _much_ state to be annoyed about, but one always have to take it into consideration: If you
incompatibly change the state, request or response DTOs of Mats endpoints, you may risk that there are already messages
on the queues that was created using the old versions. In practice, this is not a big problem, but one must understand
and handle the situation.

Let's leave the state problem for now, and focus on the external connections. As with database connections, you need to
have some kind of system for handling your different environments: Development, testing, staging / pre-prod, and
production, and whatever else environments you utilize.

### Environments needing an in-vm broker

When you actually code the service, running it locally, and when you want to run unit- and integration tests, you need a
full MatsFactory, with a ConnectionFactory _which connects to an in-vm message broker_. This is similar to how it is
common to use an H2 in-vm database engine when developing locally. _(An alternative is the use of containers for such
dependencies, but Mats definitely wants to support the single-process, in-vm mode of development)._

For development, there's a tool to easily get hold of a pre-cooked MatsFactory with a backing in-vm message broker in
the library `mats-test-broker` called `MatsTestBroker` - this supports both ActiveMQ and Artemis. When developing a
service, you typically create mock endpoints for all the external dependencies, so that you can code locally without
having to fire up the entire system consisting of dozens of microservices. More about this is found in
[Developing with Mats](DevelopingWithMats.md).

For unit- and integration testing, the Mats libraries provides several solutions for getting a MatsFactory, both for
pure Java and JUnit/Jupiter, and when being run in a Spring environment (libs `mats-test`, `mats-test-junit`,
`mats-test-jupiter` and `mats-spring-test`). These solutions employ the same tool as mentioned for development, just
packaging it up for even simpler instantiation. The testing tools also include additional features, for example a way to
get hold of DLQ messages for those tests which checks the negative paths. More about this is found
in [Testing with Mats](TestingWithMats.md).

Notice that when using the Mats-provided tools for getting such a pre-cooked MatsFactory, you can via "-D" switches
override the creation of an in-vm broker, instead directing the creation of the MatsFactory to use a JMS
ConnectionFactory which connects to an external message broker.

### Environments connecting to an external broker

When developing, you sometimes want to connect to a process-external broker (instead of employing an in-vm broker),
typically running on localhost: If you want to locally fire up multiple services which interact which each other (thus
not using mocks), for example to debug some specific situation where the interaction seemingly goes bad, you need them
to communicate. The way to do this is to have them all connect to a local instance of the message broker. This is again
explained in [Developing with Mats](DevelopingWithMats.md).

For the different prod-like environments like non-prod/pre-prod/staging and obviously for production, you want the
MatsFactory to be connected to an external message broker specific for the environment. You handle this exactly the same
way you handle any other external dependency like database or external HTTP endpoints: You'll be using the same type of
message broker in all environments, so the only change between environments will be the URL and credentials - again
probably exactly the same way you already handle the databases between environments.