# Metrics plugin using Micrometer

JmsMatsFactory have special handling for this plugin, auto-installing it if it resides on the classpath.

## "Scratch pad" for interesting things to measure, WIP:

* Number of messages total in system
  * Sent
  * Received
  * .. these should be extremely equal
  * .. if they are not, it is because of retries - find endpoints that often end in retry
* Number of messages sent from a particular
  * AppName (sum all initiations and stage-sent messages)
  * InitiatorId
  * Endpoint (not directly available as only stageId is present - is there a "like" operator?)
  * StageId
* Graph multiple of these over time
* Time taken (total, msg-handling, db commit, msgsys commit)
  * Msgsys commit vs. total number of messages / "total mass" - correlation?
  * .. or maybe concurrent for same node?
* Size of messages
* Number * Size message = "total mass"