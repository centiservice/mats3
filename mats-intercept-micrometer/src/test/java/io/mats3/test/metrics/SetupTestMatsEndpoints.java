package io.mats3.test.metrics;

import java.util.Objects;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.slf4j.Logger;

import io.mats3.MatsEndpoint;
import io.mats3.MatsFactory;
import io.mats3.test.MatsTestHelp;

public class SetupTestMatsEndpoints {
    private static final Logger log = MatsTestHelp.getClassLogger();

    private static MatsFactory __matsFactory;

    static void setupMatsAndMatsSocketEndpoints(MatsFactory matsFactory) {
        __matsFactory = matsFactory;
        setupLeafService();
        setupTerminator();
        setupMasterMultiStagedService();
        setupMidMultiStagedService();
    }

    public static MatsFactory getMatsFactory() {
        return __matsFactory;
    }

    static final String SERVICE = MatsTestHelp.service();
    static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupLeafService() {
        getMatsFactory().single(SERVICE + ".Leaf", DataTO.class, DataTO.class,
                (context, dto) -> {
                    if (log.isDebugEnabled()) log.debug("Incoming message for LeafService: DTO:[" + dto
                            + "], context:\n" + context);
                    // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
                    return new DataTO(dto.number * dto.multiplier, dto.string + ":FromLeafService");
                });
    }

    @BeforeClass
    public static void setupMidMultiStagedService() {
        MatsEndpoint<DataTO, StateTO> ep = getMatsFactory().staged(SERVICE + ".Mid", DataTO.class,
                StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for MidService: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(0, 0), sto);
            // Store the multiplier in state, so that we can use it when replying in the next (last) stage.
            sto.number1 = dto.multiplier;
            // Add an important number to state..!
            sto.number2 = Math.PI;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall", 2));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for MidService.stage1: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n"
                    + context);
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.PI, sto.number2, 0d);
            // Change the important number in state..!
            sto.number2 = Math.E;
            context.next(new DataTO(dto.number, dto.string + ":NextCall"));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for MidService.stage2: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n"
                    + context);
            // Only assert number2, as number1 is differing between calls (it is the multiplier for MidService).
            Assert.assertEquals(Math.E, sto.number2, 0d);
            // Use the 'multiplier' in the request to formulate the reply.. I.e. multiply the number..!
            return new DataTO(dto.number * sto.number1, dto.string + ":FromMidService");
        });
    }

    @BeforeClass
    public static void setupMasterMultiStagedService() {
        MatsEndpoint<DataTO, StateTO> ep = getMatsFactory().staged(SERVICE, DataTO.class, StateTO.class);
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(0, 0), sto);
            sto.number1 = Integer.MAX_VALUE;
            sto.number2 = Math.E;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall1", 3));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage1: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE, Math.E), sto);
            sto.number1 = Integer.MIN_VALUE;
            sto.number2 = Math.E * 2;

            // Add measurement
            context.logMeasurement("test.measure1", "Test measurement 1 from stage 1 of master", "test", Math.PI);
            context.logMeasurement("test.measure2", "Test measurement 2 from stage 1 of master", "test", Math.PI, "labelKeyA", "labelValueA");
            context.logMeasurement("test.measure3", "Test measurement 3 from stage 1 of master", "test", Math.PI, "labelKeyA", "labelValueA", "labelKeyB", "labelValueB");

            context.logTimingMeasurement("test.timing1", "Test measurement 1 from stage 1 of master", 1_000);
            context.logTimingMeasurement("test.timing2", "Test measurement 2 from stage 1 of master", 1_000_000, "labelKeyA", "labelValueA");
            context.logTimingMeasurement("test.timing3", "Test measurement 3 from stage 1 of master", 1_000_000_000, "labelKeyA", "labelValueA", "labelKeyB", "labelValueB");

            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall2", 7));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage2: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE, Math.E * 2), sto);
            sto.number1 = Integer.MIN_VALUE / 2;
            sto.number2 = Math.E / 2;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall1", 4));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage3: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 2, Math.E / 2), sto);
            sto.number1 = Integer.MIN_VALUE / 4;
            sto.number2 = Math.E / 4;
            context.request(SERVICE + ".Leaf", new DataTO(dto.number, dto.string + ":LeafCall2", 6));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage4: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MIN_VALUE / 4, Math.E / 4), sto);
            sto.number1 = Integer.MAX_VALUE / 2;
            sto.number2 = Math.PI / 2;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall3", 8));
        });
        ep.stage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage5: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 2, Math.PI / 2), sto);
            sto.number1 = Integer.MAX_VALUE / 4;
            sto.number2 = Math.PI / 4;
            context.request(SERVICE + ".Mid", new DataTO(dto.number, dto.string + ":MidCall4", 9));
        });
        ep.lastStage(DataTO.class, (context, sto, dto) -> {
            if (log.isDebugEnabled()) log.debug("Incoming message for Multi.stage6: DTO:[" + dto
                    + "], STO:[" + sto + "], context:\n" + context);
            Assert.assertEquals(new StateTO(Integer.MAX_VALUE / 4, Math.PI / 4), sto);
            return new DataTO(dto.number * 5, dto.string + ":FromMasterService");
        });
    }

    @BeforeClass
    public static void setupTerminator() {
        getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.toString());
//                    getMatsTestLatch().resolve(sto, dto);
                });
    }

    public static class DataTO {
        public double number;
        public String string;

        // This is used for the "Test_ComplexLargeMultiStage" to tell the service what it should multiply 'number' with..!
        public int multiplier;

        public DataTO() {
            // For Jackson JSON-lib which needs default constructor.
        }

        public DataTO(double number, String string) {
            this.number = number;
            this.string = string;
        }

        public DataTO(double number, String string, int multiplier) {
            this.number = number;
            this.string = string;
            this.multiplier = multiplier;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            // NOTICE: Not Class-equals, but "instanceof", since we accept the "SubDataTO" too.
            if (o == null || !(o instanceof DataTO)) return false;
            DataTO dataTO = (DataTO) o;
            return Double.compare(dataTO.number, number) == 0 &&
                    multiplier == dataTO.multiplier &&
                    Objects.equals(string, dataTO.string);
        }

        @Override
        public int hashCode() {
            return Objects.hash(number, string, multiplier);
        }

        @Override
        public String toString() {
            return "DataTO [number=" + number
                    + ", string=" + string
                    + (multiplier != 0 ? ", multiplier="+multiplier : "")
                    + "]";
        }
    }

    public static class StateTO {
        public int number1;
        public double number2;

        /**
         * Provide a Default Constructor for the Jackson JSON-lib which needs default constructor. It is needed
         * explicitly in this case since we also have a constructor taking arguments - this would normally not be needed
         * for a STO class.
         */
        public StateTO() {
        }

        public StateTO(int number1, double number2) {
            this.number1 = number1;
            this.number2 = number2;
        }

        @Override
        public int hashCode() {
            return (number1 * 3539) + (int) Double.doubleToLongBits(number2 * 99713.80309);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof StateTO)) {
                throw new AssertionError(StateTO.class.getSimpleName() + " was attempted equalled to [" + obj + "].");
            }
            StateTO other = (StateTO) obj;
            return (this.number1 == other.number1) && (this.number2 == other.number2);
        }

        @Override
        public String toString() {
            return "StateTO [number1=" + number1 + ", number2=" + number2 + "]";
        }
    }
}
