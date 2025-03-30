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