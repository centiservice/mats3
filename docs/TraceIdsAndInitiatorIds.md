# TraceIds and InitiatorIds

Mats has several concepts for aiding debugging, introspection, logging and tracing, and metrics gathering.

These concepts are:

1. A MatsFactory must be instantiated with an ApplicationName and ApplicationVersion. This is meant to be name of the
   containing service. E.g. if you have an "OrderService" of version "2022-02-18-build4321-e7084be" containing a
   MatsFactory, then that would be the `appName` and `appVersion`.
2. A MatsFactory also has a `name`, which is relevant if a service has multiple MatsFactories, e.g. connecting to
   multiple backing message brokers - the name should then reflect which broker it is connected to.
3. Every Mats initiation, which initiates a _Mats Flow_, must create a unique `TraceId` which follows the Mats Flow till
   it ends.
4. .. and an initiation must also state its `InitiatorId`, which should be unique as to where the initiation happens,
   but not unique per initiation.

All Stages in a Mats flow, from its initiation to its end which is when a stage doesn't create an outbound flow message,
will have the following common elements present (and this holds no matter which host it passes through).

1. InitiatingTimestamp
2. InitiatingAppName, InitiatingAppVersion
3. InitiatorId
4. TraceId

(Each individual Stage also knows who directly called it, i.e. the FromApplicationName|Version, and FromStageId)

Understand that Mats itself will work just fine even if you set all these to the empty string - this is _purely_ for
human consumption, to aid in reasoning about bugs and performance. But the fact that these elements follow along with
the Mats flow, no matter which server is processing it currently, is simply amazing, and you will very much regret not
having set a good InitiatorId and TraceId once you sit with 200 random messages on a DLQ, or wonder why _some_ of these
extremely similar Mats Flows take an age to complete, while most other run fast.

To utilize these features to their fullest, here's some guidelines:

## ApplicationName + InitiatorId

This should reflect _from where_ an initiation happens: The InitiatingAppName combined with the InitiatorId should
immediately convey which service initiated this Mats Flow, and from _where within that service_ the flow was created.

For example, if the flow is initiated from a "/api/userDetails" REST endpoint, the initiatorId could well be "http:
api/userDetails". If the flow is initiated within a batch process fulfilling orders, it could be
'OrderFulfillmentScheduledTask' or something relevant. The point is to immediately understand from which particular part
of the total codebase these flows are started from - and if you do not already know that service, you go to the service
named in the InitiatingAppName, and search for the InitiatorId, and should then immediately find the location.

If multiple flows are started from the same place, and this is obvious when you enter the code, and it makes logical
sense, they should share a common prefix. This gives the added benefit of being able to collate such flows in logging and
metrics. For example, if the "/api/userDetails" REST endpoint fires off multiple MatsFuturizer requests (thus
parallelizing them), it makes good sense that they all share the "http:api/userDetails", while adding a "sub id", e.g.
"http:api/userDetails:latestOrders", "http:api/userDetails:credit" and "http:api/userDetails:benefits".

For the Stage directly receiving an initiation, the `ProcessContext.getFromStageId()` == the InitiatorId - and this is
also a way to look at it: It is the identifier of the fictive Mats Stage representing the "0th call" in the Mats Flow.

## TraceId

A TraceId is a String. The first and foremost aspect is that it should be unique, to uniquely identify all the log lines
of a particular Mats Flow in the system-wide logging system. A UUID definitely fulfills this requirement - but it falls
flat in every other aspect a good TraceId could help with. (Even for the unique-requirement, it fails. It is way too
verbose; both is it way too unique wrt. the "universe" it lives in, and it only utilizes 16 different code points, both
resulting in the string being annoyingly long compared to the little information it holds.)

Making a good TraceId is (evidently) an art! From the TraceId _alone_, one should ideally be able to discern as much
information as possible, while still keeping the string as short as possible!

A good traceId should contain most of:

1. Some "from"-context.
    1. Quite possibly a short-form of the ApplicationName: "CustPortal", "Order", "Warehouse", "Auth".
    2. Possibly a short-form of the Initiator point: "userDetails", "orderReceived"
2. What it concerns: Possibly a short-form of the "to" aspect: "calculateShipping", "sendTrackingUpdate"
3. Which entity or entities the flow concerns: CustomerId, OrderId, SKU
4. IF (and only if!) this is part of a batch of messages, then include a "batchId", so that one can search for all Mats
   Flows originating from the same batch.
5. A piece of randomness, so that if for some reason two otherwise identical Mats Flows are produced, either on purpose
   or accident (the user managing to place an order twice), the Mats Flows can be distinguished anyway.

The TraceId should be established as close to the event triggering the Mats Flow as possible. If the action originates
from a user interface, it should preferably be started there. _(For information about automatic prepending of an
incoming HTTP-call's X-Request-ID header, check out the `FactoryConfig.setInitiateTraceIdModifier(function)`
functionality)_

When storing an order in the order table, it makes sense to also store the TraceId along. This both to correlate the
order with the logging, and since it has all these nice contextual elements in it, you may understand how that order
ended up in the table. Also, when this order moves along, e.g. gets fulfilled, then the resulting Mats Flows should be
initiated with a TraceId containing the Order-TraceId as a part. You may then gain a great effect in being able to
follow the flow of that order from when the user clicks "buy" to it being delivered on his door via search of a single
string.

There's two characters that could be considered (somewhat) reserved:

* "+": When a TraceId is composed from multiple parts
    * If the HTTP call includes a X-Request-ID header, this could be prepended to the resulting Mats Flow's TraceId with
      a "+".
    * If some process instruction is stored in a table via one Mats Flow, and then a specific event targets that, the
      resulting MatsFlow could be the "sum" of the original TraceId and the new event's (Trace)Id. (Note that if the
      event is of a type that may trigger multiple such process instructions, you should use the next special character
      instead!)
    * Think of it as a _string concatenation_ of TraceIds/EventIds.
* "|": If a Mats Flow A (or an event) spawns multiple new Mats Flows B1 and B2, the result TraceIds should be "A|B1"
  and "A|B2".
    * It is employed automatically if you initiate messages within a Stage, i.e. `ProcessContext.initiate(..)`
    * If a new shipment of widgets comes in, this would result in a bunch of orders becoming eligible for fulfilment.
      The resulting bunch of new Mats Flows could have TraceIds of the form "{single incoming
      WidgetShipmentTraceId}|{original OrderTraceId}"
    * Think of it as a _fork_/_spawn_ of TraceIds.

There is no hard and fast rules here, other than the uniqueness requirement. It should just be as helpful as possible.
Here's some fictive examples, just to give a hint towards which type of information pieces that may be of use in a
TraceId:

* `Web.Cart.shippingPage[cartid:32f93818a27b2]lo85cma+CustPortal.calculateShipping[cid:12345631][oid:68372957]xhe71pj`
* `Web.Cart.placeOrder[cartid:32f93818a27b2]758aj4l2+CustPortal.placeOrder[cid:12345631][oid:68372957]8kfm39i`
* `Warehouse.fulfilOrders[batchId:uj2lqlcv]|fulfilOrder:Web.Cart.placeOrder[cartid:32f93818a27b2]758aj4l2+CustPortal.placeOrder[cid:12345631][oid:68372957]8kfm39i`
