package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;

import io.mats3.MatsFactory;
import io.mats3.test.junit.Rule_Mats;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.MatsTestLatch.Result;

/**
 * Tests the Publish/Subscribe functionality: Sets up <i>two</i> instances of a SubscriptionTerminator to the same
 * endpointId, and then an initiator publishes a message to that endpointId. Due to the Pub/Sub nature, both of the
 * SubscriptionTerminators will get the message.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 *                     [Initiator]  - init publish
 * [SubscriptionTerminator_1][SubscriptionTerminator_2] <i>(both receives the message)</i>
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimplePublishSubscribe {
    private static final Logger log = MatsTestHelp.getClassLogger();

    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    private static final String TERMINATOR = MatsTestHelp.terminator();

    private static final MatsTestLatch matsTestLatch = new MatsTestLatch();
    private static final MatsTestLatch matsTestLatch2 = new MatsTestLatch();

    @BeforeClass
    public static void setupTerminator() {
        /*
         * :: Register TWO subscriptionTerminators to the same endpoint, to ensure that such a terminator works as
         * intended.
         *
         * NOTE: Due to a MatsFactory denying two registered endpoints with the same EndpointId, we need to trick this a
         * bit two make it happen: Create two MatsFactories with the same JMS ConnectionFactory.
         */

        // Creating TWO extra MatsFactories. These will be lifecycled by the Rule_Mats.
        MatsFactory firstMatsFactory = MATS.createMatsFactory();
        MatsFactory secondMatsFactory = MATS.createMatsFactory();

        firstMatsFactory.subscriptionTerminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("SUBSCRIPTION TERMINATOR 1 MatsTrace:\n" + context.toString());
                    matsTestLatch.resolve(sto, dto);
                });
        secondMatsFactory.subscriptionTerminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("SUBSCRIPTION TERMINATOR 2 MatsTrace:\n" + context.toString());
                    matsTestLatch2.resolve(sto, dto);
                });

        // Nap for a small while, due to the nature of Pub/Sub: If the listeners are not up when the message is sent,
        // then they will not get the message.
        MatsTestHelp.takeNap(100);
    }

    @Test
    public void doTest() {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);

        // Initiate on the standard MatsFactory of Rule_Mats.
        MATS.getMatsInitiator().initiateUnchecked(
                (msg) -> msg.traceId(MatsTestHelp.traceId())
                        .from(MatsTestHelp.from("test"))
                        .to(TERMINATOR)
                        .publish(dto, sto));

        // Wait synchronously for both terminators to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(dto, result.getData());
        Assert.assertEquals(sto, result.getState());

        Result<StateTO, DataTO> result2 = matsTestLatch2.waitForResult();
        Assert.assertEquals(dto, result2.getData());
        Assert.assertEquals(sto, result2.getState());
    }
}
