# Transactional demarcation and redeliveries

Mats run each Stage in a transaction, spanning the message consumption and production, and any database access. This
means that a stage has either consumed a message, performed its database operations, and produced a message - or done
none of that. If any of the operations within a Stage throws a RuntimeException, the processing of both the database and
message handling is rolled back, and the MQ will perform redelivery attempts, until it either succeeds, or deems the
message "poison" and puts it on the Dead Letter Queue.

## Transactions in the Mats JMS implementation: _Best Effort 1PC_

With the current sole implementation of the Mats API being the JMS implementation, an explanation of how this works
follows:

The JMS implementation of the Mats API currently have transaction managers for JMS-only, and JMS-and-SQL, the latter
both with "pure java" JDBC, or with Spring's `TransactionManagers` (typically `DataSourceTransactionManager`, but also
e.g. `HibernateTransactionManager`) so that you can employ for example Spring's `JdbcTemplate`, or Hibernate
integration.

There is only support for 1 DataSource in the transaction. This should, in a micro service setting, not really
constitute a limitation: It makes sense from a division of responsibility perspective that one microservice "owns" one
database, at least for write operations. (Whether you allow one microservice to "peek" into another microservice's
database, read only, probably due to performance considerations, is up to you. Don't go there without first having
considered doing the read over Mats, though.)

To combine JMS and SQL transactions, it employs _"Best Effort 1 Phase Commit"_, in that it runs the JMS transaction as
the "outer" transaction, and the database as the "inner" transaction, and committing them sequentially: Database first,
then JMS. The rationale is that the database might give lots of interesting problems like, obviously, SQL errors and
business process errors (wrong states) like duplicate key exception, but also more subtle problems like deadlock victim
and several other situations that might just transiently show up. On the other side, the JMS transaction is guaranteed
to go through: You are guaranteed that this consumer is the only one that are currently processing the current message,
and committing a JMS transaction with both received and sent messages will _always_ go through, _except_ if there are
actual hardware infrastructure problems (or, granted, bugs in the message broker or your setup of it).

Therefore, the Stage's transaction is set up in the following fashion:

1. Start JMS transaction
2. Receive message
3. Start JDBC transaction
4. Perform Stage processing:
    * Business logic
    * CRUD (Create, Read, Update, Delete) database operations
    * Send message(s)
5. Commit database transaction
6. Commit JMS transaction

.. where the ordering of the two last steps is the crucial point. If any of the parts in #3, #4 and #5 goes wrong, the
whole processing is rolled back, and redelivery will occur.

The only situation where this fails, is if #5 goes through (database commit), and then some infrastructure failure
hinders #6 to go through (JMS commit). You will now be in a situation where the entire processing has actually happened,
including commit to database, but the broker has rolled back.

If this was in an Initiation, the initiation will throw out. You should handle such situations as if any other external
communication error happened. Notably, if you did any "job allocation" or similar, picking work from a database table
for execution in one or multiple Mats flows, these flows will now not start. You should thus deallocate that work.

If this was in a Stage processing, you will now get a redelivery of the same message.

The problem with a redelivery of an already processed message is that if you've e.g. already inserted the new order,
you'll either insert the order once more - or if you run with "client decides ids", which you should - you'll get a
duplicate key exception, and thus abort the processing. However, any outgoing message wasn't sent on the first delivery
and processing, and will now not ever be sent, since the same situation will be present for each redelivery. The Mats
Flow will thus erroneously stop. The message will eventually be DLQed.

A solution to this is to pre-emptively check whether the order is already entered, and if so, "jump over" the business
processing, and just send the outgoing message.

