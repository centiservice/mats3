/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.test.jupiter;

import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Namespace NAMESPACE = Namespace.create(Extension_Mats.class.getName());

    protected Extension_Mats(MatsSerializer matsSerializer) {
        super(matsSerializer);
    }

    protected Extension_Mats(MatsSerializer matsSerializer, DataSource dataSource) {
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
    public static Extension_Mats create(MatsSerializer matsSerializer) {
        return new Extension_Mats(matsSerializer);
    }

    public static Extension_Mats createWithDb() {
        return createWithDb(MatsSerializerJson.create());
    }

    public static Extension_Mats createWithDb(MatsSerializer matsSerializer) {
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
     * In a scenario with nested classes, we only register the Extension_Mats in the top level class, where the
     * extension has been added with {@link org.junit.jupiter.api.extension.RegisterExtension}.
     *
     * @param extensionContext
     *            to get {@link Extension_Mats} from
     * @return the {@link Extension_Mats} from the test context
     * @throws IllegalStateException
     *             if no {@link Extension_Mats} is found in the test context
     */
    public static Optional<Extension_Mats> findFromContext(ExtensionContext extensionContext) {
        return Optional.ofNullable(extensionContext.getRoot().getStore(NAMESPACE)
                .get(Extension_Mats.class, Extension_Mats.class));
    }

    /**
     * Executed by Jupiter before any test method is executed. (Once at the start of the class.)
     */
    @Override
    public void beforeAll(ExtensionContext context) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "beforeAll: Context chain to root: "
                + getContextsToRoot(context));

        // Get root Extension context.
        // Note: The 0th level of tests actually still have a parent, which is the "engine" level.
        // This seems to be the root.
        ExtensionContext root = context.getRoot();

        // ?: Check if the Extension_Mats is already registered in the root context
        if (root.getStore(NAMESPACE).get(Extension_Mats.class, Extension_Mats.class) != null) {
            // -> Already present: We do not need to do anything
            log.info(LOG_PREFIX + "INIT SKIPPED: beforeAll on Jupiter @Extension " + idThis() + " for "
                    + id(context) + " - skipping since the root context already has Extension_Mats present.");
            return;
        }

        // E-> We do not have an Extension_Mats in the root context, so we do the init of this Extension_Mats.
        log.info(LOG_PREFIX + "INIT: beforeAll on Jupiter @Extension " + idThis() + " for " + id(context)
                + ", root is " + id(root) + " - setting up MQ Broker and JMS MatsFactory.");
        // Put in root context
        root.getStore(NAMESPACE).put(Extension_Mats.class, this);
        // Store that it was this ExtensionContext that put it in root context (Needed for removal)
        root.getStore(NAMESPACE).put(Extension_Mats.class.getName() + ".contextAdder", context);
        super.beforeAll();
        log.info(LOG_PREFIX + "-- init done: beforeAll on Jupiter @Extension " + idThis() + ", put it in root context "
                + id(root) + ".");
    }

    /**
     * Executed by Jupiter after all test methods have been executed. (Once at the end of the class.)
     */
    @Override
    public void afterAll(ExtensionContext context) {
        if (log.isDebugEnabled()) log.debug(LOG_PREFIX + "afterAll: Context chain to root: "
                + getContextsToRoot(context));

        // Get root Extension context
        ExtensionContext root = context.getRoot();

        // Get which ExtensionContext (i.e. "level") that put the Extension_Mats in the root context
        ExtensionContext contextThatPutItInRoot = root.getStore(NAMESPACE).get(Extension_Mats.class.getName()
                + ".contextAdder", ExtensionContext.class);

        // ?: Was it the current ExtensionContext that put the Extension_Mats in the root context?
        if (contextThatPutItInRoot != context) {
            // -> No, it was not us, so skip the cleanup.
            log.info(LOG_PREFIX + "CLEANUP SKIPPED: afterAll on Jupiter @Extension " + idThis() + " for "
                    + id(context) + " - this level didn't initialize it.");
            return;
        }

        // E-> Yes, we are the root context, so we do the afterAll
        log.info(LOG_PREFIX + "CLEANUP: afterAll on Jupiter @Extension " + idThis() + " for " + id(context)
                + ", root is " + id(root) + " - taking down JMS MatsFactory and MQ Broker.");
        super.afterAll();
        root.getStore(NAMESPACE).remove(Extension_Mats.class);
        log.info(LOG_PREFIX + "-- cleanup done: afterAll on Jupiter @Extension " + idThis() + ".");
    }

    // Contexts to root, but using a StringBuffer for the output, and SimpleName
    private String getContextsToRoot(ExtensionContext context) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (context != null) {
            if (first) {
                first = false;
            }
            else {
                sb.append(" -> ");
            }
            sb.append(id(context));
            context = context.getParent().orElse(null);
        }
        return sb.toString();
    }
}
