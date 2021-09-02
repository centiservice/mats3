package io.mats3.impl.jms;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;

import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.MDC;

import io.mats3.MatsEndpoint.MatsObject;
import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler.JmsSessionHolder;
import io.mats3.impl.jms.JmsMatsTransactionManager.JmsMatsTxContextKey;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.SerializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call.Channel;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.KeepMatsTrace;
import io.mats3.serial.MatsTrace.StackState;

/**
 * Common "static" stash, hacked up as an interface to be implemented if you need it.
 *
 * @author Endre Stølsvik 2015-07-24 - http://stolsvik.com/, endre@stolsvik.com
 */
public interface JmsMatsStatics {

    String LOG_PREFIX = "#JMATS# ";

    String THREAD_PREFIX = "MATS:";

    // Not using "mats." prefix for "traceId", as it is hopefully generic yet specific
    // enough that it might be used in similar applications.
    String MDC_TRACE_ID = "traceId";

    // ::: MDC-values. Using "mats." prefix for the Mats-specific parts of MDC

    String MDC_MATS_APP_NAME = "mats.AppName";
    String MDC_MATS_APP_VERSION = "mats.AppVersion";

    // Whether we're talking Init, or Stage, or Init within Stage:
    String MDC_MATS_INIT = "mats.Init"; // 'true' on any loglines involving Initialization (also within Stages)
    String MDC_MATS_STAGE = "mats.Stage"; // 'true' on Stage Processor threads (set fixed on the consumer thread)

    // :: Stage

    String MDC_MATS_STAGE_ID = "mats.StageId"; // "Static" on Stage Processor threads

    // .. Set by Processor when receiving a message:
    String MDC_MATS_IN_MESSAGE_SYSTEM_ID = "mats.in.MsgSysId";

    // :: Message Out

    // NOTICE: Same on MatsMetricsLoggingInterceptor
    String MDC_MATS_OUT_MATS_MESSAGE_ID = "mats.out.MatsMsgId"; // Set when producing message

    // JMS Properties put on the JMSMessage via set[String|Long|Boolean]Property(..)
    String JMS_MSG_PROP_TRACE_ID = "mats_TraceId"; // String
    String JMS_MSG_PROP_MATS_MESSAGE_ID = "mats_MsgId"; // String
    String JMS_MSG_PROP_DISPATCH_TYPE = "mats_DispatchType"; // String
    String JMS_MSG_PROP_MESSAGE_TYPE = "mats_MsgType"; // String
    String JMS_MSG_PROP_ENVELOPE_SIZE = "mats_EnvSize"; // Long
    String JMS_MSG_PROP_FROM = "mats_From"; // String
    String JMS_MSG_PROP_TO = "mats_To"; // String
    String JMS_MSG_PROP_AUDIT = "mats_Audit"; // Boolean

    /**
     * Number of milliseconds to "extra wait" after timeoutMillis or gracefulShutdownMillis is gone.
     */
    int EXTRA_GRACE_MILLIS = 500;

    /**
     * If an outgoing message has {@link MatsTrace#getTotalCallNumber()} higher than this (100), the processing will be
     * refused (i.e. {@link MatsRefuseMessageException} will be thrown).
     */
    int MAX_TOTAL_CALL_NUMBER = 100;

    /**
     * If an outgoing message has {@link MatsTrace#getTotalCallNumber()} higher than this (25), the processing will be
     * refused (i.e. {@link MatsRefuseMessageException} will be thrown).
     */
    int MAX_STACK_HEIGHT = 25;

    /**
     * Log prefix (after {@link #LOG_PREFIX} for flows that will be illegal in a later version.
     */
    String ILLEGAL_CALL_FLOWS = "ILLEGAL CALL FLOWS! ";

    /**
     * We'll use a space-conservative default: {@link KeepMatsTrace#COMPACT}, nulling out the DTOs for calls other than
     * the current call, while still retaining the flow meta info.
     */
    KeepMatsTrace DEFAULT_KEEP_MATS_TRACE = KeepMatsTrace.COMPACT;

    /**
     * Send a bunch of {@link JmsMatsMessage}s.
     */
    default <Z> void produceAndSendMsgSysMessages(Logger log, JmsSessionHolder jmsSessionHolder,
            JmsMatsFactory<Z> jmsMatsFactory, List<JmsMatsMessage<Z>> messagesToSend)
            throws JmsMatsJmsException {
        Session jmsSession = jmsSessionHolder.getSession();
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "Sending [" + messagesToSend.size() + "] messages.");

        MessageProducer messageProducer = jmsSessionHolder.getDefaultNoDestinationMessageProducer();

