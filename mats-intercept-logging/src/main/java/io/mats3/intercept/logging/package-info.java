/**
 * Mats<sup>3</sup> interceptor for structured logging over SLF4J, adding several data points using the SLF4J MDC for
 * each initiation, message receive and message send - consists of a single class
 * {@link io.mats3.intercept.logging.MatsMetricsLoggingInterceptor MatsMetricsLoggingInterceptor}, for which the JMS
 * implementation has special support in that if it resides on the classpath, it will autoinstall.
 */
package io.mats3.intercept.logging;