package io.mats3.api_test.failure;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jms.Connection;
import javax.jms.JMSException;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyInitiator;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling.PoolingKeyStageProcessor;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_PoolingSerial;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;
import io.mats3.util.wrappers.ConnectionFactoryWrapper;

/**
 * @author Endre Stølsvik 2023-12-05 19:05 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_HandlingConnectionFactoryFails {
    private static final Logger log = LoggerFactory.getLogger(Test_HandlingConnectionFactoryFails.class);

    @Test
    public void test_FACTORY_FACTORY_serialpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.FACTORY, 1, 1, true);
    }

    @Test
    public void test_INITIATOR_STAGE_PROCESSOR_serialpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.INITIATOR, PoolingKeyStageProcessor.STAGE_PROCESSOR, 6, 26, true);
    }

    @Test
    public void test_FACTORY_STAGE_serialpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.STAGE, 6, 6, true);
    }

    @Test
    public void test_FACTORY_ENDPOINT_serialpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.ENDPOINT, 4, 4, true);
    }

    @Test
    public void test_FACTORY_FACTORY_oldpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.FACTORY, 1, 1, false);
    }

    @Test
    public void test_INITIATOR_STAGE_PROCESSOR_oldpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.INITIATOR, PoolingKeyStageProcessor.STAGE_PROCESSOR, 6, 26, false);
    }

    @Test
    public void test_FACTORY_STAGE_oldpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.STAGE, 6, 6, false);
    }

    @Test
    public void test_FACTORY_ENDPOINT_oldpool() throws InterruptedException, ExecutionException {
        doTest(PoolingKeyInitiator.FACTORY, PoolingKeyStageProcessor.ENDPOINT, 4, 4, false);
    }

    public void doTest(PoolingKeyInitiator poolingKeyInitiator, PoolingKeyStageProcessor poolingKeyStageProcessor,
            int minimumExpectedConnections, int maximumExpectedConnections, boolean usePoolingSerial)
            throws InterruptedException, ExecutionException {

        MatsTestBroker matsTestBroker = MatsTestBroker.create();

        int concurrency = 3;

        int shouldFailNumberOfTimes = 5;

        AtomicInteger fails = new AtomicInteger();
        AtomicInteger returned = new AtomicInteger();

        ConnectionFactoryWrapper fakeConFactory = new ConnectionFactoryWrapper(matsTestBroker.getConnectionFactory()) {
            @Override
            public Connection createConnection() throws JMSException {
                if (fails.get() < shouldFailNumberOfTimes) {
                    fails.incrementAndGet();
                    log.info("#TEST# Throwing when asking for JMS Connection.");
                    throw new JMSException("FAKE! TEST! This is not really a JMSException!");
                }

                returned.incrementAndGet();
                log.info("#TEST# Returning JMS Connection.");
                return super.createConnection();
            }
        };

        JmsMatsJmsSessionHandler sessionPooler = usePoolingSerial
                ? JmsMatsJmsSessionHandler_PoolingSerial.create(fakeConFactory, poolingKeyInitiator,
                        poolingKeyStageProcessor)
                : JmsMatsJmsSessionHandler_Pooling.create(fakeConFactory, poolingKeyInitiator,
                        poolingKeyStageProcessor);

        MatsFactory matsFactory = JmsMatsFactory.createMatsFactory_JmsOnlyTransactions(
                "ConnectionFactoryFails", "-test-", sessionPooler, MatsSerializerJson.create());
        matsFactory.getFactoryConfig().setConcurrency(concurrency);

        matsFactory.single("Single", DataTO.class, DataTO.class,
                (context, dto) -> new DataTO(dto.number * 2, dto.string + ":FromSingle"));

        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged("Endpoint", DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, state, incomingDto) -> {
            context.request("Single", new DataTO(incomingDto.number * 2, incomingDto.string + ":FromStage1"));
        });
        ep.stage(DataTO.class, (context, state, incomingDto) -> {
            context.request("Single", new DataTO(incomingDto.number * 2, incomingDto.string + ":FromStage2"));
        });
        ep.lastStage(DataTO.class, (context, state, incomingDto) -> new DataTO(incomingDto.number * 2,
                incomingDto.string + ":TheAnswer"));
        ep.finishSetup();

        MatsFuturizer matsFuturizer = MatsFuturizer.createMatsFuturizer(matsFactory);
        CompletableFuture<Reply<DataTO>> future = matsFuturizer.futurizeNonessential("traceId", "test", "Endpoint",
                DataTO.class, new DataTO(5, "TheRequest"));
        Reply<DataTO> reply = future.get();
        Assert.assertEquals("TheRequest:FromStage1:FromSingle:FromStage2:FromSingle:TheAnswer", reply.get().string);

        log.info("#TEST# Fails: " + fails.get());
        log.info("#TEST# Returned: " + returned.get());

        matsFuturizer.close();
        matsFactory.close();
        matsTestBroker.close();

        Assert.assertTrue("Should have had at least '" + minimumExpectedConnections + "' connections, but had '"
                + returned.get() + "'.", returned.get() >= minimumExpectedConnections);

        Assert.assertTrue("Should have had at most '" + maximumExpectedConnections + "' connections, but had '"
                + returned.get() + "'.", returned.get() <= maximumExpectedConnections);
    }
}
