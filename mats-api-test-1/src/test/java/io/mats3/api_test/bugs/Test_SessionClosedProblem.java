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

package io.mats3.api_test.bugs;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.After;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.test.junit.Rule_MatsEndpoint;
import io.mats3.util.MatsFuturizer;

/**
 * Test that exercises a specific situation where very fast tests, employing {@link Rule_Mats} and thus
 * {@link MatsTestBroker} which spins up an <b>in-vm ActiveMQ Broker</b>, could sometimes spuriously fail when a
 * {@link MatsFuturizer} tried sending a request, getting a "Session is closed" exception.
 * <p/>
 * This test should be "run until failed", but since the problem is now fixed (see below), it should not fail.
 * <ol>
 * <li>It relies on method order, as the problem is rather specific.</li>
 * <li>The {@link JmsMatsJmsSessionHandler_Pooling} must be set up using multiple pools, that is, anything other than
 * FACTORY,FACTORY - specifically the MatsFuturizer initiator must get its own pool, while the Endpoint have
 * another.</li>
 * <li>{@link #a_Perform_Futurizer_Request()} performs a request using the {@link MatsFuturizer}, which uses a specific
 * {@link MatsInitiator}, and thus gets its own Session Pool with its own JMS Connection.</li>
 * <li>When this test is finished, it cleans up the Pool that had the sessions for the StageProcessors since these
 * <i>close</i> the Session when exiting..</li>
 * <li>.. while the MatsInitiator just <i>releases</i> its Session, thus the Pool remains open with one <i>available</i>
 * Session.</li>
 * <li>Now a heap of no-op tests are performed - the {@link #b_Only_Set_Up_Endpoint1()} methods. Each of them do nothing
 * - they are empty - but the {@link Rule_MatsEndpoint} will be set up and thus a new pool (and JMS Connection) is
 * created to hold the Sessions for its StageProcessors.</li>
 * <li>Due to the speed the test execute, the Mats Endpoint will be closed before they have gotten around to fully start
 * up.</li>
 * <li>However, there are multiple StageProcessors per such Endpoint. The first StageProcessor gets a Session, but will
 * then immediately release it again since the Endpoint is already closed - and thus the pool is empty and want to close
 * the JMS Connection.</li>
 * <li>But at the same time the next StageProcessor also wants a Session (read more below).</li>
 * <li>When all the StageProcessors have been through this, the pool is empty and is disposed.</li>
 * <li>All the while these no-op tests are run, the single available Session from the MatsInitiator pool hangs out just
 * sitting there in its own Pool (at least that's what's supposed to happen!).</li>
 * <li>Finally, the {@link #c_Perform_Futurizer_Request()} method is run, which is identical to the first request. This
 * also sets up the MatsEndpoint, creating a new pool - <i>but for the MatsFuturizer it can reuse the MatsInitiator
 * pool, since it sits there available.</i></li>
 * <li>The bug used to be that when it got hold of this Session, it was quite often closed!</li>
 * <li>Since there is only a quite small chance that the problem occurs during each of no-op test methods, there are 9
 * of them between the "a" and "c" method, which seems to be enough to compound the risk! However, as mentioned, you
 * must "run until fail" to be sure. After the problem has occurred, the closed session situation persist until the "c"
 * method is executed, and thus the test fails.</li>
 * </ol>
 * <p>
 * The current reasoning is as follows: There was a race in the Pooler: The problem with the concurrent opening and
 * closing of Sessions results in the JMS Connection being closed by one of the Session close operations (which sees
 * that the pool is empty of sessions), right when a new Session is requested. ActiveMQ has a counter of how many JMS
 * Connections exists between the client and the in-vm server. When this goes to zero, it tears down the entire
 * "VMTransport". What happened was that ActiveMQ at some point found out, during the fast cycling of the no-op tests,
 * that there was <i>zero</i> JMS Connections left - even though there clearly was this one Session (from one JMS
 * Connection) hanging out in the MatsInitiator pool. The connection mechanism in Active thus disposes the entire VM
 * Connection transport system - which we can se in a log line stating
 * <code>"o.a.a.t.vm.VMTransportFactory - Shutting down VM connectors for broker: MatsTestActiveMQ_xxxxx"</code>. This
 * then takes the MatsInitiator pool's JMS Connection with it - closing it from the server side while we still have it
 * in the client pool - and thus the pooled Session is closed - resulting in what we experienced where the MatsFuturizer
 * fails with a "Session is closed" exception when it depools this now-closed Session and tries to use it.
 * </p>
 * <p>
 * There was was a race problem in the Pooler - and this is now fixed by introducing a concept of "interest": When
 * getting hold of a Pool, before even checking for whether a Session is available or we need to create it, a counter of
 * "interest" is increased. When we get-or-create the SessionHolder, this counter is decreased again. On the other side
 * of the equation, where the closing of SessionHolders is handled so that the pool and its JMS Connection can be closed
 * and disposed, it now not only checks whether it is empty of sessions, but also whether there is any "interest" in the
 * pool. If there is, it will not close the JMS Connection.
 * </p>
 * <p>
 * There is quite obviously some missing handling of this specific race in the ActiveMQ code - the counting of
 * connections ends up being zero when there obviously is still 1 JMS Connection left. I assume it must be thrown off by
 * this fast cycling, and probably because the Pooler did something not quite semantically correct. However, ActiveMQ
 * should clearly not allow the VMTransport to be closed when there is actually is any JMS Connections left! It even
 * specifically <i>closes</i> this JMS Connection as a cleanup procedure, implying that the data structures actually
 * know about the remaining JMS Connection! It might be that in certain conditions the Pooler tried, and managed, to
 * register a new Session on a JMS Connection that was <i>on its way</i> to be closed, or something like this. I have
 * not managed to hunt down the specific ordering of concurrent events that trigger this situation, neither by manual
 * reasoning in the code, nor by making a specific test using only Connections and Session opening and closing using
 * multiple threads. But I now believe that the specific test scenario (which is a distilled variant from actual tests
 * in projects) triggers a race condition present in the Pooler - which then again somehow manages to trigger the
 * ActiveMQ problem - and this race condition in the Pooler is now fixed.
 * </p>
 * <p>
 * <b>If you want to experience the problem, you can remove the fix in the Pooler</b> - which is to comment out the one
 * test for <code>_interest == 0</code> in the 'if' commented by "?: Is the ConnectionWithSessionPool now empty?", in
 * the <code>disposePoolIfEmpty(SessionHolder)</code> method. Then run this test ("run until fail"). It should fail at
 * the {@link #c_Perform_Futurizer_Request()} method - and if you search the log for "Shutting down VM connectors for
 * broker", you should see that it is this that triggers the problem (you'll also find an exception
 * <code>"TransportDisposedIOException: peer (vm://MatsTestActiveMQ_p87NUR#5) stopped"</code> when the JMS Connection is
 * erroneously closed - the client does not expect this, and must now close the open Session).
 * </p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class Test_SessionClosedProblem {

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @After
    public void shutdownEndpoints() {
        MATS.cleanMatsFactories();
    }

    private static final String ENDPOINT_ID = MatsTestHelp.endpoint();

    @Rule
    public Rule_MatsEndpoint<String, String> _confirmCashOk = Rule_MatsEndpoint
            .create(ENDPOINT_ID, String.class, String.class)
            .setProcessLambda((context, incomingMessage) -> "")
            .setMatsFactory(MATS.getMatsFactory());

    private CompletableFuture<String> doConfirmCashRequest(
            String request, String traceId) {
        return MATS.getMatsFuturizer().futurizeNonessential(
                traceId,
                this.getClass().getSimpleName(),
                ENDPOINT_ID,
                String.class,
                request)
                .thenApply(reply -> reply.get());
    }

    @Test
    public void a_Perform_Futurizer_Request() throws ExecutionException, InterruptedException {
        doConfirmCashRequest("request", "TraceId|AMOUNT_MISMATCH").get();
    }

    @Test
    public void b_Only_Set_Up_Endpoint1() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint2() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint3() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint4() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint5() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint6() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint7() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint8() throws MatsRefuseMessageException {
    }

    @Test
    public void b_Only_Set_Up_Endpoint9() throws MatsRefuseMessageException {
    }

    @Test
    public void c_Perform_Futurizer_Request() throws ExecutionException, InterruptedException {
        doConfirmCashRequest("request", "TraceId|AMOUNT_MISMATCH").get();
    }
}