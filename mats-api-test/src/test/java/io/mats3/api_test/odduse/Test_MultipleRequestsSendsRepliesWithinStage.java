package io.mats3.api_test.odduse;

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;

/**
 * Tests that it is possible to do multiple context.request + context.send.
 *
 * @author Endre Stølsvik 2019-05-29 18:05 - http://stolsvik.com/, endre@stolsvik.com
 * @author Endre Stølsvik 2022-11-02 23:27 amended to not test request+next, as no longer allowed.
 */
public class Test_MultipleRequestsSendsRepliesWithinStage {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String SERVICE = MatsTestHelp.service();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final AtomicInteger _serviceInvocationCount = new AtomicInteger(0);
    private static final AtomicInteger _serviceStage1InvocationCount = new AtomicInteger(0);
    private static final AtomicInteger _serviceStage2InvocationCount = new AtomicInteger(0);

    private static final AtomicInteger _leafInvocationCount = new AtomicInteger(0);

    private static final AtomicInteger _terminatorInvocationCount = new AtomicInteger(0);

    private static final Map<String, String> _answers = new ConcurrentHashMap<>();

    @BeforeClass
    public static void setupThreeStageService() {
        MatsEndpoint<DataTO, StateTO> ep = MATS.getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // Should only be invoked once.
            _serviceInvocationCount.incrementAndGet();
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = 10;
            sto.number2 = Math.PI;
            // :: Perform /two/ requests to Leaf service (thus replies twice)
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":Request#1"));
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":Request#2"));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // This should now be invoked twice.
            Assert.assertEquals(new StateTO(10, Math.PI), sto);
            _serviceStage1InvocationCount.incrementAndGet();
            sto.number1 = -10;
            sto.number2 = Math.E;
            // :: Send two messages directly to TERMINATOR
            context.initiate((msg) -> msg.to(TERMINATOR)
                    .send(new DataTO(dto.number, dto.string + ":Send#1")));
            context.initiate((msg) -> msg.to(TERMINATOR)
                    .send(new DataTO(dto.number, dto.string + ":Send#2")));
            // :: Pass on to next stage (thus 2 times to next stage, since this stage is replied 2x).
            context.next(new DataTO(dto.number, dto.string + ":Next"));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // This should now be invoked 2 times
            _serviceStage2InvocationCount.incrementAndGet();
            Assert.assertEquals(new StateTO(-10, Math.E), sto);
            context.reply(new DataTO(dto.number, dto.string + ":Reply"));
        });
        ep.finishSetup();
    }

    @BeforeClass
    public static void setupLeafService() {
        MATS.getMatsFactory().single(SERVICE + ".Leaf", DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Should be invoked twice.
                    _leafInvocationCount.incrementAndGet();
                    return new DataTO(dto.number * 2, dto.string + ":FromLeafService");
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    // Should be invoked 6 times, 2 for the staged endpoint + 4 from the sends in middle stage.
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    log.debug("TERMINATOR Incoming Message: " + dto);
                    // Store the answer.
                    _answers.put(dto.string, "");
                    int count = _terminatorInvocationCount.incrementAndGet();
                    if (count == 6) {
                        MATS.getMatsTestLatch().resolve(sto, dto);
                    }
                });

    }

    @Before
    public void resetCountersAndMap() {
        _serviceInvocationCount.set(0);
        _serviceStage1InvocationCount.set(0);
        _serviceStage2InvocationCount.set(0);
        _leafInvocationCount.set(0);
        _terminatorInvocationCount.set(0);

        _answers.clear();
    }

    @Test
    public void doTestFull() {
        // 1780 bytes @ Terminator
        doTest(KeepTrace.FULL);
    }

    @Test
    public void doTestCompact() {
        // 1331 bytes @ Terminator
        doTest(KeepTrace.COMPACT);
    }

    @Test
    public void doTestMinimal() {
        // 605 bytes @ Terminator
        doTest(KeepTrace.MINIMAL);
    }

    private void doTest(KeepTrace keepTrace) {
        DataTO dto = new DataTO(1, "Initiator");
        StateTO sto = new StateTO(20, -20);
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .keepTrace(keepTrace)
                        .from(MatsTestHelp.from("test_keepTrace_" + keepTrace))
                        .to(SERVICE)
                        .replyTo(TERMINATOR, sto)
                        .request(dto));

        // Wait synchronously for terminator to finish (all 8 answers).
        MATS.getMatsTestLatch().waitForResult();

        // ----- All finished

        // :: Assert invocation counts
        Assert.assertEquals(1, _serviceInvocationCount.get());
        Assert.assertEquals(2, _leafInvocationCount.get());
        Assert.assertEquals(2, _serviceStage1InvocationCount.get());
        Assert.assertEquals(2, _serviceStage2InvocationCount.get());
        Assert.assertEquals(6, _terminatorInvocationCount.get());

        // :: These are the 6 replies that the Terminator should see.
        SortedSet<String> expected = new TreeSet<>();
        expected.add("Initiator:Request#1:FromLeafService:Next:Reply");
        expected.add("Initiator:Request#1:FromLeafService:Send#1");
        expected.add("Initiator:Request#1:FromLeafService:Send#2");

        expected.add("Initiator:Request#2:FromLeafService:Next:Reply");
        expected.add("Initiator:Request#2:FromLeafService:Send#1");
        expected.add("Initiator:Request#2:FromLeafService:Send#2");

        Assert.assertEquals(expected, new TreeSet<>(_answers.keySet()));
    }
}
