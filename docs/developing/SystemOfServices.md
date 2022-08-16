# What is a Service and a System

In these developer-focused documents about Mats, there is frequent references to _Service_ and sometimes _Application_,
as well as a _System_.

Mats is meant to be an inter-service communication solution. Therefore, there are obviously multiple services involved
in a system utilizing Mats. We are therefore talking about an architecture style of _MicroServices_, or to use an older
and maligned term, a _Service Oriented Architecture_.

> Mats is _not_ meant to be a way for external entities to communicate with your system. External entities here mean your end-users' browsers and phones, or your business customers' computer systems, or your fleet of IoT devices needing to phone home. For this, use e.g. HTTP/REST/SOAP along with `MatsFuturizer`, or possibly the MatsSocket solution. Or e.g. Kafka or Clickhouse or similar if you need to handle massive amounts of data ingestion - this is not what Mats is good at.

A set of services which make up a specific purpose is a _System_: _"A set of things working together as parts of a
mechanism or an interconnecting network; a complex whole"_. A System of Mats-employing Services will typically utilize a
single message broker to communicate, making it possible for any service in the system to communicate with its dependent
services.

Businesses might have multiple systems. The banking unit, insurance unit and broker unit of a financial services company
might each have one - or possibly multiple - systems.

A service with an actual GUI meant for end users (not only an introspection GUI for developers) might be called an
_Application_.

## Developing and running a Mats Service

The Mats library itself abstracts the communication with a message broker in a way that makes it simple to create
invokable asynchronous endpoints. Its inner workings are not very complex: It sets up small thread pools that listens to
queues, invoking a user-specified lambda every time it receives a message on one of these queues. It also has a function
to send one or more messages to a queue. This is pretty much it - the rest is packaging up the message in an envelope
containing a stack, which makes the semantics of sending and receiving messages way simpler than doing it "raw".

You may use Mats however you like! You can code a single-file `public static void main`-method that directly creates an
`ActiveMQConnectionFactory`, using hardcoded strings for its URL and credentials, then create a `JmsMatsFactory`,
providing the ConnectionFactory to it. You may then go ahead and create any endpoints you want, providing handling
lambdas for each of the endpoints' stages. And then the main-method may exit, and you'll have a piece of code which when
run will connect to that ActiveMQ message broker and provide those endpoints. You could also let the main thread not
die, but instead act on some external events, e.g. read some (hardcoded!) file from the filesystem, and initiate a
message each time it changes.

The resulting piece of code will be some dozens of lines. And it could arguably be called a Service.

However, the developability, quality-control-ability, maintainability, serviceability and debuggability of that service
would be rather poor.

So, when these documents talk of a Service, this is what is meant:

1. The codebase has a solution for being run _in development_, i.e. you should be able to check out / clone the code,
   start your IDE, and preferably without any config be able to find a specific Java file, and "right-click -> debug" -
   and you should now be a productive coder. This implies that it needs to be able to mock dependent services.
2. It has a solution for running tests, both unit tests and integration tests. Tests should be possible to
   "right click -> run" in your IDE, again implying mocking of dependent services.
3. It obviously has a build system, which also caters to testing. For example `./gradlew clean check build` should work
   and run your tests, and build your single deployable artifact.
4. The sole built artifact should be able to run in your different environments, e.g. non-prod/pre-prod/staging, and
   production. This implies that it either relies on switches to choose from a set of configs, or by some other means
   sniff out which environment it finds itself in to do such a selection.
    1. A special environment is for end-to-end tests, where the finished artifact is brought up, and another piece of
       the codebase runs tests against it. This environment might behave similar to development and test, where
       dependent services are mocked.
5. And perhaps a bit controversially, it should run along with a HTTP service, typically a Servlet Container. This is
   both to be able to make introspection tooling over HTTP, and to provide metrics, e.g. Prometheus scrapes. For an
   Application, i.e. a service with a GUI meant for end users, HTTP will probably be needed anyway.

Additional components of a good services architecture are, in the humble opinion of the author:

1. Common logging setup, so that you get the same good information from any service in the system. The log should be
   sent to a centralized system like Elastic/Kibana.
2. Metrics set up and available for scrape, with easy ability to create service-specific metrics.
3. Tracing.
4. Health checks, where it is possible to easily create service-specific checks.
5. A service-local introspection HTML GUI. This can show generic information like current config, system information
   like CPUs and memory etc. In addition, enabling developers to display service-specific information to themselves is
   great.

For more on developing services utilizing Mats, read [Developing with Mats](DevelopingWithMats.md).

## Common service foundation

Since there will be multiple of these services in the system, one should strive to make a common base / foundation on
which they all are built on.

This should ideally abstract away as much of the commonalities as possible, e.g. servlet container setup, logging setup,
monitoring setup, health checks, metrics, etc.

Such an abstracting of a common fundament is what _Spring Boot_ has done, and _Dropwizard_ before it. Mats do not yet
(2022-06) have any _Spring Boot Starter_ which would provide an opinionated way to set this up. However, by
reading [Developing with Mats](DevelopingWithMats.md) and [Testing With Mats](TestingWithMats.md), you should have a
fairly good understanding of what to handle, and what the Mats libraries bring, to help set up such handling.

Wrt. a system using Mats, a relevant piece to include in a foundation is the setup of the MatsFactory, as well as Mats
logging and metrics, since that should be identical for all communicating services in the system.