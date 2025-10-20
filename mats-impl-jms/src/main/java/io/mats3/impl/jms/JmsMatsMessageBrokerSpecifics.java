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

package io.mats3.impl.jms;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint.MatsRefuseMessageException;
import io.mats3.impl.jms.JmsMatsException.JmsMatsJmsException;

/**
 * Some small specifics for the underlying JMS Implementation. Currently handled: ActiveMQ.
 * <p />
 * <h2>ActiveMQ</h2>
 * <ul>
 * <li>Check for Connection liveliness: {@code ActiveMQConnection.is[Closed|Closing|TransportFailed]}.</li>
 * <li>Honor the {@link MatsRefuseMessageException} (i.e. insta-DLQing), by setting redelivery attempts to 0 on the
 * MessageConsumer when rolling back Session: {@code ActiveMQSession.setRedeliveryPolicy(zeroAttemptsPolicy)}.</li>
 * </ul>
 */
public class JmsMatsMessageBrokerSpecifics {

    private static final Logger log = LoggerFactory.getLogger(JmsMatsMessageBrokerSpecifics.class);

    private JmsMatsMessageBrokerSpecifics() {
        /* utility class */
    }

    // :: For ActiveMQ's impl of isConnectionLive
    private static final Class<?> _activeMqConnection_class;
    private static final Method _activeMqConnection_isClosing;
    private static final Method _activeMqConnection_isClosed;
    private static final Method _activeMqConnection_isTransportFailed;

    // :: For ActiveMQ's impl of instaDlqWithRollbackLambda
    private static final Object _activeMqRedeliveryPolicy_zeroRedeliveries;
    private static final Class<?> _activeMqMessageConsumer_class;
    private static final Method _activeMqMessageConsumer_getRedeliveryPolicy;
    private static final Method _activeMqMessageConsumer_setRedeliveryPolicy;

