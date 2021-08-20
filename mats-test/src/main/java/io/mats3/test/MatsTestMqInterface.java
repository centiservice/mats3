package io.mats3.test;

import javax.jms.ConnectionFactory;

import io.mats3.MatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.test.MatsTestBrokerInterface.MatsMessageRepresentation;

/**
 * TODO: Delete when > 0.18
 * 
 * @deprecated class is renamed to {@link MatsTestBrokerInterface}
 */
@Deprecated
public class MatsTestMqInterface {
    /**
     * @deprecated class is renamed to {@link MatsTestBrokerInterface}
     */
    @Deprecated
    public static MatsTestBrokerInterface create(ConnectionFactory connectionFactory, MatsSerializer<?> matsSerializer,
            String matsDestinationPrefix, String matsTraceKey) {
        throw new AssertionError("This class renamed to MatsTestBrokerInterface.");
    }

    /**
     * @deprecated class is renamed to {@link MatsTestBrokerInterface}
     */
    @Deprecated
    public static MatsTestBrokerInterface create(ConnectionFactory connectionFactory, JmsMatsFactory<?> matsFactory) {
        throw new AssertionError("This class renamed to MatsTestBrokerInterface.");
    }

    /**
     * @deprecated class is renamed to {@link MatsTestBrokerInterface}
     */
    @Deprecated
    public static MatsTestBrokerInterface createForLaterPopulation() {
        throw new AssertionError("This class renamed to MatsTestBrokerInterface.");
    }

    /**
     * @deprecated class is renamed to {@link MatsTestBrokerInterface}
     */
    @Deprecated
    public void _latePopulate(ConnectionFactory connectionFactory, MatsFactory matsFactory) {
        throw new AssertionError("This class renamed to MatsTestBrokerInterface.");
    }

    /**
     * @deprecated class is renamed to {@link MatsTestBrokerInterface}
     */
    @Deprecated
    public MatsMessageRepresentation getDlqMessage(String endpointOrStageId) {
        throw new AssertionError("This class renamed to MatsTestBrokerInterface.");
    }
}
