## B-2.0.0.B0+2025-10-22

* New major version, due to Java 21 and Jakarta namespaces.
* **Moved over to jakarta-namespace for all javax libraries, most notably JMS.**
* **V2-series will require Java 21.**
* **No changes in the Mats<sup>3</sup> API!**  
  _As long as you get the dependencies upgraded, your Mats<sup>3</sup> Endpoints, Stages and Initiations will
  work without change._
* Upgraded to Gradle 9.1.0. Finally, no "Deprecated Gradle features were used in this build..."!
* All dependencies upgraded, now past the Java 17+ requiring libs.
* Core (Mats<sup>3</sup> implementation):
  * JMS 3.1.0
  * Jackson 3.0.0
* Specific modules (Mats<sup>3</sup> SpringConfig)
  * Spring 6.2.12
  * Jakarta Inject 2.0.1
  * Jakarta Annotations 1.3.2
* For testing:
  * Hibernate 7.1.4.Final
  * ActiveMQ 6.1.7
  * Artemis 2.43.0
  * Jetty 12.1.2

## 1.0.1+2025-10-20

* Changed to use Maven Central Portal API for publish, using Vanniktech's plugin.
* Upgraded to Gradle 8.14.3, latest supporting Java 11.
* Upgraded all dependencies. Spring and ActiveMQ is stuck due to Java 11.
* Started Changelog.

## 1.0.0+2025-05-17

Going for 1.0.0!