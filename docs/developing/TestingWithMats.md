# Testing with Mats

_Read [System of Services](SystemOfServices.md) first!_  
_Then read [Developing with Mats](DevelopingWithMats.md) afterwards!_

As mentioned in [Developing with Mats](DevelopingWithMats.md), Mats is a clean Java API, but has optional Spring
integration. This means that there are two modes of doing testing: Either clean Java plus JUnit/Jupiter
Rules/Extensions, or Spring based annotations for JUnit/Jupiter. We will go through both modes.

**Notice: You cannot combine the Mats `Rule_Mats`/`Extensions_Mats` with the Spring annotations test tools: You'll get
two different MatsFactories talking to two different in-vm message brokers.** You will then probably create your
Spring-oriented Mats endpoints on the Spring based message broker, but then try to initiate Mats flows on the
Rule/Extension based message broker, which then won't have any endpoints listening.

The test tooling is created around an actual instance of `JmsMatsFactory`, again needing an actual message broker to
work. All test tooling uses `MatsTestBroker` to get hold of a JMS `ConnectionFactory`.

# MatsTestBroker

The `MatsTestBroker` is utilized by all test tooling. It is a utility class providing a JMS `ConnectionFactory`, which
by default is backed by an in-vm ActiveMQ broker. This can however be changed by system properties: Both can it instead
create an instance of Artemis, and it can also not create an in-vm broker, instead connecting to a specified external
ActiveMQ or Artemis broker.

Read the JavaDoc of the class.

# Testing with clean Java

As shown in [Developing with Mats](DevelopingWithMats.md), a MatsFactory can be programmatically cooked up pretty
easily. However, as also mentioned briefly, there are some JUnit/Jupiter Rules/Extensions to help with setting up common
infrastructure, to make test classes as succinct as possible.

## JUnit and Jupiter

The Junit and Jupiter tooling resides in the `mats-test-junit` and `mats-test-jupiter` libraries. The JUnit and Jupiter
tooling is effectively identical (the `Rule_Mats` and `Extension_Mats` share the base code), so we'll mostly discuss the
JUnit variants.

If you clone the Mats code, you'll find a few tests inside the code of both those two libs, the tests being identical
except for the differences between JUnit and Jupiter.

Also, all the Mats API unit tests reside in the module `mats-api-test`, and employ this type of testing.

The `Rule_Mats` must be used as a `@ClassRule`, thus needing to be a `public static` class field. (Or a `@Extension`
static field with Jupiter)

It provides the following:

1. JMS `ConnectionFactory` to an in-vm message broker created with `MatsTestBroker` (NB: Read chapter above, it can be
   directed to not use in-vm, and can also use Artemis). _This should not be used directly!_
2. `MatsFactory` using the ConnectionFactory. This is used to create Mats Endpoints in the test.
3. Default `MatsInitiator`, just for one less method chaining! (it directly fetches it from the MatsFactory)
4. A single `MatsTestLatch`, a simple tool for being able to communicate from a test-specific Terminator back to the
   test method, informing the test that an answer has arrived. You may also create such instances yourself.
5. A `MatsFuturizer`, as a simple way to invoke an endpoint under test. This is often simpler than using the
   MatsTestLatch, although the latter might still come in handy in certain scenarios.
6. `MatsTestBrokerInterface`, a tool to communicate with the broker underlying the MatsFactory, i.e. with the
   ConnectionFactory. This is used to check for DLQs: Some endpoints may refuse a message if it is not business-wise
   correct, and the incoming message shall then end up on a DLQ. You want to assert this, and the
   MatsTestBrokerInterface helps you with that.
7. Optionally, an H2 database JDBC `DataSource`.

## Coding clean Java tests

Actual unit testing, where you test the service's pure Java code and its internal API and domain model, should not be
affected by employing Mats: I.e. an internal service that calculates the Value Added Tax for some amount will still be
unit tested as you've always done.

For each integration test class, typically testing one of the service's Mats Endpoints using multiple `@Test` methods,
you "cobble together" whatever the unit-under-test needs of services and internal infrastructure - as well as mock out
any external dependencies, being it Mats endpoints or other types of services, e.g. HTTP or SQL. The Mats infrastructure
is, as pointed out above, typically handled well by `Rule_Mats`. Alternatively, you might just yourself create a
MatsFactory backed by an in-vm ActiveMQ. If you do it manually, remember to clean up, either inside the test method, or
by using `@AfterClass`. Otherwise, you'll end up with lots of ActiveMQs and MatsFactories, as well as their threads, in
the VM as the test suite progresses. Cleanup is done automatically when employing Rule_Mats.

