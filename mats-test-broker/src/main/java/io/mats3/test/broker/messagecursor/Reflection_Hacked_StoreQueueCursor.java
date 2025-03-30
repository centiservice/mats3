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

package io.mats3.test.broker.messagecursor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.region.Queue;
import org.apache.activemq.broker.region.cursors.PendingMessageCursor;
import org.apache.activemq.broker.region.cursors.StoreQueueCursor;
import org.apache.activemq.broker.region.policy.PendingQueueMessageStoragePolicy;

/**
 * There's a bug, or implementation curiosity, in ActiveMQ, which leads to starvation when dequeuing messages from a
 * queue where some messages are persistent and others are non-persistent. It is explained in the repository <a href=
 * "https://github.com/stolsvik/activemq_priority_nonpersistent_issue">github.com/stolsvik/activemq_priority_nonpersistent_issue</a>.
 * <p/>
 * An extension of {@link StoreQueueCursor} which must use reflection on the private fields to achieve its goal of
 * overriding the {@link #getNextCursor()} method.
 * <p>
 * A better alternative would be to re-implement the entire class. This was a tad problematic due to some needed classes
 * and methods being package-private in the ActiveMQ codebase. It did work out, though, by copying out 3 more classes.
 * This exploration can be found in the above mentioned repository.
 * <p/>
 * To employ this, use the "policy" {@link Reflection_Hacked_StorePendingQueueMessageStoragePolicy}.
 */
public class Reflection_Hacked_StoreQueueCursor extends StoreQueueCursor {

    /**
     * To "install" the {@link Reflection_Hacked_StoreQueueCursor}, we need a "policy". Here it is.
     */
    public static class Reflection_Hacked_StorePendingQueueMessageStoragePolicy implements
            PendingQueueMessageStoragePolicy {
        public PendingMessageCursor getQueuePendingMessageCursor(Broker broker, Queue queue) {
            return new Reflection_Hacked_StoreQueueCursor(broker, queue);
        }
    }

    private static final VarHandle CURRENT_CURSOR_FIELD;
    private static final VarHandle NON_PERSISTENT_FIELD;
    private static final MethodHandle PERSISTENT_FIELD_GETTER;

    static {
        try {
            NON_PERSISTENT_FIELD = MethodHandles.privateLookupIn(StoreQueueCursor.class, MethodHandles.lookup())
                    .findVarHandle(StoreQueueCursor.class, "nonPersistent", PendingMessageCursor.class);
            CURRENT_CURSOR_FIELD = MethodHandles.privateLookupIn(StoreQueueCursor.class, MethodHandles.lookup())
                    .findVarHandle(StoreQueueCursor.class, "currentCursor", PendingMessageCursor.class);

            Field persistent = StoreQueueCursor.class.getDeclaredField("persistent");
            persistent.setAccessible(true);
            Lookup lookup = MethodHandles.lookup();
            PERSISTENT_FIELD_GETTER = lookup.unreflectGetter(persistent);
        }
        catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("Cant reflect.", e);
        }
    }

    public Reflection_Hacked_StoreQueueCursor(Broker broker, org.apache.activemq.broker.region.Queue queue) {
        super(broker, queue);
    }

    protected synchronized PendingMessageCursor getNextCursor() throws Exception {
        // ?: Sanity check that nonPersistent has been set, i.e. that start() has been invoked.
        PendingMessageCursor nonPersistent = (PendingMessageCursor) NON_PERSISTENT_FIELD.get(this);
        if (nonPersistent == null) {
            // -> No, not set, so don't evaluate switching - so that currentCursor never becomes null.
            return (PendingMessageCursor) CURRENT_CURSOR_FIELD.get(this);
        }

        // :: Get opposite cursor:
        PendingMessageCursor persistent;
        try {
            persistent = (PendingMessageCursor) PERSISTENT_FIELD_GETTER.invoke(this);
        }
        catch (Throwable e) {
            throw new AssertionError("Couldn't invoke getter of field 'StoreQueueCursor.persistent'.", e);
        }
        PendingMessageCursor currentCursor = (PendingMessageCursor) CURRENT_CURSOR_FIELD.get(this);
        // .. actual oppositeCursor resolving:
        PendingMessageCursor oppositeCursor = currentCursor == persistent ? nonPersistent : persistent;

        // ?: Do we have any messages in the opposite?
        if (oppositeCursor.hasNext()) {
            // -> Yes, so do the switch
            CURRENT_CURSOR_FIELD.set(this, oppositeCursor);
            // System.out.println("Swithced to: " + oppositeCursor);
            return oppositeCursor;
        }
        else {
            // System.out.println("DID NOT switch, keeping: " + currentCursor);
        }
        // E-> Return existing current
        return currentCursor;
    }
}
