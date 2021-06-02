# What is needed

## Initiator

* There will typically be only ONE Initiator per JVM
  * .. but there can be several
* Each of them is-a different JmsMatsTxContextKey
* Initiators are multi-threaded, in that e.g. all Servlet threads might be "inside" the same Initiator at the same time
* .. While a JMS Session is single threaded.
* Which means that a Initiator needs a typical _pool_ of Sessions
* .. Which means that the semantics is that they "release" or "return" the Session back to a pool
* .. And even if the last Session is released, the Connection (and the Sessions of it) shall live on 


## Endpoints, Stages - StageProcessors

* The actual processing part is the StageProcessor - this is the one that needs a JMS Session, and
will make a consumer and producers off of it.
* It will not give up on the Session until it is stopped.
* When it is stopped, it will close the Session.
  * .. when the last Session of a Connection is closed, one shall close the Connection.

### Connection Sharing Semantics

* There is no "pool" semantics here, rather there is a possible Connection Sharing Semantics.
* A Connection can be shared at the level of StageProcessor, Stage, Endpoint and the entire MatsFactory. 

## General

* If one JMS Session crashes, all the Sessions from the same Connection must be invalidated.
  * And they should "come back" to the pool using "close" semantics, not "release".
  * When the last one comes back, the Connection shall be closed (possibly "again" if it already was closed)