    static {
        // :: Check if we have ActiveMQConnection, and if so get the "liveliness methods".

        Class<?> amqConnClass = null;
        Method isClosing = null;
        Method isClosed = null;
        Method isTransportFailed = null;
        try {
            Class<?> l_amqConnCllass = Class.forName("org.apache.activemq.ActiveMQConnection");
            Method l_isClosing = l_amqConnCllass.getMethod("isClosing");
            Method l_isClosed = l_amqConnCllass.getMethod("isClosed");
            Method l_isTransportFailed = l_amqConnCllass.getMethod("isTransportFailed");
            // ----- We've got all these methods, now set them on the class.
            amqConnClass = l_amqConnCllass;
            isClosing = l_isClosing;
            isClosed = l_isClosed;
            isTransportFailed = l_isTransportFailed;
        }
        catch (ClassNotFoundException e) {
            log.info("Couldn't get hold of 'org.apache.activemq.ActiveMQConnection' class,"
                    + " so ActiveMQ probably not on classpath.");
        }
        catch (NoSuchMethodException e) {
            log.warn("ActiveMQConnection was on classpath, but couldn't get hold of"
                    + " 'org.apache.activemq.ActiveMQConnection.is[Closing|Closed|TransportFailed]"
                    + " methods. This is not expected, report a bug! Mats will still work, though.", e);
        }
        _activeMqConnection_class = amqConnClass;
        _activeMqConnection_isClosing = isClosing;
        _activeMqConnection_isClosed = isClosed;
        _activeMqConnection_isTransportFailed = isTransportFailed;

        // :: Check if we have ActiveMQConsumer, and RedeliveryPolicy, and make a "0-redeliveries" policy.

        Class<?> redeliveryPolicyClass = null;
        Object zeroRedeliveries = null;
        try {
            Class<?> l_redeliveryPolicyClass = Class.forName("org.apache.activemq.RedeliveryPolicy");
            Method setMaximumRedeliveries = l_redeliveryPolicyClass.getMethod("setMaximumRedeliveries", int.class);

            // Create a 0-redeliveries RedeliveryPolicy
            Object l_zeroRedeliveries;
            try {
                l_zeroRedeliveries = l_redeliveryPolicyClass.getDeclaredConstructor().newInstance();
                try {
                    setMaximumRedeliveries.invoke(l_zeroRedeliveries, 0);
                    // ----- We've now created the 0-redeliveries RedeliveryPolicy, so set it on the outside
                    redeliveryPolicyClass = l_redeliveryPolicyClass;
                    zeroRedeliveries = l_zeroRedeliveries;
                }
                catch (IllegalAccessException | InvocationTargetException e) {
                    log.warn("Invoking RedeliveryPolicy.setMaximumRedeliveries(0) raised exception, which"
                            + "is not expected: Report a bug!", e);
                }
            }
            catch (IllegalAccessException | InvocationTargetException | InstantiationException  e) {
                log.warn("Could not create a 'org.apache.activemq.RedeliveryPolicy' instance. This is not expected,"
                        + " report a bug!", e);
            }
        }
        catch (ClassNotFoundException e) {
            log.info("Couldn't get hold of 'org.apache.activemq.RedeliveryPolicy' class,"
                    + " so ActiveMQ probably not on classpath.");
        }
        catch (NoSuchMethodException e) {
            log.warn("'org.apache.activemq.RedeliveryPolicy' was on classpath, but couldn't get hold of"
                    + " RedeliveryPolicy.setMaximumRedeliveries()"
                    + " method. This is not expected, report a bug! Mats will still work, though.", e);
        }

        Class<?> amqMsgConsClass = null;
        Method getRedeliveryPolicy = null;
        Method setRedeliveryPolicy = null;
        // ?: Did we manage to make the 0-redeliveries RedeliveryPolicy?
        if (zeroRedeliveries != null) {
            // -> Yes, managed to make 0-redeliveries - create what we need to set it.
            Class<?> l_amqMsgConsClass;
            Method l_getRedeliveryPolicy;
            Method l_setRedeliveryPolicy;
            try {
                l_amqMsgConsClass = Class.forName("org.apache.activemq.ActiveMQMessageConsumer");
                l_getRedeliveryPolicy = l_amqMsgConsClass.getMethod("getRedeliveryPolicy");
                l_setRedeliveryPolicy = l_amqMsgConsClass.getMethod("setRedeliveryPolicy", redeliveryPolicyClass);
                // ----- We've got the class and needed methods: Set them on the outside, so they can be set on class
                amqMsgConsClass = l_amqMsgConsClass;
                getRedeliveryPolicy = l_getRedeliveryPolicy;
                setRedeliveryPolicy = l_setRedeliveryPolicy;
            }
            catch (ClassNotFoundException e) {
                log.info("Couldn't get hold of 'org.apache.activemq.ActiveMQMessageConsumer' class,"
                        + " so ActiveMQ probably not on classpath.");
            }
            catch (NoSuchMethodException e) {
                log.warn("'org.apache.activemq.ActiveMQMessageConsumer' was on classpath, but couldn't get hold of"
                        + " ActiveMQMessageConsumer.[get|set]RedeliveryPolicy()"
                        + " methods. This is not expected, report a bug! Mats will still work, though.", e);
            }
        }
        _activeMqMessageConsumer_class = amqMsgConsClass;
        _activeMqMessageConsumer_getRedeliveryPolicy = getRedeliveryPolicy;
        _activeMqMessageConsumer_setRedeliveryPolicy = setRedeliveryPolicy;
        _activeMqRedeliveryPolicy_zeroRedeliveries = zeroRedeliveries;
    }

    public static void init() {
        log.info("Initializing " + JmsMatsMessageBrokerSpecifics.class.getSimpleName() + " (idempotent init).");
        /* Relying on static init block for init. */
    }

