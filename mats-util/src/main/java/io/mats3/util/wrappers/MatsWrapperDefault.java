package io.mats3.util.wrappers;

import io.mats3.MatsFactory.MatsWrapper;

/**
 * Extension of {@link MatsWrapper} that default-implement {@link #unwrapFully()}.
 *
 * @param <T>
 *            the type of the wrapped instance.
 */
public interface MatsWrapperDefault<T> extends MatsWrapper<T> {
    /**
     * @return the fully unwrapped instance: If the returned instance from {@link #unwrap()} is itself a
     *         {@link MatsWrapper MatsWrapper}, it will recurse down by invoking this method
     *         (<code>unwrapFully()</code>) again on the returned target.
     */
    default T unwrapFully() {
        T target = unwrap();
        // ?: If further wrapped, recurse down. Otherwise return.
        if (target instanceof MatsWrapper) {
            // -> Yes, further wrapped, so recurse
            @SuppressWarnings("unchecked")
            MatsWrapper<T> wrapped = (MatsWrapper<T>) target;
            return wrapped.unwrapFully();
        }
        // E-> No, not wrapped - this is the end target.
        return target;
    }
}
