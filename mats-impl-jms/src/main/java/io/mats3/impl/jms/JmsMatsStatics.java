package io.mats3.impl.jms;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.MatsObject;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsStage;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.SerializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call;
import io.mats3.serial.MatsTrace.Call.Channel;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.StackState;

/**
 * Common "static" stash, hacked up as an interface to be implemented if you need it.
 *
 * @author Endre Stølsvik 2015-07-24 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface JmsMatsStatics {

    String LOG_PREFIX = "#JMATS# ";

    String THREAD_PREFIX = "MATS:";

    // ===== MDC properties set for logger at various points ("as early as possible" in the processing)

    // Not using "mats." prefix for "traceId", as it is hopefully generic yet specific
    // enough that it might be used in similar applications.
    String MDC_TRACE_ID = "traceId";

    // ::: Using "mats." prefix for the Mats-specific parts of MDC

    String MDC_MATS_CALL_NUMBER = "mats.CallNo"; // 0 for init, >0 for stages.

    String MDC_MATS_TOTAL_CALL_NUMBER = "mats.TotalCallNo"; // ~call number, but taking into account child flows.

    String MDC_MATS_APP_NAME = "mats.AppName";
    String MDC_MATS_APP_VERSION = "mats.AppVersion";

    // Whether we're talking Init, or Stage, or Init within Stage:
    String MDC_MATS_INIT = "mats.Init"; // 'true' on any loglines involving Initialization (also within Stages)
    String MDC_MATS_STAGE = "mats.Stage"; // 'true' on Stage Processor threads (set fixed on the consumer thread)

    // :: Stage

    String MDC_MATS_STAGE_ID = "mats.StageId"; // "Static" on Stage Processor threads
    String MDC_MATS_STAGE_INDEX = "mats.StageIndex"; // "Static" on Stage Processor threads

    // .. Set by Processor when receiving a message:
    String MDC_MATS_IN_MESSAGE_SYSTEM_ID = "mats.in.MsgSysId";
    // NOTICE: Same on MatsMetricsLoggingInterceptor
    String MDC_MATS_IN_MATS_MESSAGE_ID = "mats.in.MatsMsgId"; // Set when receiving message, from JMS props.

    // :: Message Out

    // NOTICE: Same on MatsMetricsLoggingInterceptor
    String MDC_MATS_OUT_MATS_MESSAGE_ID = "mats.out.MatsMsgId"; // Set when producing message

    // ===== JMS Properties put on the JMSMessage via set[String|Long|Boolean]Property(..)
    // NOTICE: "." is not allowed by JMS (and Apache Artemis complains!), so we use "_".

    String JMS_MSG_PROP_TRACE_ID = "mats_TraceId"; // String
    String JMS_MSG_PROP_MATS_MESSAGE_ID = "mats_MsgId"; // String
    String JMS_MSG_PROP_DISPATCH_TYPE = "mats_DispatchType"; // String
    String JMS_MSG_PROP_MESSAGE_TYPE = "mats_MsgType"; // String
    String JMS_MSG_PROP_FROM = "mats_From"; // String
    String JMS_MSG_PROP_INITIATING_APP = "mats_InitApp"; // String
    String JMS_MSG_PROP_INITIATOR_ID = "mats_InitId"; // String
    String JMS_MSG_PROP_TO = "mats_To"; // String (needed if a message ends up on a global/common DLQ)
    // Four next are set if non-default:
    String JMS_MSG_PROP_INTERACTIVE = "mats_IA"; // Boolean - not set if false
    String JMS_MSG_PROP_NON_PERSISTENT = "mats_NP"; // Boolean - not set if false
    String JMS_MSG_PROP_NO_AUDIT = "mats_NA"; // Boolean - not set if false
    String JMS_MSG_PROP_EXPIRES = "mats_Expires"; // Long - not set if 'never expires'
    // TODO: Delete 'JMS_MSG_PROP_AUDIT' ASAP, latest 2025
    String JMS_MSG_PROP_AUDIT = "mats_Audit"; // Boolean - not set if false

    // :: For 'Mats Managed DLQ Divert' - Note that most of these shall be cleared when reissued from DLQ!
    String JMS_MSG_PROP_DLQ_EXCEPTION = "mats_dlq_Exception"; // String (not set if DLQed on receive-side)
    String JMS_MSG_PROP_DLQ_REFUSED = "mats_dlq_Refused"; // Boolean (not set if DLQed on receive-side)
    String JMS_MSG_PROP_DLQ_DELIVERY_COUNT = "mats_dlq_DeliveryCount"; // Integer
    String JMS_MSG_PROP_DLQ_DLQ_COUNT = "mats_dlq_DlqCount"; // Integer (NOTE: Must be kept when reissued from DLQ!)
    String JMS_MSG_PROP_DLQ_APP_VERSION_AND_HOST = "mats_dlq_AppAndVersion"; // String
    String JMS_MSG_PROP_DLQ_STAGE_ORIGIN = "mats_dlq_StageOrigin"; // String

    // NOTE: Ignoring all the DLQ properties for the "Mats Managed DLQ Divert": they are only set when diverting to DLQ.
    int TOTAL_JMS_MSG_PROPS_SIZE = JMS_MSG_PROP_TRACE_ID.length()
            + JMS_MSG_PROP_MATS_MESSAGE_ID.length()
            + JMS_MSG_PROP_DISPATCH_TYPE.length()
            + JMS_MSG_PROP_MESSAGE_TYPE.length()
            + JMS_MSG_PROP_FROM.length()
            + JMS_MSG_PROP_INITIATING_APP.length()
            + JMS_MSG_PROP_INITIATOR_ID.length()
            + JMS_MSG_PROP_TO.length();

    /**
     * Number of milliseconds to "extra wait" after timeoutMillis or gracefulShutdownMillis is gone.
     */
    int EXTRA_GRACE_MILLIS = 500;

    /**
     * If an outgoing message has {@link MatsTrace#getTotalCallNumber()} higher than this (200), the processing will be
     * refused (i.e. {@link MatsRefuseMessageException} will be thrown).
     */
    int MAX_TOTAL_CALL_NUMBER = 200;

    /**
     * If an outgoing message has {@link Call#getReplyStackHeight()} higher than this (35), the processing will be
     * refused (i.e. {@link MatsRefuseMessageException} will be thrown).
     */
    int MAX_STACK_HEIGHT = 35;

    /**
     * Log prefix (after {@link #LOG_PREFIX}) for flows that are illegal.
     */
    String ILLEGAL_CALL_FLOWS = "ILLEGAL CALL FLOWS! ";

    /**
     * Send a bunch of {@link JmsMatsMessage}s.
     */
    default  void produceAndSendMsgSysMessages(Logger log, JmsSessionHolder jmsSessionHolder,
            JmsMatsFactory jmsMatsFactory, List<JmsMatsMessage> messagesToSend)
            throws JmsMatsJmsException {
        Session jmsSession = jmsSessionHolder.getSession();
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Sending [" + messagesToSend.size() + "] messages.");

        MessageProducer messageProducer = jmsSessionHolder.getDefaultNoDestinationMessageProducer();

        // :: Send each message
        for (JmsMatsMessage jmsMatsMessage : messagesToSend) {
            MatsTrace outgoingMatsTrace = jmsMatsMessage.getMatsTrace();
            SerializedMatsTrace serializedOutgoingMatsTrace = jmsMatsMessage.getCachedSerializedMatsTrace();

            long nanosStart_ProduceAndSendSingleJmsMessage = System.nanoTime();
            Channel toChannel = outgoingMatsTrace.getCurrentCall().getTo();
            // :: Keep MDC's TraceId to restore
            String existingTraceId = MDC.get(MDC_TRACE_ID);
            try { // :: try-catch-finally: Catch JMSException, Finally restore MDC
                  // Set MDC for this outgoing message
                MDC.put(MDC_TRACE_ID, outgoingMatsTrace.getTraceId());
                MDC.put(MDC_MATS_OUT_MATS_MESSAGE_ID, outgoingMatsTrace.getCurrentCall().getMatsMessageId());

                // Get FactoryConfig
                FactoryConfig factoryConfig = jmsMatsFactory.getFactoryConfig();

                // Create the JMS MapMessage that will be sent.
                MapMessage mm = jmsSession.createMapMessage();
                // Set the MatsTrace.
                mm.setBytes(factoryConfig.getMatsTraceKey(), serializedOutgoingMatsTrace.getMatsTraceBytes());
                mm.setString(factoryConfig.getMatsTraceKey() + MatsSerializer.META_KEY_POSTFIX,
                        serializedOutgoingMatsTrace.getMeta());

                // :: Add the Mats properties to the MapMessage
                for (Entry<String, byte[]> entry : jmsMatsMessage.getBytes().entrySet()) {
                    mm.setBytes(entry.getKey(), entry.getValue());
                }
                for (Entry<String, String> entry : jmsMatsMessage.getStrings().entrySet()) {
                    mm.setString(entry.getKey(), entry.getValue());
                }

                // :: Add some JMS Message Properties to simplify intercepting/logging on MQ Broker.
                // .. and also for ourselves when we receive the message (picking out traceId and matsMessageId).
                mm.setStringProperty(JMS_MSG_PROP_TRACE_ID, outgoingMatsTrace.getTraceId());
                mm.setStringProperty(JMS_MSG_PROP_MATS_MESSAGE_ID,
                        outgoingMatsTrace.getCurrentCall().getMatsMessageId());
                mm.setStringProperty(JMS_MSG_PROP_DISPATCH_TYPE, jmsMatsMessage.getDispatchType().toString());
                mm.setStringProperty(JMS_MSG_PROP_MESSAGE_TYPE, jmsMatsMessage.getMessageType().toString());
                mm.setStringProperty(JMS_MSG_PROP_INITIATING_APP, outgoingMatsTrace.getInitiatingAppName());
                mm.setStringProperty(JMS_MSG_PROP_INITIATOR_ID, outgoingMatsTrace.getInitiatorId());
                mm.setStringProperty(JMS_MSG_PROP_FROM, outgoingMatsTrace.getCurrentCall().getFrom());
                mm.setStringProperty(JMS_MSG_PROP_TO, toChannel.getId());
                // TODO: Delete 'JMS_MSG_PROP_AUDIT' ASAP, latest 2025
                mm.setBooleanProperty(JMS_MSG_PROP_AUDIT, !outgoingMatsTrace.isNoAudit());
                if (outgoingMatsTrace.isNoAudit()) {
                    mm.setBooleanProperty(JMS_MSG_PROP_NO_AUDIT, true);
                }
                if (outgoingMatsTrace.isInteractive()) {
                    mm.setBooleanProperty(JMS_MSG_PROP_INTERACTIVE, true);
                }
                if (outgoingMatsTrace.isNonPersistent()) {
                    mm.setBooleanProperty(JMS_MSG_PROP_NON_PERSISTENT, true);
                }
                if (outgoingMatsTrace.getTimeToLive() != 0) {
                    mm.setLongProperty(JMS_MSG_PROP_EXPIRES, System.currentTimeMillis()
                            + outgoingMatsTrace.getTimeToLive());
                }

                // Setting DeliveryMode: NonPersistent or Persistent
                int deliveryMode = outgoingMatsTrace.isNonPersistent()
                        ? DeliveryMode.NON_PERSISTENT
                        : DeliveryMode.PERSISTENT;

                // Setting Priority: 4 is default, 9 is highest.
                int priority = outgoingMatsTrace.isInteractive() ? 9 : 4;

                // Get Time-To-Live
                long timeToLive = outgoingMatsTrace.getTimeToLive();

                // :: Create the JMS Queue or Topic.
                Destination destination = toChannel.getMessagingModel() == MessagingModel.QUEUE
                        ? jmsSession.createQueue(factoryConfig.getMatsDestinationPrefix() + toChannel.getId())
                        : jmsSession.createTopic(factoryConfig.getMatsDestinationPrefix() + toChannel.getId());

                // :: Send the message (but since transactional, won't be committed until TransactionContext does).
                messageProducer.send(destination, mm, deliveryMode, priority, timeToLive);

                // Time taken for produce and send
                long nanosTaken_ProduceAndSendSingleJmsMessage = System.nanoTime()
                        - nanosStart_ProduceAndSendSingleJmsMessage;

                jmsMatsMessage.setSentProperties(mm.getJMSMessageID(),
                        serializedOutgoingMatsTrace.getNanosSerialization(),
                        serializedOutgoingMatsTrace.getSizeUncompressed(),
                        serializedOutgoingMatsTrace.getNanosCompression(),
                        serializedOutgoingMatsTrace.getSizeCompressed(),
                        nanosTaken_ProduceAndSendSingleJmsMessage);
            }
            catch (JMSException e) {
                // Log on error to get MDC tracking
                String msg = "Got problems sending [" + jmsMatsMessage.getWhat()
                        + "] to [" + toChannel + "] via JMS API: JMSException of type ["
                        + e.getClass().getSimpleName() + "]: " + e.getMessage();
                log.error(msg); // The exception stack trace will be printed later.
                throw new JmsMatsJmsException(msg, e);
            }
            finally {
                // :: Restore MDC
                // TraceId
                if (existingTraceId != null) {
                    MDC.put(MDC_TRACE_ID, existingTraceId);
                }
                else {
                    MDC.remove(MDC_TRACE_ID);
                }
                // The rest..
                MDC.remove(MDC_MATS_OUT_MATS_MESSAGE_ID);
            }
        }
    }

    static <S, Z> S handleIncomingState(MatsSerializer matsSerializer, Class<S> stateClass,
            StackState stackState) {
        // ?: Is the desired class Void.TYPE/void.class (or Void.class for legacy reasons).
        if ((stateClass == Void.TYPE) || (stateClass == Void.class)) {
            // -> Yes, so return null (Void can only be null).
            return null;
        }
        // ?: Is the incoming StackState null?
        // This happens for initial stage or any endpoint unless init'ed with initialState, OR if the endpoint is
        // invoked as a REPLY from a REQUEST initiation, i.e. init.replyTo(.., state).
        if (stackState == null) {
            // -> Yes, so then we return a fresh new State instance
            return matsSerializer.newInstance(stateClass);
        }
        // ?: Is the incoming StackState's data null?
        // This happens if the endpoint is invoked as a REPLY from a REQUEST initiation, i.e. init.replyTo(.., state),
        // but where the state is null, ref mats3 GitHub Issue #64.
        if (stackState.getState() == null) {
            // -> Yes, so then we return a fresh new State instance
            return matsSerializer.newInstance(stateClass);
        }
        // E-> We have data, and it is not Void - so then deserialize the State
        return matsSerializer.deserializeObject(stackState.getState(), stateClass);
    }

    static <I, Z> I handleIncomingMessageMatsObject(MatsSerializer matsSerializer, Class<I> incomingMessageClass,
            Z data) {
        // ?: Is the desired class Void.TYPE/void.class (or Void.class for legacy reasons).
        if (incomingMessageClass == Void.TYPE || incomingMessageClass == Void.class) {
            // -> Yes, so return null (Void can only be null).
            // NOTE! The reason for handling this here, not letting Jackson do it, is that Jackson has a bug, IMHO:
            // https://github.com/FasterXML/jackson-databind/issues/2679
            return null;
        }
        // ?: Is the desired class the special MatsObject?
        if (incomingMessageClass == MatsObject.class) {
            // -> Yes, special MatsObject, so return this "deferred deserialization" type.
            @SuppressWarnings(value = "unchecked") // We've checked that I is indeed MatsObject
            I ret = (I) new MatsObject() {
                @Override
                public <T> T toClass(Class<T> type) throws IllegalArgumentException {
                    // ?: Is it the special type Void.TYPE?
                    if (type == Void.TYPE) {
                        // -> Yes, Void.TYPE, so return null (Void can only be null).
                        return null;
                    }
                    // E-> No, not VOID, so deserialize.
                    try {
                        return matsSerializer.deserializeObject(data, type);
                    }
                    catch (Throwable t) {
                        throw new IllegalArgumentException("Could not deserialize the data"
                                + " contained in MatsObject to class [" + type.getName() + "].", t);
                    }
                }
            };
            return ret;
        }
        // E-> it is not special MatsObject
        return matsSerializer.deserializeObject(data, incomingMessageClass);
    }

    static void makeMessagePropertiesEditable(Message message) throws JMSException {
        Map<String, Object> existingProperties = new HashMap<>();
        @SuppressWarnings("unchecked")
        Enumeration<String> propertyNames = message.getPropertyNames();
        while (propertyNames.hasMoreElements()) {
            String propertyName = propertyNames.nextElement();
            existingProperties.put(propertyName, message.getObjectProperty(propertyName));
        }
        // .. and then clear the properties (to make them editable)
        message.clearProperties();
        // .. and then put them back on (now editable!)
        for (Map.Entry<String, Object> entry : existingProperties.entrySet()) {
            message.setObjectProperty(entry.getKey(), entry.getValue());
        }
    }

    // 62 points in this alphabet
    String RANDOM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * @param length
     *            the desired length of the returned random string.
     * @return a random string of the specified length.
     */
    default String randomString(int length) {
        StringBuilder buf = new StringBuilder(length);
        ThreadLocalRandom tlRandom = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++)
            buf.append(RANDOM_ALPHABET.charAt(tlRandom.nextInt(RANDOM_ALPHABET.length())));
        return buf.toString();
    }

    default String createFlowId(long creationTimeMillis) {
        /*
         * We need a pretty much guaranteed unique id. It does not really need to be globally unique, but just unique
         * within the Mats fabric in use, i.e. within the connected JMS broker.
         *
         * The id consists of a random part, plus the timestamp in milliseconds. The random part thus only needs to be
         * unique within a single millisecond.
         *
         * The random alphabet in use has 62 entries. If we use length = 10, we get 62^10 = 839_299_365_868_340_224.
         *
         * Playing a bit with the birthday paradox: y=1-e^(-n(n-1)/(2*839_299_365_868_340_224)) Graphing this, we see
         * that we'd need ~13_000_000 generated flow ids in a millisecond to get above 0.01% chance of having a
         * collision. If we produce 1 million flow ids in one millisecond, there is a chance of <.00006% of having a
         * collision.
         *
         * The odds of getting struck by lightning in a given year (US): https://www.weather.gov/safety/lightning-odds
         * 1/1_222_000 = 0.000082%
         *
         * Short (but good enough) ids are better than long (but way overkill) ids. This is hopefully good enough.
         */
        return "m_" + randomString(10) + "_T" + Long.toUnsignedString(creationTimeMillis, 36);
    }

    default String id(String what, Object obj) {
        return what + '@' + Integer.toHexString(System.identityHashCode(obj));
    }

    default String id(Object obj) {
        return id(obj.getClass().getSimpleName(), obj);
    }

    default String idThis() {
        return id(this);
    }

    default String stageOrInit(JmsMatsTxContextKey txContextKey) {
        // ?: Stage or Initiator?
        if (txContextKey instanceof JmsMatsStageProcessor) {
            // -> Stage
            return "StageProcessor[" + txContextKey.getStage() + "]";
        }
        else if (txContextKey instanceof JmsMatsInitiator) {
            // -> Initiator
            JmsMatsInitiator initiator = (JmsMatsInitiator) txContextKey;
            return "Initiator[" + initiator.getName() + "]";
        }
        else {
            return "SomethingUnknown!";
        }
    }

    /**
     * Truncate milliseconds to 3 decimals.
     */
    default double ms3(double ms) {
        return Math.round(ms * 1000d) / 1000d;
    }

    String NO_INVOCATION_POINT = "-no_info-";

    /**
     * Inspired from <a href="https://stackoverflow.com/a/11306854">Stackoverflow - Denys Séguret</a>.
     *
     * @return a String showing where the Mats-code was invoked from, like "Test.java.123;com.example.Test;methodName()"
     */
    default String getInvocationPoint() {
        StackTraceElement[] stElements = Thread.currentThread().getStackTrace();
        for (int i = 1; i < stElements.length; i++) {
            StackTraceElement ste = stElements[i];
            if ((ste.getClassName().startsWith("io.mats3.")
                    /* Handle special usage where wrapped STOW */
                    || ste.getClassName().startsWith("com.skagenfondene.spstow."))
                    // No class of JMS Mats has "test" in its name, so break if seen.
                    && (!ste.getClassName().toLowerCase().contains("test"))) {
                continue;
            }
            // ?: Handle special case which occurs with a "lastStage" style REPLY, since that happens within Mats.
            if (ste.getClassName().equals("java.lang.Thread")) {
                // -> Yes, only found "java.lang.Thread", which means there was no non-Mats stack frames.
                return NO_INVOCATION_POINT;
            }
            // E-> We have an invocation point, return a nice string representation
            // Evidently, ste.getFileName() can "randomly" return null. Handle this with a tad info.
            String fileInfo = ste.getFileName() == null
                    ? "-missing_file:line-"
                    : ste.getFileName() + ":" + ste.getLineNumber();
            // Using semicolons to separate elements; e.g. 'matsbrokermonitor' splits this into 3 lines.
            return fileInfo + ";"
                    + ste.getClassName() + ";"
                    + ste.getMethodName() + "()";
        }
        // E-> Evidently no stackframes!?
        return NO_INVOCATION_POINT;
    }

    /**
     * Set concurrency on entity, printing log
     */
    default void setConcurrencyWithLog(Logger log, String what, Supplier<Integer> getter,
            Supplier<Boolean> isDefault, Consumer<Integer> setter, int newConcurrency) {

        int previousConcurrencyResult = getter.get();
        boolean previousIsDefault = isDefault.get();
        // Set new
        setter.accept(newConcurrency);
        int newConcurrencyResult = getter.get();
        boolean newIsDefault = isDefault.get();

        String msg = what + " is set to ";
        if (newIsDefault) {
            msg += "[0:default -> " + newConcurrencyResult + "]";
        }
        else {
            msg += "[" + newConcurrencyResult + "]";
        }
        msg += " - was ";
        if (previousIsDefault) {
            msg += "[0:default -> " + previousConcurrencyResult + "]";
        }
        else {
            msg += "[" + previousConcurrencyResult + "]";
        }
        log.info(LOG_PREFIX + msg);
    }

    static String convertToDlqName(Logger log, JmsMatsFactory jmsMatsFactory,
            JmsMatsStage<?, ?, ?> jmsMatsStage) {
        String dlqName;
        try {
            dlqName = jmsMatsFactory.getDestinationNameModifierForDlqs().apply(jmsMatsStage);
        }
        catch (Throwable t) {
            log.error(LOG_PREFIX + "Unbelievably, someone has made a bad MatsFactory "
                    + " 'DestinationNameModifierForDlqs' function, which threw an Exception for"
                    + " stage [" + jmsMatsStage.getStageId() + "]."
                    + " Using DLQ-name 'DLQ' instead.", t);
            dlqName = "DLQ";
        }
        return dlqName;
    }

    static void sendToDlq(Logger log, Session jmsSession, MessageProducer messageProducer, MatsStage<?, ?, ?> matsStage,
            Message message, String dlqName, int deliveryCount, boolean refused, Throwable t)
            throws JMSException {
        JmsMatsStatics.makeMessagePropertiesEditable(message);

        // ?: Do we have a Throwable? (Always do in post-Stage, never in pre-Stage/receive-side).
        if (t != null) {
            // -> Yes, Throwable: Convert the Throwable to string, and add it
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            message.setStringProperty(JMS_MSG_PROP_DLQ_EXCEPTION, sw.toString());

            // Refused? (Can only be set if there is a Throwable, as otherwise it was DLQed on receive-side.)
            message.setBooleanProperty(JMS_MSG_PROP_DLQ_REFUSED, refused);
        }
        // How many times has this message been delivered
        message.setIntProperty(JMS_MSG_PROP_DLQ_DELIVERY_COUNT, deliveryCount);

        // How many rounds have this message been through DLQing? (Re-issue from a monitor, new DLQ)
        message.setIntProperty(JMS_MSG_PROP_DLQ_DLQ_COUNT, message.propertyExists(JMS_MSG_PROP_DLQ_DLQ_COUNT)
                ? message.getIntProperty(JMS_MSG_PROP_DLQ_DLQ_COUNT) + 1
                : 1);

        message.setStringProperty(JMS_MSG_PROP_DLQ_STAGE_ORIGIN, matsStage.getStageConfig().getOrigin());

        FactoryConfig factoryConfig = matsStage.getParentEndpoint().getParentFactory().getFactoryConfig();
        message.setStringProperty(JMS_MSG_PROP_DLQ_APP_VERSION_AND_HOST, factoryConfig.getAppName()
                + ";" + factoryConfig.getAppVersion() + "@" + factoryConfig.getNodename());

        // :: Send (divert) the message
        log.info(LOG_PREFIX + "Diverting message to DLQ [" + dlqName + "].");
        messageProducer.send(jmsSession.createQueue(dlqName), message);
        // .. and Commit
        jmsSession.commit();
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "JMS Session committed for DLQ Divert to" +
                " [" + dlqName + "].");
    }
}
