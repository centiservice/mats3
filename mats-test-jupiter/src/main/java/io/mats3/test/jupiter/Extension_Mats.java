package io.mats3.test.jupiter;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.abstractunit.AbstractMatsTest;

/**
 * Provides a full MATS harness for unit testing by creating {@link JmsMatsFactory MatsFactory} utilizing an in-vm
 * Active MQ broker, and optionally a {@link TestH2DataSource} for database tests.
 * <p/>
 * <b>Notice: If you are in a Spring-context, this is probably not what you are looking for</b>, as the MatsFactory then
 * should reside as a bean in the Spring context. Look in the 'mats-spring-test' package for testing tools for Spring.
 * <p/>
 * By default the {@link #create() extension} will create a {@link MatsSerializerJson} which will be the serializer
 * utilized by the created {@link JmsMatsFactory MatsFactory}. Should one want to use a different serializer which
 * serializes to the type of {@link String} then this can be specified using the method {@link #create(MatsSerializer)}.
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
public class Extension_Mats extends AbstractMatsTest
        implements BeforeAllCallback, AfterAllCallback {

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
     * Executed by Jupiter before any test method is executed. (Once at the start of the class.)
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        super.beforeAll();
    }

    /**
     * Executed by Jupiter after all test methods have been executed. (Once at the end of the class.)
     */
    @Override
    public void afterAll(ExtensionContext context) {
        super.afterAll();
    }

}
