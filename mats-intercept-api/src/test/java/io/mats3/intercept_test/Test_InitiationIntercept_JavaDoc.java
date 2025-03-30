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

package io.mats3.intercept_test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsInitiate;
import io.mats3.MatsInitiator.MatsInitiateWrapper;
import io.mats3.MatsInitiator.MessageReference;
import io.mats3.api.intercept.MatsInitiateInterceptor;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptOutgoingMessages;
import io.mats3.api.intercept.MatsInitiateInterceptor.MatsInitiateInterceptUserLambda;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.junit.Rule_Mats;

/**
 * Simple interception test which doesn't really test anything except showing, in the logs, the code sequence described
 * in JavaDoc of {@link MatsInitiateInterceptor}.
 *
 * TODO: De-shit this test, i.e. make it into a test.
 *
 * @author Endre Stølsvik - 2021-01-17 13:35 - http://endre.stolsvik.com
 */
public class Test_InitiationIntercept_JavaDoc {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    public static final CountDownLatch _countDownLatch = new CountDownLatch(2);

    private static final String TERMINATOR = MatsTestHelp.terminator();

    /**
     * This Terminator is set up just to consume all messages produced by this test, so that they do not linger on the
     * MQ - which is a point if we use an external, persistent broker, as MatsTestBroker (within Rule_Mats) can be
     * directed to utilize.
     */
    @BeforeClass
    public static void setupCleanupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, Object.class, DataTO.class, (ctx, state, msg) -> {
            _countDownLatch.countDown();
        });
    }

    @Test
    public void doTest() throws InterruptedException {
        final MatsInitiateInterceptor initiationInterceptor_1 = new MyMatsInitiateInterceptor(1);
        final MatsInitiateInterceptor initiationInterceptor_2 = new MyMatsInitiateInterceptor(2);

        MATS.getMatsFactory().getFactoryConfig().installPlugin(initiationInterceptor_1);
        MATS.getMatsFactory().getFactoryConfig().installPlugin(initiationInterceptor_2);

        MATS.getMatsFactory().getDefaultInitiator().initiateUnchecked(init -> {
            init.traceId(MatsTestHelp.traceId() + "_First")
                    .from(MatsTestHelp.from("test"))
                    .to(TERMINATOR)
                    .send(new DataTO(1, "First message"));
            init.traceId(MatsTestHelp.traceId() + "_Second")
                    .from(MatsTestHelp.from("test"))
                    .to(TERMINATOR)
                    .send(new DataTO(2, "Second message"));
        });

        boolean notified = _countDownLatch.await(30, TimeUnit.SECONDS);
        if (!notified) {
            throw new AssertionError("Didn't get countdown.");
        }
    }

    private static class MyMatsInitiateInterceptor implements MatsInitiateInterceptor,
            MatsInitiateInterceptUserLambda, MatsInitiateInterceptOutgoingMessages {

        private final int _number;

        MyMatsInitiateInterceptor(int number) {
            _number = number;
        }

        @Override
        public void initiateStarted(InitiateStartedContext context) {
            log.info("Stage 'Started', interceptor #" + _number);
        }

        @Override
        public void initiateInterceptUserLambda(InitiateInterceptUserLambdaContext context,
                InitiateLambda initiateLambda, MatsInitiate matsInitiate) {
            log.info("Stage 'Intercept', pre lambda-invoke, interceptor #" + _number);

            // Example: Wrap the MatsInitiate to catch "send(dto)", before invoking the lambda
            MatsInitiateWrapper wrappedMatsInitiate = new MatsInitiateWrapper(matsInitiate) {
                @Override
                public MessageReference send(Object messageDto) {
                    log.info(".. matsInitiate.send(..), pre super.send(..), interceptor #" + _number + ", message:"
                            + messageDto);
                    MessageReference sendMsgRef = super.send(messageDto);
                    log.info(".. matsInitiate.send(..), post super.send(..), interceptor #" + _number + ", message:"
                            + messageDto
                            + ", messageReference:" + sendMsgRef);
                    return sendMsgRef;
                }
            };

            // Invoke the lambda, with the wrapped MatsInitiate
            initiateLambda.initiate(wrappedMatsInitiate);

            // NOTICE: At this point the MDC's "traceId" will be whatever the last initiated message set it to.

            log.info("Stage 'Intercept', post lambda-invoke, interceptor #" + _number);
        }

        @Override
        public void initiateInterceptOutgoingMessages(
                InitiateInterceptOutgoingMessagesContext context) {
            log.info("Stage 'Message', interceptor #" + _number + ", message:"
                    + context.getOutgoingMessages());
        }

        @Override
        public void initiateCompleted(InitiateCompletedContext context) {
            log.info("Stage 'Completed', interceptor #" + _number + ", messages:" + context
                    .getOutgoingMessages());
        }
    }
}
