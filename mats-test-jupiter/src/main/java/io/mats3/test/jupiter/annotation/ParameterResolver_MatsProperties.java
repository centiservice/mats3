package io.mats3.test.jupiter.annotation;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestLatch;
import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.util.MatsFuturizer;

/**
 * Extension to provide properties from the {@link Extension_Mats} as parameters to test instances.
 * <p>
 * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats}
 * to be run first.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
class ParameterResolver_MatsProperties implements ParameterResolver {

    // This corresponds to the getters on Extension_Mats
    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
            MatsInitiator.class, MatsTestLatch.class, MatsFuturizer.class, MatsTestBrokerInterface.class,
            MatsFactory.class
    );


    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (!Extension_Mats.isExtensionRegistered(extensionContext)) {
            return false;
        }
        return SUPPORTED_TYPES
                .contains(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Extension_Mats extensionMats = Extension_Mats.getExtension(extensionContext);
        if (parameterContext.getParameter().getType().equals(MatsInitiator.class)) {
            return extensionMats.getMatsInitiator();
        }
        if (parameterContext.getParameter().getType().equals(MatsTestLatch.class)) {
            return extensionMats.getMatsTestLatch();
        }
        if (parameterContext.getParameter().getType().equals(MatsFuturizer.class)) {
            return extensionMats.getMatsFuturizer();
        }
        if (parameterContext.getParameter().getType().equals(MatsTestBrokerInterface.class)) {
            return extensionMats.getMatsTestBrokerInterface();
        }
        if (parameterContext.getParameter().getType().equals(MatsFactory.class)) {
            return extensionMats.getMatsFactory();
        }
        throw new IllegalStateException("Could not resolve parameter [" + parameterContext.getParameter() + "].");
    }
}
