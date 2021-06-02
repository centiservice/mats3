package io.mats3.api.intercept;

import io.mats3.MatsFactory;

/**
 * Combines the interfaces {@link MatsInterceptable} and {@link MatsFactory}.
 *
 * @author Endre Stølsvik - 2021-02-07 - http://endre.stolsvik.com
 */
public interface MatsInterceptableMatsFactory extends MatsFactory, MatsInterceptable {
}
