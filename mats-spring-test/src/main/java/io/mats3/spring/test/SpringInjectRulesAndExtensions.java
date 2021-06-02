package io.mats3.spring.test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;
import org.springframework.test.context.support.AbstractTestExecutionListener;

import io.mats3.spring.test.SpringInjectRulesAndExtensions.SpringInjectRulesAndExtensionsTestExecutionListener;

/**
 * Use this Test Execution Listener to autowire JUnit Rules and Jupiter Extensions, i.e. so that any fields in the Rule
 * or Extension annotated with @Inject or @Autowire will be autowired - typically needed for
 * <code>Rule_MatsEndpoint</code> and <code>Extension_MatsEndpoint</code>.
 * <p>
 * To use, just put this annotation on the test class. If that fails, typically because you are also employing a
 * different TestExecutionListener, a fallback is to directly list the
 * {@link SpringInjectRulesAndExtensionsTestExecutionListener} in the <code>@TestExecutionListeners</code> annotation on
 * the test class, as such:
 *
 * <pre>
 * {@literal @}TestExecutionListeners(listeners = SpringInjectRulesAndExtensionsTestExecutionListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
 * </pre>
 *
 * @author Kevin Mc Tiernan, 2020-11-03, kmctiernan@gmail.com
 * @author Endre Stølsvik 2020-11-24 23:16 - http://stolsvik.com/, endre@stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestExecutionListeners(listeners = SpringInjectRulesAndExtensionsTestExecutionListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
@Documented
public @interface SpringInjectRulesAndExtensions {

    /**
     * This {@link TestExecutionListener} finds all fields in the test class instance which is annotated with JUnit
     * <code>Rule</code> or JUnit 5 Jupiter <code>RegisterExtension</code>, and runs
     * <code>beanFactory.autowireBean(fieldValue)</code> on them.
     */
    class SpringInjectRulesAndExtensionsTestExecutionListener extends AbstractTestExecutionListener {

        /**
         * Performs dependency injection on <code>Rule</code> and <code>@RegisterExtension</code> fields in test-class
         * as supplied by testContext.
         */
        @Override
        public void prepareTestInstance(TestContext testContext) throws Exception {
            AutowireCapableBeanFactory beanFactory = testContext.getApplicationContext()
                    .getAutowireCapableBeanFactory();

            // Get all fields in test class annotated with @RegisterExtension
            Set<Field> testRuleFields = findFields(testContext.getTestClass(), "org.junit.Rule");
            Set<Field> testExtensionFields = findFields(testContext.getTestClass(),
                    "org.junit.jupiter.api.extension.RegisterExtension");

            Set<Field> allFields = new HashSet<>();
            allFields.addAll(testRuleFields);
            allFields.addAll(testExtensionFields);

            // Use bean factory to autowire all extensions
            for (Field testField : allFields) {
                Object ruleObject = testField.get(testContext.getTestInstance());
                beanFactory.autowireBean(ruleObject);
            }
        }

        /**
         * Find all fields in class with given annotation. Amended from StackOverflow, inspired by Apache Commons Lang.
         * <p/>
         * https://stackoverflow.com/a/29766135
         */
        @SuppressWarnings("unchecked")
        protected static Set<Field> findFields(Class<?> clazz, String annotationClassName) {
            try {
                Class<? extends Annotation> annotationClass = (Class<? extends Annotation>) Class.forName(
                        annotationClassName);
                Set<Field> set = new HashSet<>();
                Class<?> c = clazz;
                while (c != null) {
                    for (Field field : c.getDeclaredFields()) {
                        if (field.isAnnotationPresent(annotationClass)) {
                            set.add(field);
                        }
                    }
                    c = c.getSuperclass();
                }
                return set;
            }
            catch (ClassNotFoundException e) {
                return Collections.emptySet();
            }
        }
    }
}
