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

import io.mats3.MatsInitiator;

/**
 * Base class for Exceptions thrown around in the JMS implementation of Mats.
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class JmsMatsException extends Exception {
    public JmsMatsException(String message) {
        super(message);
    }

    public JmsMatsException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown if a undeclared <b>checked</b> exception propagates out of the user-supplied lambda. This should obviously
     * not happen, but can happen nevertheless due to checked-ness being a compilation-feature, not a JVM feature.
     * Groovy chooses to ignore the concept of checked exceptions - and it is also possible to throw such an Exception
     * with the "sneaky-throws" paradigm in pure Java (Google it) - and therefore, it is possible to get such Checked
     * Exceptions propagating even though the signature of a method states that it should not be possible.
     * <p />
     * Shall cause rollback.
     */
    public static class JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException extends RuntimeException {
        public JmsMatsUndeclaredCheckedExceptionRaisedRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown if anything goes haywire with the backend implementation, e.g. that any of the numerous exception-throwing
     * methods of the JMS API actually does throw any (unexpected) Exception.
     */
    public static class JmsMatsJmsException extends JmsMatsException {
        public JmsMatsJmsException(String message) {
            super(message);
        }

        JmsMatsJmsException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * Specialization of {@link JmsMatsJmsException}.
     * <p />
     * Corresponds to the {@link MatsInitiator.MatsMessageSendException}, i.e. "VERY BAD!".
     */
    static class JmsMatsMessageSendException extends JmsMatsJmsException {
        JmsMatsMessageSendException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * If we get "stack overflow" or "call overflow", then the sending method will throw this.
     */
    protected static class JmsMatsOverflowRuntimeException extends RuntimeException {
        public JmsMatsOverflowRuntimeException(String message) {
            super(message);
        }
    }

}
