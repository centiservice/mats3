# Mats<sup>3</sup> Development

This README describes the layout of the Mats<sup>3</sup> project, and the development environment, and how to build and
publish.

# Modules / Jars

### API and implementation modules:
* `mats-api`: The Mats<sup>3</sup> API where most if not all coding will be against (unless you also use Spring, for
  which there are annotations for setup). The `MatsFactory` is the entry point, where you at application startup create
  Mats Endpoints, and then throughout the life of the application you initiate Mats Flows using a `MatsInitiator` which
  you also get from the factory.
* `mats-impl-jms`: The standard JMS-based implementation of the Mats<sup>3</sup> API, which also implements the
  Interceptor API. This is where you get the `MatsFactory` from, by creating a `JmsMatsFactory` instance, supplying
  a `JmsMatsTransactionManager` hosting a JMS `ConnectionFactory` and optionally a JDBC `DataSource`.
* `mats-serial` and `mats-serial-json`: A small sub API and implementation that the `mats-impl-jms` uses for serializing
  and deserializing to and from the wire format sent onto the JMS message broker. Both for the envelopes, and the
  messages sent.
* `mats-api-test-1` and `mats-api-test-2`: Most modules have their own unit tests, but these two modules (that are not
  put on Maven) contain the unit/integration tests for the Mats API and implementation. Divided in two to get the build
  faster via parallelization.

### Tools
* `mats-util`: Utility classes and tools for Mats<sup>3</sup> development - most notably `MatsFuturizer` for bridging
  the sync-async chasm between typically synchronous HTTP endpoints and asynchronous Mats<sup>3</sup> endpoints. Also
  the flexible `MatsEagerCache` system for caching frequently used (master) data owned by one microservice, on another,
  so you don't need to incur constant message passing between the two.


### Spring integration
* `mats-spring`: A Spring integration module, which provides annotations and a BeanPostProcessor that makes it possible
  to create Mats<sup>3</sup> Endpoints using `@MatsMapping` and `@MatsClassMapping` much like you use
  `@RequestMapping` to create HTTP endpoints in a Spring MVC application. Enable it by adding `@EnableMats` to your
  Spring configuration.
* `mats-spring-jms`: A set of tools for setting up a Spring-based application to use JMS Mats<sup>3</sup>, handling
  test, staging, and production environments. It also contains the transaction manager tying into the Spring transaction
  management for JDBC.
* Also `mats-spring-test`, see under Testing.

### Instrumentation
* `mats-interceptor-api`: Optional API that a Mats<sup>3</sup> implementation may choose to implement, which of course
  this implementation does. It enhances the solution with interception functionality, the two main classes are
  `MatsInitiateInterceptor` and `MatsStageInterceptor`, which are used to intercept "initiations" (where you start a
  Mats Flow), and "stages," where messages are processed.
* `mats-intercept-logging`: Employs the Interceptor API to log all initations and messages.
* `mats-intercept-metrics`: Employs the Interceptor API to measure a heap of metrics for all initations and stages.
* `mats-localinspect`: An embeddable HTML view that provides comprehensive introspection facilities for the
  MatsFactory, and also provides rudimentary metrics for all Endpoint and Terminator's Stages.

### Testing
* `mats-test`: Base test tooling, extended to JUnit 4 and Jupiter (JUnit 5).
* `mats-test-junit`: JUnit 4 test tooling, e.g. `Rule_Mats`, which sets up a broker and a `MatsFactory` for you.
* `mats-test-jupiter`: JUnit 5 test tooling, here it's `Extension_Mats`.
* `mats-spring-test`: Tooling to make it easier to do testing in a Spring-based Mats<sup>3</sup>-employing application.
* `mats-test-broker`: Provides a way to very simply get an in-vm Apache ActiveMQ Classic or Artemis broker running for
  setting up the a MatsFactory. All test tooling uses this - and you can use it directly.

## Gradle tasks:

Note the property `mats.build.java_version` which can be set on the Gradle invocation using
`-Pmats.build.java_version={version}` to override the default Java 11 version. This is used by the matrix build in
GitHub Actions. Note that the command line Java version must match this - Gradle's "build tooling" is currently not
used.

* `build`: Build code, jar files, javadoc, and runs tests.
* `build -x test`: build, skipping tests.
* `systemInformation`: Shows Java Properties, System Environment, and versions of Java, Gradle and Groovy. The Java
  version shown here is also the one used for building.
* `allDeps`: Runs the `DependencyReportTask` for the Java projects (i.e., get all library/"maven" dependencies for all
  configurations)
* `dependencyUpdates --refresh-dependencies --no-parallel`: Checks for updates to dependencies (not compatible with
  parallel)
