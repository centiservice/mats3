package io.mats3.test.jupiter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.abstractunit.AbstractMatsTest;
import io.mats3.util.MatsFuturizer;

/**
 * Provides a full MATS harness for unit testing by creating {@link JmsMatsFactory MatsFactory} utilizing an in-vm
 * Active MQ broker, and optionally a {@link TestH2DataSource} for database tests.
 * <p/>
 * <b>Notice: If you are in a Spring-context, this is probably not what you are looking for</b>, as the MatsFactory then
 * should reside as a bean in the Spring context. Look in the 'mats-spring-test' package for testing tools for Spring.
 * <p/>
 * By default the {@link #create() extension} will create a {@link MatsSerializerJson} which will be the serializer
 * utilized by the created {@link JmsMatsFactory MatsFactory}. Should one want to use a different serializer then this
 * can be specified using the method {@link #create(MatsSerializer)}.
 * <p/>
 * {@link Extension_Mats} shall be annotated with
 * {@link org.junit.jupiter.api.extension.RegisterExtension @RegisterExtension} and the instance field shall be static
 * for the Jupiter life cycle to pick up the extension at the correct time. {@link Extension_Mats} can be viewed in the
 * same manner as one would view a ClassRule in JUnit4.
 * <p/>
 * Example:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;RegisterExtension
 *         public static final Extension_Mats MATS = Extension_Mats.createRule()
 *     }
 * </pre>
 *
 * To get a variant that has a {@link TestH2DataSource} contained, and the MatsFactory set up with transactional
 * handling of that, use the {@link #createWithDb()} methods. In this case, you might want to clean the database before
 * each test method, which can be accomplished as such:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;RegisterExtension
 *         public static final Extension_Mats MATS = Extension_Mats.createRule()
 *
 *         &#64;Before  // Will clean the database before each test - if this is what you want.
 *         public void cleanDatabase() {
 *             MATS.getDataSource().cleanDatabase()
 *         }
 *     }
 * </pre>
 *
 * @author Kevin Mc Tiernan, 2020-10-18, kmctiernan@gmail.com
 */
public class Extension_Mats extends AbstractMatsTest implements BeforeAllCallback, AfterAllCallback {
    private static final Logger log = LoggerFactory.getLogger(Extension_Mats.class);

    private static final Namespace NAMESPACE = Namespace.create(Extension_Mats.class.getSimpleName());

    protected Extension_Mats(MatsSerializer<?> matsSerializer) {
        super(matsSerializer);
    }

    protected Extension_Mats(MatsSerializer<?> matsSerializer, DataSource dataSource) {
        super(matsSerializer, dataSource);
    }

    /**
     * Creates an {@link Extension_Mats} utilizing the {@link MatsSerializerJson MATS default serializer}
     */
    public static Extension_Mats create() {
        return new Extension_Mats(MatsSerializerJson.create());
    }

    /**
     * Creates an {@link Extension_Mats} utilizing the user provided {@link MatsSerializer} which serializes to the type
     * of String.
     */
    public static Extension_Mats create(MatsSerializer<?> matsSerializer) {
        return new Extension_Mats(matsSerializer);
    }

    public static Extension_Mats createWithDb() {
        return createWithDb(MatsSerializerJson.create());
    }

    public static Extension_Mats createWithDb(MatsSerializer<?> matsSerializer) {
        TestH2DataSource testH2DataSource = TestH2DataSource.createStandard();
        return new Extension_Mats(matsSerializer, testH2DataSource);
    }

    /**
     * Returns the {@link Extension_Mats} from the test context, provided that this has been initialized prior to
     * calling this method. This is intended for use by other extensions that rely on the presence of a
     * {@link Extension_Mats} to function. The {@link Extension_Mats} is not set in the {@link ExtensionContext} until
     * after the {@link #beforeAll(ExtensionContext)} has been called for an instance of {@link Extension_Mats}.
     * <p>
     * Note that if you crate multiple {@link Extension_Mats}, then this will only provide the last created extension.
     * In that case, you should instead provide the actual MatsFactory to each extension.
     * <p>
     * In a scenario with nested classes, we only register the Extension_Mats in the top level class, where
     * the extension has been added with {@link org.junit.jupiter.api.extension.RegisterExtension}.
     *
     * @param extensionContext
     *            to get {@link Extension_Mats} from
     * @return the {@link Extension_Mats} from the test context
     * @throws IllegalStateException
     *             if no {@link Extension_Mats} is found in the test context
     */
    public static Extension_Mats getExtension(ExtensionContext extensionContext) {
        Extension_Mats extensionMats = extensionContext.getStore(NAMESPACE).get(Extension_Mats.class,
                Extension_Mats.class);
        if (extensionMats == null) {
            // If we have a parent context, we should get the Extension_Mats from there
            return extensionContext.getParent()
                    .map(Extension_Mats::getExtension)
                    .orElseThrow(() -> new IllegalStateException("Could not find Extension_Mats in ExtensionContext,"
                            + " make sure to include Extension_Mats as a test extension."));
        }
        return extensionMats;
    }

    /**
     * Checks if the {@link Extension_Mats} is registered in the test context already.
     *
     * @param context current test context
     * @return true if the {@link Extension_Mats} is registered, false otherwise
     */
    public static boolean isExtensionRegistered(ExtensionContext context) {
        // Check if the Extension_Mats is registered in the current context or any parent context
        return context.getStore(NAMESPACE).get(Extension_Mats.class, Extension_Mats.class) != null
                || context.getParent().filter(Extension_Mats::isExtensionRegistered).isPresent();
    }

    /**
     * Executed by Jupiter before any test method is executed. (Once at the start of the class.)
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        // Does the parent context have an Extension_Mats registered?
        if (context.getParent().filter(Extension_Mats::isExtensionRegistered).isPresent()) {
            // Yes, the parent context has Extension_Mats, so we do not do the beforeAll.
            log.debug("+++ Jupiter +++ beforeAll(..) invoked, ignoring since the parent context has Extension_Mats");
            return;
        }

        // -> No, this is the top level wrt. Extension_Mats, so register ourselves and do beforeAll.
        context.getStore(NAMESPACE).put(Extension_Mats.class, this);
        super.beforeAll();
    }

    /**
     * Executed by Jupiter after all test methods have been executed. (Once at the end of the class.)
     */
    @Override
    public void afterAll(ExtensionContext context) {
        // Does the parent context have an Extension_Mats registered?
        if (context.getParent().filter(Extension_Mats::isExtensionRegistered).isPresent()) {
            // -> Yes, the parent context has Extension_Mats, so we do not do the afterAll.
            log.debug("--- Jupiter --- afterAll(..) invoked, ignoring since the parent context has Extension_Mats");
            return;
        }
        // -> No, this is the top level, and we do the afterAll
        super.afterAll();
        context.getStore(NAMESPACE).remove(Extension_Mats.class);
    }
}
