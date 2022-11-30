/**
 * Contains a set of helpful tools for testing: {@link io.mats3.test.MatsTestBrokerInterface MatsTestBrokerInterface} to
 * get DLQ'ed messages, {@link io.mats3.test.MatsTestHelp MatsTestHelp} for creation of relevant ids,
 * {@link io.mats3.test.MatsTestLatch MatsTestLatch} for a simple tool to synchronize between the test method and the
 * async Mats endpoints, and {@link io.mats3.test.TestH2DataSource TestH2DataSource} which is a small wrapper around H2
 * to create a database with a test table - mainly used by the API Tests to verify SQL transactions.
 */
package io.mats3.test;