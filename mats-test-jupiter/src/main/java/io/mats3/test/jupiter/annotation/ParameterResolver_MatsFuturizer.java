package io.mats3.test.jupiter.annotation;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import io.mats3.test.jupiter.Extension_Mats;
import io.mats3.util.MatsFuturizer;

/**
 * Extension to provide a {@link MatsFuturizer} parameter to a test method.
 * <p>
 * Note, this is a part of {@link MatsTest}, and should not be used directly. It requires the {@link Extension_Mats}
 * to be run first.
 *
 * @author Ståle Undheim <stale.undheim@storebrand.no> 2025-02-06
 */
class ParameterResolver_MatsFuturizer implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == MatsFuturizer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return Extension_Mats.getExtension(extensionContext).getMatsFuturizer();
    }
}
