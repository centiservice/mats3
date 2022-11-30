package io.mats3.test.junit;

import javax.sql.DataSource;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.serial.MatsSerializer;
import io.mats3.test.TestH2DataSource;
import io.mats3.test.abstractunit.AbstractMatsTest;

/**
 * Similar to {@link Rule_Mats}, provides a full Mats harness for unit testing by creating {@link JmsMatsFactory
 * MatsFactory} utilizing an in-vm Active MQ broker. The difference between the two is that this Rule is open to the
 * usage of more customized {@link MatsSerializer}s.
 * <p>
 * {@link Rule_MatsGeneric} shall be considered a {@link org.junit.ClassRule} and thus annotated as such, being a
 * {@link org.junit.ClassRule} also means that the instance field shall be static. Therefore to utilize
 * {@link Rule_MatsGeneric} one should add it to a test class in this fashion:
 *
 * <pre>
 *     public class YourTestClass {
 *         &#64;ClassRule
 *         public static Rule_MatsGeneric&lt;Z&gt; mats = new Rule_MatsGeneric(new YourSerializer())
 *     }
 * </pre>
 *
 * This will ensure that Rule_MatsGeneric sets up the test harness correctly. However the factory needs to be cleaned in
 * between tests to ensure that there are no endpoint collisions (One can only register a given endpointId once per
 * MatsFactory), thus one should also implement a method within the test class which is annotated with
 * {@link org.junit.After} and executes a call to {@link Rule_MatsGeneric#cleanMatsFactories()} as such:
 *
 * <pre>
 * &#64;After
 * public void cleanFactory() {
 *     mats.removeAllEndpoints();
 * }
 * </pre>
 *
 * @param <Z>
 *            The type definition for the {@link MatsSerializer} employed. This defines the type which STOs and DTOs are
 *            serialized into. When employing JSON for the "outer" serialization of MatsTrace, it does not make that
 *            much sense to use a binary (Z=byte[]) "inner" representation of the DTOs and STOs, because JSON is
 *            terrible at serializing byte arrays.
 * @author Kevin Mc Tiernan, 2020-10-22, kmctiernan@gmail.com
 * @see AbstractMatsTest
 */
public class Rule_MatsGeneric<Z> extends AbstractMatsTest<Z> implements TestRule {

    protected Rule_MatsGeneric(MatsSerializer<Z> matsSerializer) {
        super(matsSerializer);
    }

    protected Rule_MatsGeneric(MatsSerializer<Z> matsSerializer, DataSource dataSource) {
        super(matsSerializer, dataSource);
    }

    public static <Z> Rule_MatsGeneric<Z> create(MatsSerializer<Z> matsSerializer) {
        return new Rule_MatsGeneric<>(matsSerializer);
    }

    public static <Z> Rule_MatsGeneric<Z> createWithDb(MatsSerializer<Z> matsSerializer) {
        TestH2DataSource testH2DataSource = TestH2DataSource.createStandard();
        return new Rule_MatsGeneric<>(matsSerializer, testH2DataSource);
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
            throw new IllegalStateException("The Rule_MatsGeneric should be applied as a @ClassRule, NOT as a @Rule");
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
