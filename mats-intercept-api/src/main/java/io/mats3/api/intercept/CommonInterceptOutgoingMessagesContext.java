package io.mats3.api.intercept;

import java.util.List;

import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.api.intercept.MatsOutgoingMessage.MatsEditableOutgoingMessage;

/**
 * Common elements of intercepting messages for both Initiate and Stage.
 *
 * @author Endre Stølsvik - 2021-01-08 20:00 - http://endre.stolsvik.com
 */
public interface CommonInterceptOutgoingMessagesContext {
    /**
     * @return the current list of outgoing messages after the user lambda have run - and after previous interceptors
     *         have run, which might have added, or removed messages.
     */
    List<MatsEditableOutgoingMessage> getOutgoingMessages();

    /**
     * To add a new message, you may initiate it here. Notice that any interceptors that are invoked later than this
     * (interceptor was added later) will have this message show up in its {@link #getOutgoingMessages()}.
     */
    void initiate(InitiateLambda lambda);

    /**
     * To remove an outgoing message, you may invoke this method, providing the unique
     * {@link MatsOutgoingMessage#getMatsMessageId() MatsMessageId}. Notice that any interceptors that are invoked later
     * than this (interceptor was added later) will thus not see this message in its {@link #getOutgoingMessages()}.
     *
     * @param matsMessageId
     *            the unique MatsMessageId, gotten from {@link MatsOutgoingMessage#getMatsMessageId()}.
     */
    void cancelOutgoingMessage(String matsMessageId);
}