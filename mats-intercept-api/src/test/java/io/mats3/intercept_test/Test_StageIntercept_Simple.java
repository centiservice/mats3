package io.mats3.intercept_test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsEndpoint.ProcessContextWrapper;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.api.intercept.MatsStageInterceptor;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptUserLambda;
import io.mats3.api.intercept.MatsStageInterceptor.MatsStageInterceptOutgoingMessages;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Simple test adding a MatsStageInterceptor, just for visual inspection in logs.
 *
 * TODO: De-shit this test, i.e. make it into a test.
 */
public class Test_StageIntercept_Simple {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String ENDPOINT = MatsTestHelp.endpoint();
    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final CountDownLatch _latch = new CountDownLatch(2);

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(ENDPOINT, DataTO.class, DataTO.class,
                (context, dto) -> new DataTO(dto.number * 2, dto.string + ":FromService"));
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
                    _latch.countDown();
                });

    }

    @Test
    public void doTest() throws InterruptedException {
        final MatsStageInterceptor stageInterceptor_1 = new MyMatsStageInterceptor(1);
        final MatsStageInterceptor stageInterceptor_2 = new MyMatsStageInterceptor(2);

        MATS.getMatsFactory().getFactoryConfig().installPlugin(stageInterceptor_1);
        MATS.getMatsFactory().getFactoryConfig().installPlugin(stageInterceptor_2);

        MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(MatsTestHelp.traceId() + "_First")
                    .from(MatsTestHelp.from("test"))
                    .to(ENDPOINT)
                    .replyTo(TERMINATOR, new DataTO(0, "null"))
                    .request(new DataTO(1, "First message"));
            init.traceId(MatsTestHelp.traceId() + "_Second")
                    .from(MatsTestHelp.from("test"))
                    .to(ENDPOINT)
                    .replyTo(TERMINATOR, new DataTO(0, "null"))
                    .request(new DataTO(2, "Second message"));
        });

        _latch.await(5, TimeUnit.SECONDS);
    }

    private static class MyMatsStageInterceptor implements MatsStageInterceptor,
            MatsStageInterceptUserLambda, MatsStageInterceptOutgoingMessages {

        private final int _number;

        MyMatsStageInterceptor(int number) {
            _number = number;
        }

        @Override
        public void stagePreprocessAndDeserializeError(
                StagePreprocessAndDeserializeErrorContext context) {

        }

        @Override
        public void stageReceived(StageReceivedContext context) {
            log.info("Stage 'Started', interceptor #" + _number);
        }

        @Override
        public void stageInterceptUserLambda(StageInterceptUserLambdaContext context,
                ProcessLambda<Object, Object, Object> processLambda,
                ProcessContext<Object> ctx, Object state, Object msg) throws MatsRefuseMessageException {
            log.info("Stage 'Intercept', pre lambda-invoke, interceptor #" + _number);

            // Example: Wrap the ProcessContext to catch "reply(dto)", before invoking the lambda
            ProcessContextWrapper<Object> wrappedProcessContext = new ProcessContextWrapper<Object>(ctx) {
                @Override
                public MessageReference reply(Object replyDto) {
                    log.info(".. processContext.reply(..), pre super.reply(..), interceptor #" + _number + ", message:"
                            + replyDto);
                    MessageReference sendMsgRef = super.reply(replyDto);
                    log.info(".. processContext.reply(..), post super.reply(..), interceptor #" + _number + ", message:"
                            + replyDto + ", messageReference:" + sendMsgRef);
                    return sendMsgRef;
                }
            };

            processLambda.process(wrappedProcessContext, state, msg);

            log.info("Stage 'Intercept', post lambda-invoke, interceptor #" + _number);
        }

        @Override
        public void stageInterceptOutgoingMessages(
                StageInterceptOutgoingMessageContext context) {
            log.info("Stage 'Message', interceptor #" + _number + ", messages:" + context
                    .getOutgoingMessages());
        }

        @Override
        public void stageCompleted(StageCompletedContext context) {
            log.info("Stage 'Completed', interceptor #" + _number + ", messages:" + context
                    .getOutgoingMessages());
        }
    }
}
