package io.mats3.test.junit;

import javax.sql.DataSource;

import org.junit.ClassRule;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.abstractunit.AbstractMatsTest;

/**
 * {@link ClassRule} which provides a full Mats harness for unit testing by creating {@link JmsMatsFactory MatsFactory}
 * utilizing an in-vm Active MQ broker, and optionally a {@link TestH2DataSource} for database tests.
 * <p/>
 * <b>Notice: If you are in a Spring-context, this is probably not what you are looking for</b>, as the MatsFactory then
 * should reside as a bean in the Spring context. Look in the 'mats-spring-test' package for testing tools for Spring.
 * <p/>
 * By default the {@link #create() rule} will create a {@link MatsSerializerJson} which will be the serializer utilized
 * by the created {@link JmsMatsFactory MatsFactory}. Should one want to use a different serializer which serializes to
 * the type of {@link String} then this can be specified using the method {@link #create(MatsSerializer)}.
 * <p/>
 * {@link Rule_Mats} shall be considered a {@link ClassRule} and thus annotated as such, being a {@link ClassRule} also
 * means that the instance field shall be static. Therefore to utilize {@link Rule_Mats} one should add it to a test
 * class in this fashion:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;ClassRule
 *         public static final Rule_Mats MATS = Rule_Mats.create() // Or provide your own serializer
 *     }
 * </pre>
 *
 * To get a variant that has a {@link TestH2DataSource} contained, and the MatsFactory set up with transactional
 * handling of that, use the {@link #createWithDb()} methods. In this case, you might want to clean the database before
 * each test method, which can be accomplished as such:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;ClassRule
 *         public static Rule_Mats MATS = Rule_Mats.createWithDb() // Or provide your own serializer
 *
 *         &#64;Before  // Will clean the database before each test - if this is what you want.
 *         public void cleanDatabase() {
 *             MATS.getDataSource().cleanDatabase()
 *         }
 *     }
 * </pre>
 *
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 * @author Kevin Mc Tiernan, 2020-10-18, kmctiernan@gmail.com
 */
public class Rule_Mats extends AbstractMatsTest implements TestRule {

    protected Rule_Mats(MatsSerializer<?> matsSerializer) {
        super(matsSerializer);
    }

    protected Rule_Mats(MatsSerializer<?> matsSerializer, DataSource dataSource) {
        super(matsSerializer, dataSource);
    }

    /**
     * Creates a {@link Rule_Mats} utilizing the {@link MatsSerializerJson Mats default serializer}
     */
    public static Rule_Mats create() {
        return new Rule_Mats(MatsSerializerJson.create());
    }

    /**
     * Creates a {@link Rule_Mats} utilizing the user provided {@link MatsSerializer} which serializes to the type of
     * String.
     */
    public static Rule_Mats create(MatsSerializer<?> matsSerializer) {
        return new Rule_Mats(matsSerializer);
    }

    public static Rule_Mats createWithDb() {
        return createWithDb(MatsSerializerJson.create());
    }

    public static Rule_Mats createWithDb(MatsSerializer<?> matsSerializer) {
        TestH2DataSource testH2DataSource = TestH2DataSource.createStandard();
        return new Rule_Mats(matsSerializer, testH2DataSource);
    }

    public TestH2DataSource getDataSource() {
        return (TestH2DataSource) super.getDataSource();
    }

    // ================== Junit LifeCycle =============================================================================

    /**
     * Note: Shamelessly inspired from: <a href="https://stackoverflow.com/a/48759584">How to combine @Rule
     * and @ClassRule in JUnit 4.12</a>
     */
    @Override
    public Statement apply(Statement base, Description description) {
        // :: Rule handling.
        if (description.isTest()) {
            throw new IllegalStateException("The Rule_Mats should be applied as a @ClassRule, NOT as a @Rule");
        }
        return new Statement() {
            public void evaluate() throws Throwable {
                beforeAll();
                try {
                    base.evaluate();
                }
                finally {
                    afterAll();
                }
            }
        };
    }
}
