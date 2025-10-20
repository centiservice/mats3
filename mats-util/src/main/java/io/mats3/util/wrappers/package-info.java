/**
 * Wrappers for {@link jakarta.jms.ConnectionFactory JMS ConnectionFactory} and {@link javax.sql.DataSource JDBC
 * DataSource} - with a special "deferred connection proxy" wrapper which is smart to use as underlying DataSource for
 * the SQL-oriented <code>JmsMatsTransactionManager</code>s, as it elides transactions if the connection is not actually
 * employed within an initiation or stage processing.
 */
package io.mats3.util.wrappers;