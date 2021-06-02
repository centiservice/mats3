package io.mats3.impl.jms;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator.MatsInitiate;

public class JmsMatsContextLocalCallback implements BiFunction<Class<?>, String[], Object> {
    private static final ThreadLocal<Map<Object, Object>> THREAD_LOCAL_MAP = ThreadLocal.withInitial(HashMap::new);

    /**
     * Binds a ThreadLocal resource.
     */
    public static void bindResource(Object key, Object value) {
        THREAD_LOCAL_MAP.get().put(key, value);
    }

    /**
     * Retrieves a ThreadLocal-bound resource, bound with {@link #bindResource(Object, Object)}.
     */
    public static Object getResource(Object key) {
        return THREAD_LOCAL_MAP.get().get(key);
    }

    /**
     * Removes a ThreadLocal-bound resource, bound with {@link #bindResource(Object, Object)}.
     */
    public static void unbindResource(Object key) {
        THREAD_LOCAL_MAP.get().remove(key);
    }

    @Override
    public Object apply(Class<?> type, String[] name) {
        // :: First check whether we're inside a stage or initiate context, and can find it in
        // ProcessContext or MatsInitiate
        MatsInitiate init = (MatsInitiate) THREAD_LOCAL_MAP.get().get(MatsInitiate.class);
        // ?: Did we find the MatsInitiate?
        if (init != null) {
            // -> Yes, so forward the call to MatsInitiate
            Optional<?> optionalT = init.getAttribute(type, name);
            if (optionalT.isPresent()) {
                return optionalT;
            }
        }

        // E-> No, no MatsInitiate, check ProcessContext (stage context)
        ProcessContext<?> ctx = (ProcessContext<?>) THREAD_LOCAL_MAP.get().get(ProcessContext.class);
        // ?: Did we find the ProcessContext?
        if (ctx != null) {
            // -> Yes, so forward the call to ProcessContext
            Optional<?> optionalT = ctx.getAttribute(type, name);
            if (optionalT.isPresent()) {
                return optionalT;
            }
        }

        // E-> No, check by name.
        if (name.length != 0) {
            String joinedKey = String.join(".", name);
            Object keyedObject = THREAD_LOCAL_MAP.get().get(joinedKey);
            if ((keyedObject != null)) {
                if (!type.isInstance(keyedObject)) {
                    throw new ClassCastException("The Object stored on key [" + joinedKey + "] is not of that type!");
                }
                return Optional.of(cast(keyedObject));
            }
        }

        // E-> No, check direct lookup of type in the ThreadLocal map.
        Object direct = THREAD_LOCAL_MAP.get().get(type);
        if (direct != null) {
            if (!(type.isInstance(direct))) {
                throw new ClassCastException("The Object stored on key [" + type + "] is not of that type!");
            }
            return Optional.of(cast(direct));
        }

        // E-> No luck.
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object object) {
        return (T) object;
    }
}