* `clean`: Clean the built artifacts.

## Development

Development is mostly done "test driven" in that the tests are the primary way to run the code during development.

However, there are also three "development servers" (right-click run in IntelliJ):
* [LocalHtmlInspect_TestJettyServer](mats-localinspect/src/test/java/io/mats3/localinspect/LocalHtmlInspect_TestJettyServer.java)
  For developing the visual HTML introspection system. 
* [MatsMetrics_TestJettyServer](mats-intercept-micrometer/src/test/java/io/mats3/test/metrics/MatsMetrics_TestJettyServer.java)
  For developing the metrics interceptor / plugin. This is a simple Jetty server that serves a GUI for viewing the
  metrics. There are no unit or integration tests for metrics - it is done "by hand" by actually running initiations and
  endpoints and stages, and then observing whether metrics kick in as expected.
* [EagerCache_TestJettyServer](mats-util/src/test/java/io/mats3/util/eagercache/EagerCache_TestJettyServer.java) The
  EagerCache system do have unit and integration tests, but in addition there's a visual HTML dashboard for viewing the
  cache state. This server is to develop this introspection system.

## Testing

As mentioned above, since the primary development of Mats is done "test driven", there are quite a few tests. You can
run them all with `./gradlew test`, and you can point to any individual, or a folder of them, in IntelliJ and
right-click run them. They use the JUnit 4 test tooling - thus setting up a broker and a `MatsFactory` internally,
there is no (external) setup required.

## Publishing

**NOTE: First update the version in root `build.gradle` and `JmsMatsFactory.java`, read below for format per
release type!**

**Remember to git commit and tag the version bump before publishing, read below for git tag and message format!**

For Java publishing, we use Gradle and the
[Vanniktech maven.publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/central/), which uploads via
the Portal Publisher API.  

To see what will be published, we rely on the Central Portal's "review" functionality.

Release types / SemVer tags:
* Experimental (testing a new feature / fix):
    * Prefix `EXP-`, suffix: `.EXPX+<iso date>` to the version, X being a counter from 0.  
      Example: `EXP-1.0.0.EXP0+2025-10-16`  
      Description: "Experimental release"
* Beta (while working on major overhauls):
    * Prefix `B-`, suffix: `.BX+<iso date>` to the version, X being a counter from 0.  
      Example: `B-2.0.0.B0+2025-10-21`  
      Description: "Beta release"
* Release Candidate (before a new version, testing that it works, preferably in production!):
    * Prefix `RC-`, suffix `.RCX+<iso date>` to the version, X being a counter from 0  
      Example: `RC-1.0.0.RC0+2025-10-16`  
      Description: "Release Candidate"
* Release
    * Suffix `+<iso date>` to the version.  
      Example: `1.0.0+2025-10-16`  
      Description: "Release"

### Transcript of a successful publish:

#### Change version number and build:

* Change version in `build.gradle` and `JmsMatsFactory.java` to relevant (RC) version! Read above on the version
  string format.

* See over [CHANGELOG.md](CHANGELOG.md): Update with version and notable changes.  
  "Coalesce" any non-release versions into the final release.

Build and test:
```bash
$ ./gradlew clean build
```

#### Commit and tag git:

Commit the version bump, message shall read ala: _(Note "Candidate" in the message: Remove it if not!)_   
`Release Candidate: RC-1.0.0.RC5+2025-05-20  (from RC-1.0.0.RC4+2025-05-15)`

Tag git, and push, and push tags. _(Note the "Candidate" in the message: Remove it if not!)_
```shell
$ git tag -a vRC-1.0.0.RC5+2025-05-20 -m "Release Candidate vRC-1.0.0.RC5+2025-05-20"
$ git push && git push --tags
```

#### Publish to Maven Central Repository:

```shell
$ ./gradlew publishToMavenCentral
```

Afterwards, log in to [Maven Central Repository Portal](https://central.sonatype.com/publishing/deployments), find the
newly published version.

Check over it, and if everything looks good, ship it!

#### Verify publication

It says "Publishing" for quite a while in the Portal. Afterwards, it should say "Published". It might still take some
time to appear in all places.

Eventually, the new version should be available in:
* [Maven Central Repository](https://central.sonatype.com/namespace/io.mats3) - First place it appears,
  directly after "Published" on Portal. Same style GUI as Portal.
* [repo.maven.apache.org/maven2](https://repo.maven.apache.org/maven2/io/mats3/) - HTML File browser. This is
  where Maven/Gradle actually downloads artifacts from, so when they're available here, you can update your projects.
* [MVN Repository](https://mvnrepository.com/artifact/io.mats3) - good old MVN Repository. Often VERY slow to display
  the new version.
