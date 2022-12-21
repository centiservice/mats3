# Developing with Mats

_Read [System of Services](SystemOfServices.md) first!_  
_Then read [MatsFactory](MatsFactory.md)!_  
_Then also read [Endpoints and Initiations](EndpointsAndInitiations.md) before reading further!_

_You might also want to read [Transactions and Redeliveries](TransactionsAndRedeliveries.md), as that explains how Mats
currently have JMS-only, JMS-plus-JDBC and JMS-plus-SpringTX transaction handling._

Mats is a clean Java library, the API having no dependencies. The JMS implementation only depends on the JMS v1.1 API,
and SLF4j for logging.

The library `mats-spring` provides Spring integration, but this is completely optional.

When developing anything, testing goes hand in hand. This documentation has however divided the matter into two
documents, as there are several strategies wrt. testing that should be explored in a united fashion. So lets first start
off with what to think about when developing, where getting a good setup for testing obviously is an important part!

## Coding for the long haul!

When coding something, one is obviously eager to just code stuff and get it out and be done with it. However, most code
typically lasts for years and years, and needs debugging, upgrades, new features, performance improvements and more,
throughout its hopefully long life.

Therefore, it is important to make a literal code _base_ that can support this. This entails a bit more than just
hammering up a `public static void main` containing the code and throwing it out in production.

So, what is needed, from a code perspective? (From a "what is a good service" perspective, there's some more thoughts
in [System of Services](SystemOfServices.md))

* Ability to quickly clone down the code repository and getting it to run in your IDE, as fast as possible.
* Support for quickly making a test without having to manually code up lots of infrastructure.
* Mocking of dependent services.
    * Preferably not getting the mocks and supporting infrastructure along into the built artifact.
* Running the resulting codebase in your different environments like non-prod/pre-prod/staging and production.
* Support for running multiple services on your local developer machine at the same time, preferably in debug mode in
  your IDE.

It mostly boils down to handling dependent services. In development and test, you need to mock the dependent services -
but not always: You might sit working in the Accounts service, and want to also locally run the dependent Order service
at the same time so that those directly talk to each other on your local machine. In staging and production, the service
must connect to the actual dependent services, but the connections and URLs will be different for the different
environments.

As pointed out in [MatsFactory](MatsFactory.md), handling how the MatsFactory connects, or runs an in-vm broker, has
much of the same joys and interesting problems as when you handle connections to databases.

## Some code examples, clean Java

We'll run through a few small code examples, to build understanding of how the connectivity of Mats works.

### Local, single-file code samples

First, we'll try to make clear what constitute the full stack of a Mats setup. We'll do this with single Java file
without any tooling dependencies, i.e. bring up an ActiveMQ server, then make the client that connects to it, and create
the MatsFactory.

We'll then introduce some tooling, so that one get a good grip of what "magic" is hidden behind those things.

#### Raw, from scratch sample

The smallest, raw code sample with an endpoint and an initiation. This will:

1. Set up an ActiveMQ broker and JMS ConnectionFactory
2. Create a MatsFactory giving it the ConnectionFactory
3. Using the MatsFactory to create a single-stage Mats Endpoint
4. .. and a Terminator Endpoint
5. Initiate a Mats flow, requesting the endpoint, setting replyTo the terminator
6. Wait for the Terminator to receive the Reply from the endpoint.

#### Using a test utility, and Mats tools to achieve the same

1. Using MatsTestBroker to get a ConnectionFactory
2. Create a MatsFactory
3. Create a single-stage Mats Endpoint
4. Create and use a MatsFuturizer to request the endpoint

#### Using the test tools to achieve the same

1. Use Mats_Test to get a MatsFactory
2. Create a single-stage Mats Endpoint
3. Using the MatsFuturizer from the Mats_Test to request the endpoint

### Connecting to an external ActiveMQ with multiple instances

We'll now bring up an ActiveMQ server on localhost, and instead connect to that. We'll make three Java files, running in
two different JVMs: One providing a two-stage endpoint, another running a single-stage endpoint, and the third acting as
client to the two-stage endpoint.

1. Download and run the ActiveMQ in default config.
2. Create a Java file that runs the two-stage Endpoint
3. Create a Java file that runs the single-stage Endpoint
4. Create a Java file that runs a client, talking to that endpoint.
5. Start the Java files as individual JVMs - the order is not important.

You may also run multiple instances of the endpoints. And of the client.

## Coding with Spring

TODO: Code with spring!

## Mocking dependencies

So let's say you're working on CustomerService. There is a dependency to OrderService from this.

This dependency should be mocked when running in development and in testing.

### Mocking for Development

When you check out a service's codebase, you'd want to be able to instantly start developing. In a multi-service
architecture, this codebase probably have dependencies to other services. So, you either have to bring up a "nest" of
dependent services, which might end up being quite a bunch when taking into account the transitive dependencies of those
services. Or, you can mock the dependencies - which works out pretty well.

So, when in development mode, you bring up a MatsFactory backed by an in-vm message broker. The MatsTestBroker is
helpful for this. There needs to be some kind of switching mode - where you in development bring up an in-vm message
broker. This must obviously not happen when in production (or any prod-like environments, like staging): In prod-like
environments, you want the MatsFactory to employ the system's global message broker.

When in development mode, you'll also bring up the mocks. These are typically simple single-stage endpoints, emulations
of what the actual service dependencies offers. There might be some if's or switches in them, so that if you ask for
order #1 for customer A, the mock answers with a simple order consisting of a single order line, while if you ask for
order #2 for customer B, you get a more complex order with multiple order lines and some advanced routing. This lets you
run/debug the CustomerService in your IDE, and when you use the service's admin GUI, you should be able to pull up
customer A and get the simple order, while customer B should show the advanced order.

### Mocking for Testing

Mocking is also necessary when running integration tests. With integration test, the author means tests that fire up
relevant parts of the infrastructure and runs testing against the endpoints. Since the endpoints of this service depends
on other services, to be able to run the test without firing up other codebases (which could become rather complex when
running the tests on your continuous integration service), you want the dependent services' endpoints mocked.

It should be possible to run tests by just "right-click -> run" on the java file, not needing any other dependencies,
e.g. databases or message brokers running on the same host. This also means that you should not need to have any
properties set, or at least that such properties defaults to whatever is needed in testing.

In given test, you pull up the endpoint that you want to test, and also mock out the dependent endpoints which your
endpoint under testing needs. Those mocks probably need to answer different things for different tests.