/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.api_test.database;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsFactory.ContextLocal;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch.Result;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.junit.Rule_Mats;

/**
 * Simple test that looks quite a bit like <code>Test_SimplestEndpointRequest</code>, only the Initiator now populate a
 * table with some data, which the Mats service retrieves and replies with.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - inserts into database - request
 *     [Service] - fetches from database - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimpleDbTest {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.createWithDb();

    private static final String ENDPOINT = MatsTestHelp.endpoint("Service");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> {
                    Optional<Connection> connectionAttribute = context.getAttribute(Connection.class);
                    if (!connectionAttribute.isPresent()) {
                        throw new AssertionError("Missing context.getAttribute(Connection.class)");
                    }
                    Connection sqlConnection = connectionAttribute.get();

                    Optional<Connection> contextLocalConnectionAttribute = ContextLocal.getAttribute(Connection.class);
                    if (!contextLocalConnectionAttribute.isPresent()) {
                        throw new AssertionError("Missing ContextLocal.getAttribute(Connection.class)");
                    }

                    // These should be the same.
                    Assert.assertSame(sqlConnection, contextLocalConnectionAttribute.get());

                    // :: Get the data from the SQL table
                    List<String> data = TestH2DataSource.getDataFromDataTable(sqlConnection);
                    Assert.assertEquals(1, data.size());

                    return new DataTO(dto.number * 2, dto.string + ":FromService:" + data.get(0));
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void checkThatDataSourceWorks() {
        MATS.getDataSource().createDataTable();

        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String randomData = UUID.randomUUID().toString();

        // :: Insert into 'datatable' and send the request to ENDPOINT.
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    // :: Assert that SQL Connection is in places where it should be
                    Optional<Connection> connectionAttribute = init.getAttribute(Connection.class);
                    if (!connectionAttribute.isPresent()) {
                        throw new AssertionError("Missing matsInitiate.getAttribute(Connection.class)");
                    }
                    Connection sqlConnection = connectionAttribute.get();

                    Optional<Connection> contextLocalConnectionAttribute = ContextLocal.getAttribute(Connection.class);
                    if (!contextLocalConnectionAttribute.isPresent()) {
                        throw new AssertionError("Missing ContextLocal.getAttribute(Connection.class)");
                    }

                    // These should be the same.
                    Assert.assertSame(sqlConnection, contextLocalConnectionAttribute.get());

                    // :: Stick some data in table.
                    TestH2DataSource.insertDataIntoDataTable(sqlConnection, randomData);

                    // :: Send the request
                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("checkThatDataSourceWorks"))
                            .to(ENDPOINT)
                            .replyTo(TERMINATOR, sto)
                            .request(dto);
                });

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService:" + randomData), result.getData());
    }
}