You create the Mats endpoints, both the endpoint-under-test, and dependent endpoints (both internal, and external mocks)
by using a `@BeforeClass`-annotated method (`@BeforeAll` in Jupiter). Assuming that you in the Service have methods
setting up the different Mats Endpoints the service provide, which take a MatsFactory as argument: You'll invoke one or
multiple of these setup methods, now providing the test MatsFactory from the `Rule_Mats` instance.

There's a convenient `Rule_MatsEndpoint` (`Extension_MatsEndpoint` for Jupiter) which might be of interest: This is a
"programmable/verifiable test endpoint" which typically is used to mock dependent endpoints for the endpoint-under-test.
You add it to the test class as a `@Rule` non-static public field (`@Extension` for Jupiter, non-static field). You can
have multiple. Its first feature is that you may change the _process lambda_ during the running of the test class, i.e.
for one `@Test`, it answers "customer is good", while for the next `@Test`, it answers "customer has debts". The second
feature is that you can verify that it was invoked (getting the incoming message), or that it was not invoked. Read its
JavaDoc!

Depending on how you've coded the system, you might want to create test-specific Terminators to receive answers, or
events, from the endpoints being tested. Again the `Rule_MatsEndpoint` might be of help.

Inside the `@Test` methods, you then create a RequestDTO, and fire it off using an Initiation, or use a MatsFuturizer to
send the request and get a `CompletableFuture` back. You then assert whatever is relevant:

1. The ReplyDTO from the invoked endpoint-under-test has the expected values
2. The database now contains the expected rows
3. The mocked endpoints was invoked as expected (possibly using `MatsTestLatch`, or `Rule_MatsEndpoint`)
4. You have gotten the expected event messages
5. Anything else you expect - or expect _not_ to happen!

# Testing with Spring

When using Mats with Spring, you need to have a MatsFactory in the Spring context. The Mats SpringConfig `@EnableMats`,
which fires up the BeanPostProcessor `MatsSpringAnnotationRegistration`, will scan the Spring beans for any Mats
annotations, and use the MatsFactory from the context to create the Endpoints on.

This means that when running tests in a Spring-utilizing project, we need to create a MatsFactory and get it into the
Spring context - and this needs to happen during the Spring setup phase.

You can make a MatsFactory with a backing in-vm message broker yourself, using a `@Bean` method on a `@Configuration`
for the test.

TODO: Make code example

However, Mats' `mats-spring-test` provides a set of annotations and tools to help with this, which also provides a few
other perks like `Rule_Mats` does in clean Java testing.

TODO: Make code example

## Philosophy: Cobble-together, vs. full-service-config

When testing with Spring, you can use the same style as with ordinary JUnit testing, detailed above: Cobble together a
test scenario by pointing to the Service's relevant `@Components`, `@Services`, `@Repositories`, `@Controllers`
and `@Configuratoins` - possibly containing `@MatsMapping`-annotated methods, as well as classes annotated
with `@MatsClassMappings`. You'll also set up mocked dependencies using test-specific `@Configuration` classes
and `@Bean` methods. Then, when you have a correct subset of the service's functions ready to go, with dependencies
mocked out, you run your integration tests against it.

However, there is also a style where you just point the test class's `@ContextConfiguration` to the application's full
setup configuration - but put the Spring context into "full lazy" mode: Only the beans actually referenced by the test
instance will actually be brought up, _as well as their transitive Spring dependencies_. You may in some tests mock
out, _"overwrite"_, certain beans of the actual project code using _Mockito_. This is what the project _Remock_ helps
set up, and what Spring copied into their _MockBean_ solution.

While this author favoured the "full config" mode for a long while, due to the often extreme simplicity of creating _one
more test_, he has changed his mind! The primary driver for this mind-changing was test execution speed: Since the full
service config will typically have to do classpath scanning to find everything, and the Spring context will be fully set
up, even though he beans weren't populated/initialized, the setup process becomes pretty weighty.

