# Documentation for Mats<sup>3</sup>

You should probably read the top-level [README.md](../README.md) file first!

## Introduction

### [What is Mats?](WhatIsMats.md)

### [Rationale for Mats](RationaleForMats.md)

## Coding

### [Endpoints, Stages and Initiations](developing/EndpointsAndInitiations.md)

How to code Mats Endpoints and Stages, both pure Java and with SpringConfig (annotations). Single-stage and multi-stage
Endpoints. Terminators. Initiation of Mats Flows. How to perform synchronous Mats requests using the `MatsFuturizer`.

### [MatsFactory, and how to get it](developing/MatsFactory.md)

You need an implementation of `MatsFactory` both for creating Mats Endpoints, and initiating Mats Flows. How to handle
this for the different environments your code must work in: Development, testing, pre-prod / staging, production.

### [Developing with Mats](developing/DevelopingWithMats.md)

* Handling MatsFactory when in development mode (in-vm broker, classpath)
* Mocking of external dependencies, also classpath

### [Testing with Mats](developing/TestingWithMats.md)

* Handling MatsFactory when in testing mode (Mats_Rule, Mats_Extension, Spring annotations)

### [Transactional Stages and Redelivery](developing/TransactionsAndRedeliveries.md)

JMS and JDBC transactions. Each Stage is transactional, the transaction demarcation constituting all of reception of
message, database operations, and sending of messages. Either all of those occurs, or none of them. This means that if
anything bad happen in a Stage processing, the message delivery and the entire stage processing is rolled back, and a
redelivery attempt will occur - until either it goes through, or the message is considered _"poison"_ and Dead Letter
Queued.

### [TraceIds and InitiatorIds](developing/TraceIdsAndInitiatorIds.md)

Mats Flows contains several metadata that is meant to help in understanding and debugging process errors, as well as
enabling aggregate metrics. The document explains a bit about these, and gives pointers to their intended usage.

### [Composition of Mats Endpoints](developing/MatsComposition.md)

You might be tempted to create synchronous Client wrappers, employing the MatsFuturizer, to abstract away collaborating
Mats Endpoints. This would feel familiar to how you might code when interfacing with external HTTP endpoints,
abstracting away the actual communication with the external service. However, due to the asynchronous nature of Mats,
this will hinder composition of Mats endpoints: You should not be invoking a synchronous wrapper of a Mats Endpoint
within another Mats Endpoint. If you have a relevant use case, instead make "service-private" Mats Endpoints.

Upcoming:

### Few and small messages!

_Eight fallacies of distributed computing._ "Granularity killed SOA". You can't beat physics, and a message broker does
not make it simpler: Distance and bandwidth creates latencies, and bottlenecks. You want to think about your inter
service communications, and minimize it both in numbers and sizes. You don't do a SQL SELECT for millions of rows, and
then sum up some amount from those. You rather use a SQL aggregate function.

### Batch processing

If you periodically create a report to or for hundreds of thousands of customers, you might _not_ want to run this as
individual Mats flows consisting of dozens of stages. Such a solution will by itself create millions of messages, and
will necessarily load the infrastructure by quite a bit. If you send along PDFs, that will necessarily be rather big
messages. Rather, consider batching (many reports per flow), or gather all necessary data to one service (using massive
batching), and then run the report generation locally on this service.

### Upgrading, refactoring Endpoints and services

What to think about to have seamless upgrades: Mats flows with DTOs and STOs might be _in flight_, and you want to
minimize the differences and incompatibilities between vN and vN+1, so that very few, preferably zero, flows crash
midflight.

## Prodcution

### Config for ActiveMQ