Note that this is not a problem if the Stage only performs reads: This is a "safe" idempotent method, and the second
delivery will just be processed again, this time the JMS commit will most probably go through. The same holds for any
other idempotent operations: Deleting an already deleted row is often not a problem (unless you verify "number of
affected rows"!), and updating a row with new information might also not be a problem if done multiple times.

2-phase-commit transactions (XA-transactions) on the surface seems like a silver bullet. However, even XA-transactions
cannot defy reality: If you have two resources A and B, then prepare both, and then commit the A, and then everything
crashes, you now have a committed resource A, and a prepared-but-not-committed resource B. This will be present on the
resource B as an "in doubt" transaction. Handling such outstanding in-doubt transactions is a _manual_ clean-up job. So
what have you really gained, compared to the above "Best Effort 1PC"? With the latter, you at least have an option of
coding your way around the situation.

### Outbox pattern

There is actually a silver bullet, though: This is the "outbox pattern" - _but this is not yet implemented in Mats_.

The trick here is to store the (serialized) outgoing message inside the same database and the same transaction as the
other database operations in the Stage. You will now be guaranteed that the commit of the database, and commit of the
outgoing message, either both happens or both not - as they _are_ the same transaction. You'll try to send and commit
the JMS transaction as normal, but you'll also have a job on the outside that periodically scans for unsent messages in
the outbox table, and ships them away. Thus, a failure with the JMS commit will now just lead to a bit more latency.

This will however lead to possible double deliveries: Both will the initial failed JMS commit result in a new delivery
to this Stage, but you might also on the sending side produce double sends to the next Stage in the Flow: You will now
want to ensure the JMS message being sent, and therefore commit the JMS transaction before the DB, and if the DB now
fails the commit, you can end up in double sends.

Thus, you also have an "inbox" table, which also is committed inside the same, single DB-transaction. This only needs to
hold the message id of the incoming processed messages. Its only purpose is to ditch incoming messages that are already
processed.

So the full solution will be an "inbox-outbox pattern". The outbox to ensure sending of outgoing messages, and the inbox
to catch duplicate deliveries.

Needless to say, such a solution does not improve performance! The intention is to be able to selectively enable this
only for stages that aren't idempotent. Again, stages are idempotent either by nature (reads, and possibly updates and
deletes), or by explicitly having code to handle the above described possible problem (which mainly should be with
create).

## Redeliveries

For ActiveMQ, the redelivery policy _per default_ constitute 1 delivery attempt, and 6 redelivery attempts, for a total
of 7 attempts. It is worth understanding that these redelivery attempts _per default_ is performed _on the
client/consumer side_, blocking further deliveries until the message either is handled, or DLQed. The rationale for this
is to uphold the message order, so that no later sent message may sneak past an earlier message just because the earlier
hit a transient error and needed redelivery. This means that the StageProcessor (the thread) that got a poison message
will "stutter" on that message until it either works out, or the message is DLQed. Since there is a delay between each
attempt, this will take multiple seconds. If you for some reason (typically a coding error upstream of the Mats Flows)
end up with a whole bunch of poison messages, this entire Stage, with all its StageProcessors, on all the instances (
replicas) of the service, might effectively "stutter" on redelivery attempts while slowly chewing its way through that
bunch of poison messages.

### Never rely on message order!

However, You should **never** use Mats with a logic that relies on message order. Each Mats Flow should be totally
independent, and the order in which a Mats Flow runs and completes compared to any other Mats Flow should not be
relevant to the correctness of your application. The reason is that a Mats Flow may constitute many different message
passings between different Stages of different Endpoints. If you start two Mats Flows with message 1 and message 2,
there is no way to ensure that this flow passes by Stage N of Endpoint Y in the order you sent them out. This because
you should have multiple instances (replicas) of each Service, and each Stage has multiple StageProcessors - and any
skewing in processing time, e.g. a garbage collection kicking in, or just simply the thread scheduling of either the
broker's delivery mechanism, or the StageProcessors, or any network effect, could make message 2 overtake message 1.

Therefore, this concept of ActiveMQ's client side redelivery, blocking further deliveries till either success or DLQ, to
keep strict message order is of no value whatsoever for Mats' usage of the message broker - and might rather be
considered detrimental to its usage.

For ActiveMQ, you may turn on non-blocking redelivery on the client
ConnectionFactory: `ActiveMQConnectionFactory.setNonBlockingRedelivery(true)`. The redelivery is still performed on the
client, but now new messages are delivered concurrently with the waiting period between deliveries for the message that
initially failed.

There is also an option for broker-side redelivery, but this solution is somewhat of a tack-on to the original
client-side solution, and was found to not give the desired benefits. _YMMV._

## Links for ActiveMQ:

* [Redelivery Policy](https://activemq.apache.org/redelivery-policy) How redelivery policies work,
* [Message Redelivery and DLQ Handling](https://activemq.apache.org/message-redelivery-and-dlq-handling) - which
  includes a section at the bottom on configuring for Broker-side redelivery.
* Relevant StackOverflow question wrt. client or broker side redelivery, with a good
  answer: [ActiveMQ Redelivery Policy - How does it work?](https://stackoverflow.com/q/29689587/39334)

## Links for XA, 2-phase commits:

* Good article about transactional demarcation between database and JMS, explains "best effort
  1PC": [Distributed transactions in Spring, with and without XA](https://www.infoworld.com/article/2077963/distributed-transactions-in-spring--with-and-without-xa.html)
* Martin Fowler on 2-phase
  commits: ["This is the reason that while XA transactions seem so attractive, they often run into issues in practice and are avoided."](https://martinfowler.com/articles/patterns-of-distributed-systems/two-phase-commit.html#FailureHandling)
* XA-transactions needs cleanup: Amazon's ActiveMQ
  service: [Avoid slow restarts by recovering prepared XA transactions](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/recover-xa-transactions.html)