However, when beginning to use the "cobble together" mode, a second benefit manifests: It turns out that this not only
goes faster, sometimes way faster, but also is more concise and explicit: One becomes acutely aware of the intra-service
dependencies, i.e. dependencies between pieces of the service's code. For example, if you were only supposed to test a
small little piece of the code, but end up having to pull in 90% of the service and mock out nearly all the service's
dependencies, then your code is probably not structured very cleanly. Such facts are totally hidden for you when using
the "full config" mode, as any piece of the service's code is just an `@Autowire`/`@Inject` away, and whatever those
pieces transitively needs as dependencies will just magically also be pulled up. The only clue to such transitive
networks of intra-service dependencies is that the test takes longer time than you'd expect, and possibly that you get a
ton more logging than expected, from all the dependencies starting up (assuming that you are a coder that logs!).

## Coding "Cobble together" Spring tests

Standard unit testing: Point specfically to services and endpoints that you want to bring up, mock out the rest, like
other services, dependent services and db.

You may of course start from clean Java and yourself cook up the Spring ApplicationContext from scratch - but Spring has
unit test-specific annotations for helping out with this.

In the same vein, you may yourself manage everything of Mats yourself for each test - it is like 3 lines of Java code to
get a working JmsMatsFactory from scratch - but Mats has a few tools and Spring-testing oriented annotations to simplify
setting up such tests contexts, with a tad richer test environment than those 3 lines provide.

## Coding "Full config" Spring tests

Point to the application's full `@Configuration` file. This is probably the one that also has any `@ComponentScan`
annotation on it, and is also probably full of `@Bean`-annotated methods.

Remember to @Autowire dependencies which have @MatsMappings and @MatsClassMappings, as otherwise they won't be "booted",
since the entire Spring context is in lazy init mode.

### MockBean / Remock

Replace some beans with mocks.

# Test granularity, and test infrastructure lifecycle

By using this way of integration testing, you'll create a full MatsFactory with a backing in-vm message broker for each
test class, spin up dependent components and mocks, and tear it all down after all tests (`@Test`-annotated methods) in
the class has finished. This might sound heavy - and it is - but it goes pretty fast nevertheless: The `mats-api-test`
contains 85 tests, some of which sends many messages, and some of which tests timeouts, and it runs in 8 seconds. When
the JVM is getting warm, a single test class with a single simple Mats test can run in 5-8 ms.

You should however consider having multiple `@Test` methods per class, testing all sides of some specific aspect of the
service within a single class - instead of having one class per single scenario. That is, when "cobbling together" the
set of services, endpoints and mocks to test a specific Mats endpoint of the service, you should create a bunch
of `@Test` methods testing as much as possible using that setup. The mentioned `@Rule_MatsEndpoint` helper class, which
can change behaviour by setting the process lambda within the `@Test` methods, can potentially help in this regard.

This is both beneficial in terms of speed (not needing to create hundreds of MatsFactories and message brokers, as well
as the other services and dependencies), but also with regard to readability and maintainability: The cobbling-together
part of an integration test can become large, and having this spread out or copied over many classes may become messy.

You can also modify the test setup during the run of a test class: You can delete endpoints of a MatsFactory - a feature
specifically meant for testing. That is, instead of creating all the mock Endpoints of the test in a `@BeforeClass`, you
may create some of them in the `@Test` method itself, and then delete them at the end of the method. This way you may
test even more elements using a single setup.

You should however not take this too far! One class should test the multiple sides of _one_ aspect of the Service,
typically one Endpoint, and not weer over into testing entirely different things. Doing that will probably end up with
too much infrastructure/cobbling-together in a single test class, making it messy. Any too similar parts between test
classes can potentially be factored out in common setup-classes.

What to test using unit tests, and what to test using integration tests, is a discussion we won't delve too deep into
here. There are arguments for and against both sides. Personally, this author tends to favour integration testing, as
that is the observable effects seen from the outside of the service and what actually needs to work! One is then more
free to do heavy refactorings within the service without breaking hundreds of unit tests in the process. Unit tests are
in this philosophy reserved for quite detailed pieces of the code: As an illustrative real-world example, calculating a
customer's tax dues for a set of buys and sells of a set of financial assets have literally hundreds of corner cases.
Running these hundreds of test using integration testing would be absurd. Rather, for this situation, we even created a
Groovy unit test DSL to be able to very quickly, in a very simple and readable manner, set up a new scenario and verify
the calculations.