    /**
     * For an ActiveMQ JMS Connection, this method throws {@link JmsMatsJmsException} if any of the following methods
     * return <code>true</code>:
     * <ul>
     * <li>jmsConnection.isClosing()</li>
     * <li>jmsConnection.isClosed()</li>
     * <li>jmsConnection.isTransportFailed()</li>
     * </ul>
     *
     * @param jmsConnection
     *            the JMS Connection to check.
     * @throws JmsMatsJmsException
     *             if it seems like this Connection is dead.
     */
    public static void isConnectionLive(Connection jmsConnection) throws JmsMatsJmsException {
        if ((_activeMqConnection_class != null) && _activeMqConnection_class.isInstance(jmsConnection)) {
            try {
                if ((Boolean) _activeMqConnection_isClosing.invoke(jmsConnection)) {
                    throw new JmsMatsJmsException("ActiveMQ JMS Connection [" + jmsConnection + "].isClosing()"
                            + " == true.");
                }
                if ((Boolean) _activeMqConnection_isClosed.invoke(jmsConnection)) {
                    throw new JmsMatsJmsException("ActiveMQ JMS Connection [" + jmsConnection + "].isClosed()"
                            + " == true.");
                }
                if ((Boolean) _activeMqConnection_isTransportFailed.invoke(jmsConnection)) {
                    throw new JmsMatsJmsException("Active MQ JMS Connection [" + jmsConnection + "].isTransportFailed()"
                            + " == true.");
                }
            }
            catch (IllegalAccessException | InvocationTargetException e) {
                log.warn("Invoking ActiveMqConnection.is[Closing|Closed|TransportFailed]() raised exception, which"
                        + " is not expected: Report a bug on Mats!", e);
            }
        }
        // ----- No problems found, return w/o throwing.
    }

    /**
     * If the MessageConsumer is an ActiveMQMessageConsumer, then it should set it into "0 redeliveries" mode, and then
     * run the provided lambda to rollback the current message, and then re-set the deliveries. If not an
     * ActiveMQMessageConsumer, just run the provided lambda directly to rollback the current message.
     * 
     * @param jmsMessageConsumer
     *            consumer to set to "0 redeliveries" (will be reset before return)
     * @param rollbackLambda
     *            the lambda that should be run to perform the rollback of the current message.
     * @throws JmsMatsJmsException
     */
    public static void instaDlqWithRollbackLambda(MessageConsumer jmsMessageConsumer,
            JmsMatsJmsExceptionThrowingRunnable rollbackLambda)
            throws JmsMatsJmsException {
        if ((_activeMqMessageConsumer_class != null) && _activeMqMessageConsumer_class.isInstance(jmsMessageConsumer)) {
            // -> Yes, we're in ActiveMQ world
            Object existingRedeliveryPolicy = null;
            try {
                existingRedeliveryPolicy = _activeMqMessageConsumer_getRedeliveryPolicy.invoke(jmsMessageConsumer);
                _activeMqMessageConsumer_setRedeliveryPolicy.invoke(jmsMessageConsumer,
                        _activeMqRedeliveryPolicy_zeroRedeliveries);
            }
            catch (IllegalAccessException | InvocationTargetException e) {
                log.warn("Invoking ActiveMqMessageConsumer.[get|set]RedeliveryPolicy() raised exception, which"
                        + "is not expected: Report a bug on Mats!", e);
            }
            try {
                rollbackLambda.run();
            }
            finally {
                if (existingRedeliveryPolicy != null) {
                    try {
                        _activeMqMessageConsumer_setRedeliveryPolicy
                                .invoke(jmsMessageConsumer, existingRedeliveryPolicy);
                    }
                    catch (IllegalAccessException | InvocationTargetException e) {
                        log.warn("Invoking ActiveMqMessageConsumer.setRedeliveryPolicy() raised exception, which"
                                + "is not expected: Report a bug on Mats!", e);
                    }
                }
            }
        }
        else {
            // -> No, we're not ActiveMQ, so just run the rollback.
            rollbackLambda.run();
        }
    }

    @FunctionalInterface
    public interface JmsMatsJmsExceptionThrowingRunnable {
        void run() throws JmsMatsJmsException;
    }
}
