package io.mats3.lib_test.database;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.lib_test.DataTO;
import io.mats3.lib_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that if a Mats stage throws RTE, any SQL INSERT shall be rolled back.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]
 *     [Service]  - inserts into database, then throws RTE (but also have test asserting that data inserts if we do NOT throw!)
 * [Terminator]  - checks that the msg is DLQed and data is not present (or test that it IS inserted if we do NOT throw!)
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_ExceptionInServiceRollbacksDb {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.createWithDb();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Insert the data into the datatable
                    TestH2DataSource.insertDataIntoDataTable(context.getAttribute(Connection.class).get(),
                            "FromService:" + dto.string);
                    // ?: Are we requested to throw RTE?
                    if (dto.number == 1) {
                        // -> Yes, so do!
                        throw new RuntimeException("Should send message to DLQ after retries.");
                    }
                    return new DataTO(dto.number * 2, dto.string + ":FromService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> MATS.getMatsTestLatch().resolve(sto, dto));
    }

    @Before
    public void cleanDatabaseAndCreateTable() {
        MATS.getDataSource().cleanDatabase(true);
    }

    /**
     * Tests the infrastructure for checking that SERVICE's SQL inserts are rolled back upon RTE, by sending a message
     * using the same path (but with no RTE) which we assert that we DO receive, and where the data is inserted!
     */
    @Test
    public void checkTestInfrastructre() {
        String randomData = UUID.randomUUID().toString();

        // Request that the SERVICE do NOT throw, providing the randomData to insert into the 'datatable'
        DataTO dto = new DataTO(0, randomData);
        StateTO sto = new StateTO(420, 420.024);

        // :: Send the request to SERVICE.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    // :: Send the request
                    msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("checkTestInfrastructre"))
                            .to(SERVICE)
                            .replyTo(TERMINATOR, sto)
                            .request(dto);
                });

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService"), result.getData());

        // Assert that the data inserted in SERVICE is actually in place.
        List<String> dataFromDataTable = MATS.getDataSource().getDataFromDataTable();
        Assert.assertEquals(1, dataFromDataTable.size());
        Assert.assertEquals("FromService:" + randomData, dataFromDataTable.get(0));
    }

    /**
     * Tests that an SQL INSERT in the SERVICE will rolled back if SERVICE throws a RTE.
     */
    @Test
    public void exceptionInServiceShouldRollbackDb() {
        String randomData = UUID.randomUUID().toString();

        // Request that the SERVICE throws, providing the randomData to insert into the 'datatable'
        DataTO dto = new DataTO(1, randomData);
        StateTO sto = new StateTO(420, 420.024);

        // :: Send the request to SERVICE.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> {
                    // :: Send the request
                    msg.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("exceptionInServiceShouldRollbackDb"))
                            .to(SERVICE)
                            .replyTo(TERMINATOR, sto)
                            .request(dto);
                });

        // Wait for the DLQ
        MatsMessageRepresentation dlqMessage = MATS.getMatsTestBrokerInterface().getDlqMessage(SERVICE);

        Assert.assertEquals(SERVICE, dlqMessage.getTo());

        // Assert that the data inserted in SERVICE is NOT inserted!
        Assert.assertEquals("Should NOT have found any data in SQL Table 'datatable'!",
                0, MATS.getDataSource().getDataFromDataTable().size());
    }
}
