package io.mats3.spring.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.spring.EnableMats;
import io.mats3.spring.test.MatsTestContext.MatsSimpleTestInfrastructureContextInitializer;
import io.mats3.test.MatsTestLatch;
import io.mats3.util.MatsFuturizer;
import io.mats3.test.activemq.MatsLocalVmActiveMq;

/**
 * One-stop-shop for making <i>simple</i> Spring-based integration/unit tests of MATS endpoints (NOT utilizing SQL
 * Connections), automatically importing the configuration . The configuration is done in
 * {@link MatsTestInfrastructureConfiguration}. This annotation can be put on a test-class, or on a
 * {@literal @Configuration} class (typically a nested static class within the test-class).
 * <p />
 * <i>Observe: If this "kitchen sink" annotation doesn't serve your needs, an alternative for
 * quickly putting a {@link MatsFactory} into a Spring test context is to utilize the methods in
 * {@link TestSpringMatsFactoryProvider} within a <code>{@literal @Bean}</code> factory method.</i>
 * <p />
 * The annotation, mostly via {@link MatsTestInfrastructureConfiguration}, sets up the following:
 * <ul>
 * <li>Meta-annotated with {@link DirtiesContext}, since the Spring Test Context caching system is problematic when one
 * is handling static/global resources like what an external Message Queue broker is. Read more below.</li>
 * <li>The {@link MatsTestInfrastructureConfiguration @MatsTestInfrastructureConfiguration} is Meta-annotated with
 * {@link EnableMats @EnableMats}, since you pretty obviously need that, and have better things to do than to write it
 * in each test.</li>
 * </ul>
 * Beans that are put in the Spring test context:
 * <ul>
 * <li>A {@link MatsFactory} (making the {@literal @EnableMats} happy), created using
 * {@link TestSpringMatsFactoryProvider}.</li>
 * <li>The <code>MatsFactory</code>'s {@link MatsFactory#getDefaultInitiator() default} {@link MatsInitiator} to
 * initiate messages with.</li>
 * <li>A lazy-initialized {@link MatsFuturizer}, since this is often handy in tests.</li>
 * <li>A {@link MatsTestLatch} instance in the Spring Context, since when you need it, you need it both in the
 * {@literal @Configuration} class, and in the test-class - nice to already have an instance defined.</li>
 * </ul>
 * <p />
 * For some more background on the Context Caching: <a href=
 * "http://docs.spring.io/spring/docs/current/spring-framework-reference/html/integration-testing.html#testcontext-ctx-management-caching">
 * Read the Spring doc about Context Caching</a> and <a href="https://jira.spring.io/browse/SPR-7377">read a "wont-fix"
 * bug report describing how the contexts aren't destroyed due to caching</a>. Since we're setting up message queue
 * consumers on a (potentially) global Message Queue broker, the different contexts would mess each other up, the old
 * consumers still consuming messages that the new test setup expected its consumers to take. For the in-memory broker
 * case, a solution would be to set up a new message broker (with a new name) for each context (each instantiation of a
 * new Spring context), but this would not solve the situation where we ran the tests against an actual external MQ
 * (which {@link MatsLocalVmActiveMq} supports, read its JavaDoc!). Had there been some "deactivate"/"reactivate" events
 * that could be picked, we could have stopped the endpoints in one context (put in the cache) when firing up a new
 * context, and the restarted them when the context was reused from the cache. Since such a solution does not seem to be
 * viable, the only solution seems to be dirtying of the context, this stopping it from being cached.
 *
 * @see TestSpringMatsFactoryProvider
 * @see MatsTestDbContext
 * @see MatsTestInfrastructureConfiguration
 * @see MatsTestInfrastructureDbConfiguration
 * @author Endre Stølsvik - 2016-06-23 / 2016-08-07 - http://endre.stolsvik.com
 * @author Endre Stølsvik - 2020-11 - http://endre.stolsvik.com
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// @ContextConfiguration makes it possible to annotate the test class itself with this annotation
@ContextConfiguration(initializers = MatsSimpleTestInfrastructureContextInitializer.class)
// @Import makes it possible to annotate a @Configuration class with this annotation
@Import(MatsTestInfrastructureConfiguration.class)
// @DirtiesContext since most tests needs this.
@DirtiesContext(classMode = ClassMode.AFTER_CLASS) // AFTER_CLASS is also default, just pointing it out.
// @Documented is only for JavaDoc: The documentation will show that the class is annotated with this annotation.
@Documented
// Meta for @ActiveProfiles(MatsProfiles.PROFILE_MATS_TEST)
@MatsTestProfile
public @interface MatsTestContext {

    /**
     * The reason for this obscure way to add the {@link MatsTestInfrastructureConfiguration} (as opposed to just point
     * to it with "classes=..") is as follows: Spring's testing integration has this feature where any static
     * inner @Configuration class of the test class is automatically loaded. If we specify specify classes= or
     * location=, this default will be thwarted.
     * 
     * @see <a href=
     *      "https://docs.spring.io/spring-framework/docs/current/reference/html/testing.html#testcontext-ctx-management-javaconfig">
     *      Context Configuration with Component Classes</a>.
     */
    class MatsSimpleTestInfrastructureContextInitializer implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {
        // Use clogging, since that's what Spring does.
        private static final Log log = LogFactory.getLog(MatsSimpleTestInfrastructureContextInitializer.class);
        private static final String LOG_PREFIX = "#SPRINGMATS# ";

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            log.debug(LOG_PREFIX + "Registering " + MatsTestInfrastructureConfiguration.class.getSimpleName()
                    + " on: " + applicationContext);

            /*
             * Hopefully all the ConfigurableApplicationContexts presented here will also be a BeanDefinitionRegistry.
             * This at least holds for the default 'GenericApplicationContext'.
             */
            new AnnotatedBeanDefinitionReader((BeanDefinitionRegistry) applicationContext.getBeanFactory())
                    .register(MatsTestInfrastructureConfiguration.class);
        }
    }

}
