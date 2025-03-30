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

/**
 * Marker interface to denote a metrics interceptor. The MatsFactory will only allow one such singleton interceptor, and
 * remove any previously installed if subsequently installing another.
 */
public interface MatsMetricsInterceptor {
    /**
     * When measuring "initiate complete", and the initiation ends up sending no messages, there is no
     * <code>InitiatorId</code> to include. This is a constant that can be used instead. Value is
     * <code>"_no_outgoing_messages_"</code>. (Note that this also holds for logging, but it came up first with
     * pure metrics).
     */
    String INITIATOR_ID_WHEN_NO_OUTGOING_MESSAGES = "_no_outgoing_messages_";
}
