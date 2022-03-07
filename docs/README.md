# Documentation for Mats<sup>3</sup>

You should probably read the top-level [README.md](../README.md) file first!

## Introduction

### [What is Mats?](WhatIsMats.md)

### [Rationale for Mats](RationaleForMats.md)

## Coding

### [Basics](developing/A-DevelopingBasics.md)

How to code Mats Endpoints and Stages. Single-stage and multi-stage Endpoints. Terminators. Initiation of Mats Flows.
How to perform synchronous Mats requests using the `MatsFuturizer`.

### [Transactional Stages and Redelivery](developing/TransactionsAndRedeliveries.md)

Each Stage is transactional, the transaction demarcation constituting all of reception of message, database operations,
and sending of messages. Either all of those occurs, or none of them. This means that if anything bad happen in a Stage
processing, the message delivery and the entire stage processing is rolled back, and a redelivery attempt will occur -
until either it goes through, or the message is considered _"poison"_ and Dead Letter Queued.

### [TraceIds and InitiatorIds](developing/TraceIdsAndInitiatorIds.md)

Mats Flows contains several metadata that is meant to help in understanding and debugging process errors, as well as
aggregate metrics. The document explains a bit about these, and gives pointers to their intended usage.

### [Composition of Mats Endpoints](developing/MatsComposition.md)

Do not wrap Mats Endpoints into Clients in service-internal APIs, as you might normally do when employing HTTP-style
inter service communications. This will hinder composition of Mats endpoints. Instead make "service-private" Mats
Endpoints.

TODO:

### MatsFactory

### Developing with mocks for collaborating services

### Testing