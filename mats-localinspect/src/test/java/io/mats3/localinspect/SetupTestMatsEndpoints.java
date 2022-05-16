package io.mats3.localinspect;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.Assert;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;

public class SetupTestMatsEndpoints {

    void setupMatsTestEndpoints(MatsFactory matsFactory, String baseService, int baseConcurrency) {
        setupMainMultiStagedService(matsFactory, baseService);
        setupMidMultiStagedService(matsFactory, baseService, baseConcurrency);
        setupLeafService(matsFactory, baseService, baseConcurrency);

        setupTerminator(matsFactory, baseService);
        setupSubscriptionTerminator(matsFactory, baseService);
    }

    final static String SERVICE = ".service";
    final static String SERVICE_MID = ".service.Mid";
    final static String SERVICE_LEAF = ".service.Leaf";
    final static String TERMINATOR = ".terminator";
    final static String SUBSCRIPTION_TERMINATOR = ".subscriptionTerminator";

    public void setupLeafService(MatsFactory matsFactory, String baseService, int baseConcurrency) {
        MatsEndpoint<DataTO, Void> single = matsFactory.single(baseService + SERVICE_LEAF, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
                    return new DataTO(dto.number * dto.multiplier, dto.string + ":FromLeafService");
                });
        single.getEndpointConfig().setConcurrency(baseConcurrency * 6);
    }

    public void setupMidMultiStagedService(MatsFactory matsFactory, String baseService, int baseConcurrency) {
        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged(baseService + SERVICE_MID, DataTO.class,
                StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            // Store the multiplier in state, so that we can use it when replying in the next (last) stage.
            sto.number1 = dto.multiplier;
            // Add an important number to state..!
            sto.number2 = Math.PI;
            context.request(baseService + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall", 2));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.PI, sto.number2, 0d);
            // Change the important number in state..!
            sto.number2 = Math.E;
            context.next(new DataTO(dto.number, dto.string + ":NextCall"));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.E, sto.number2, 0d);
            // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
            return new DataTO(dto.number * sto.number1, dto.string + ":FromMidService");
        });

        ep.getEndpointConfig().setConcurrency(baseConcurrency * 4);
    }

    public void setupMainMultiStagedService(MatsFactory matsFactory, String baseService) {
        MatsEndpoint<DataTO, StateTO> ep = matsFactory.staged(baseService + SERVICE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            context.request(baseService + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall1", 3));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);
            sto.number1 = 1;
            sto.number2 = 2;
            // ?: Should we send null, or DTO?
            if (ThreadLocalRandom.current().nextFloat() > 0.5) {
                // -> Send null, for testing of issue #53, "LocalHtmlInspectForMatsFactory crashes with NPE if outgoing
                // messages are null."
                context.next(null);
            }
            else {
                context.next(dto);
            }
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            // ?: Is the incoming message null (see comments in above stage)?
            if (dto == null) {
                // -> Yes, so replace it with something non-null
                dto = new DataTO(1, "replacing null message", 5);
            }
            Assert.assertEquals(new StateTO(1, 2), sto);
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;
            context.request(baseService + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall2", 7));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE, Math.E * 2), sto);
            sto.number1 = Integer.MIN_VALUE / 2;
            sto.number2 = Math.E / 2;
            // RANDOM: NEXT vs. REQUEST
            if (ThreadLocalRandom.current().nextFloat() > 0.5) {
                context.next(new DataTO(dto.number, dto.string + ":LeafCall1", 4));
            }
            else {
                context.request(baseService + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall1", 4));
            }
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 2, Math.E / 2), sto);
            sto.number1 = Integer.MIN_VALUE / 4;
            sto.number2 = Math.E / 4;
            context.request(baseService + SERVICE_LEAF, new DataTO(dto.number, dto.string + ":LeafCall2", 6));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 4, Math.E / 4), sto);
            sto.number1 = Integer.MAX_VALUE / 2;
            sto.number2 = Math.PI / 2;
            // RANDOM: REPLY vs. REQUEST
            if (ThreadLocalRandom.current().nextFloat() > 0.5) {
                context.reply(new DataTO(dto.number, dto.string + ":EarlyReturn_FromMasterService", 4));
            }
            else {
                context.request(baseService + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall3", 8));
            }
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 2, Math.PI / 2), sto);
            sto.number1 = Integer.MAX_VALUE / 4;
            sto.number2 = Math.PI / 4;
            context.request(baseService + SERVICE_MID, new DataTO(dto.number, dto.string + ":MidCall4", 9));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 4, Math.PI / 4), sto);
            return new DataTO(dto.number * 5, dto.string + ":FromMasterService");
        });
    }

    public void setupTerminator(MatsFactory matsFactory, String baseService) {
        matsFactory.terminator(baseService + TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                });
    }

    public void setupSubscriptionTerminator(MatsFactory matsFactory, String baseService) {
        matsFactory.subscriptionTerminator(baseService + SUBSCRIPTION_TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                });
    }

}
