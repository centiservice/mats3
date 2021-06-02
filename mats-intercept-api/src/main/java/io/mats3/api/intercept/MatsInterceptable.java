package io.mats3.api.intercept;

import java.util.List;
import java.util.Optional;

/**
 * Specifies methods that an interceptable MatsFactory must provide.
 *
 * @author Endre Stølsvik - 2021-02-07 - http://endre.stolsvik.com
 */
public interface MatsInterceptable {
    // ===== Initiation

    void addInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor);

    List<MatsInitiateInterceptor> getInitiationInterceptors();

    <T extends MatsInitiateInterceptor> Optional<T> getInitiationInterceptor(Class<T> interceptorClass);

    void removeInitiationInterceptor(MatsInitiateInterceptor initiateInterceptor);

    // ===== Stage

    void addStageInterceptor(MatsStageInterceptor stageInterceptor);

    List<MatsStageInterceptor> getStageInterceptors();

    <T extends MatsStageInterceptor> Optional<T> getStageInterceptor(Class<T> interceptorClass);

    void removeStageInterceptor(MatsStageInterceptor stageInterceptor);
}
