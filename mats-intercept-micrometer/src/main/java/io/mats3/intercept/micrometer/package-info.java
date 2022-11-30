/**
 * Mats<sup>3</sup> interceptor adding metrics gathering using Spring's Micrometer solution - consists of a single class
 * {@link io.mats3.intercept.micrometer.MatsMicrometerInterceptor MatsMicrometerInterceptor}, for which the JMS
 * implementation has special support in that if it resides on the classpath, it will autoinstall.
 */
package io.mats3.intercept.micrometer;