        // :: Send each message
        for (JmsMatsMessage<Z> jmsMatsMessage : messagesToSend) {
            MatsTrace<Z> outgoingMatsTrace = jmsMatsMessage.getMatsTrace();
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
                mm.setStringProperty(JMS_MSG_PROP_TRACE_ID, outgoingMatsTrace.getTraceId());
                mm.setStringProperty(JMS_MSG_PROP_MATS_MESSAGE_ID, outgoingMatsTrace.getCurrentCall()
                        .getMatsMessageId());
                mm.setStringProperty(JMS_MSG_PROP_DISPATCH_TYPE, jmsMatsMessage.getDispatchType().toString());
                mm.setStringProperty(JMS_MSG_PROP_MESSAGE_TYPE, jmsMatsMessage.getMessageType().toString());
                mm.setLongProperty(JMS_MSG_PROP_ENVELOPE_SIZE, serializedOutgoingMatsTrace.getSizeCompressed());
                mm.setStringProperty(JMS_MSG_PROP_FROM, outgoingMatsTrace.getCurrentCall().getFrom());
                mm.setStringProperty(JMS_MSG_PROP_TO, toChannel.getId());
                mm.setBooleanProperty(JMS_MSG_PROP_AUDIT, !outgoingMatsTrace.isNoAudit());

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

                // Log it.
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

    default <S, Z> S handleIncomingState(MatsSerializer<Z> matsSerializer, Class<S> stateClass,
            StackState<Z> stackState) {
        // ?: Is the desired class Void.TYPE/void.class (or Void.class for legacy reasons).
        if ((stateClass == Void.TYPE) || (stateClass == Void.class)) {
            // -> Yes, so return null (Void can only be null).
            return null;
        }
        // ?: Is the incoming data null?
        if (stackState == null) {
            // -> Yes, so then we return a fresh new State instance
            return matsSerializer.newInstance(stateClass);
        }
        // E-> We have data, and it is not Void - so then deserialize the State
        return matsSerializer.deserializeObject(stackState.getState(), stateClass);
    }

    default <I, Z> I handleIncomingMessageMatsObject(MatsSerializer<Z> matsSerializer, Class<I> incomingMessageClass,
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
                                + " contained in MatsObject to class [" + type.getName() + "].");
                    }
                }
            };
            return ret;
        }
        // E-> it is not special MatsObject
        return matsSerializer.deserializeObject(data, incomingMessageClass);
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
        // 2^122 = 5316911983139663491615228241121378304 // "type 4 (random) UUID"
        // 62^20 = 704423425546998022968330264616370176 // This ID
        // Feels good enough. One more letter would have totally topped it, but this is way too much already.
        return "m_" + randomString(20) + "_T" + Long.toUnsignedString(creationTimeMillis, 36);
    }

    default String createMatsMessageId(String flowId, long matsTraceCreationMillis, long messageCreationMillis,
            int callNumber) {
        // Hack to fix backwards compatibility. Remove after everyone >= v0.15.0
        flowId = flowId != null
                ? flowId
                : randomString(20);

        // Since we can have clock skews between servers, and we do not want a "-" in the messageId (due to the
        // double-clickableness mentioned below), we make -10 -> "n10".
        long millisSince = messageCreationMillis - matsTraceCreationMillis;
        String millisSinceString = millisSince >= 0 ? Long.toString(millisSince) : "n" + Math.abs(millisSince);
        // A MatsMessageId ends up looking like this: 'm_XBExAa1iioAGFVRk6nR5_Tjzswm4ys_t49_n22'
        // Or for negative millisSince: 'm_XBExAa1iioAGFVRk6nR5_Tjzswm4ys_tn49_n22'
        // NOTICE FEATURE: You can double-click anywhere inside that string, and get the entire id marked! w00t!
        return flowId + "_t" + millisSinceString + "_n" + callNumber;
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
            JmsMatsInitiator<?> initiator = (JmsMatsInitiator<?>) txContextKey;
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

    /**
     * Inspired from <a href="https://stackoverflow.com/a/11306854">Stackoverflow - Denys Séguret</a>.
     *
     * @return a String showing where the Mats-code was invoked from, i.e. "com.example.Test.methodName(Test.java:123)"
     */
    default String getInvocationPoint() {
        StackTraceElement[] stElements = Thread.currentThread().getStackTrace();
        for (int i = 1; i < stElements.length; i++) {
            StackTraceElement ste = stElements[i];
            if ((ste.getClassName().startsWith("io.mats3.")
                    /* Handle special usage where wrapped STOW */
                    || ste.getClassName().startsWith("com.skagenfondene.spstow."))
                    && (!ste.getClassName().toLowerCase().contains("test"))) {
                continue;
            }
            // ?: Handle special case which occurs with a "lastStage" style REPLY, since that happens within Mats.
            if (ste.getClassName().equals("java.lang.Thread")) {
                // -> Yes, only found "java.lang.Thread", which means there was no non-Mats stack frames.
                return "<no non-mats stack frames - probably lastStage.return>";
            }
            // E-> return a nice representation of the stackframe, looking like a stacktrace frame.
            return ste.getClassName() + '.' + ste.getMethodName() + "(" + ste.getFileName() + ":" + ste
                    .getLineNumber() + ")";
        }
        // E-> Evidently no stackframes!?
        return "<could not determine invocation point>";
    }
}
