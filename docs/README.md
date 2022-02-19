# Documentation for Mats<sup>3</sup>

You should probably read the top-level [README.md](../README.md) file first!

## Introduction

### [What is Mats?](WhatIsMats.md)

### [Rationale for Mats](RationaleForMats.md)

## Coding

### [TraceIds and InitiatorIds](TraceIdsAndInitiatorIds.md)

Mats Flows contains several metadata that is meant to help in understanding and debugging process errors, as well as
aggregate metrics. The document explains a bit about these, and gives pointers to their intended usage.

### [Composition of Mats Endpoints](MatsComposition.md)

Do not wrap Mats Endpoints into Clients in service-internal APIs.