package io.mats3.spring.test;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.PlatformTransactionManager;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.spring.EnableMats;
import io.mats3.test.MatsTestBrokerInterface;
import io.mats3.test.MatsTestLatch;
import io.mats3.util.MatsFuturizer;

/**
 * Spring {@link Configuration @Configuration} class that cooks up the simple test infrastructure, employing a
 * {@link MatsSerializer} from the Spring context if available, otherwise creates a default {@link MatsSerializerJson}.
 * <p />
 * There is very little magic with this convenience test infrastructure configuration, you could just as well have made
 * these beans yourself - just check the code!
 * <p />
 * Provided beans:
 * <ol>
 * <li>{@link MatsFactory}.</li>
 * <li>{@link MatsInitiator} from the MatsFactory.</li>
 * <li>{@link MatsTestBrokerInterface} that "hooks in" to the underlying MQ instance, providing (for now) DLQ access.</li>
 * <li>{@link MatsTestLatch} for convenience (if you need to signal from e.g. a Terminator to the @Test method.</li>
 * <li>{@link MatsFuturizer} (lazily created if needed), backed by the MatsFactory.</li>
 * </ol>
 * @author Endre Stølsvik - 2016-06-23 / 2016-08-07 - http://endre.stolsvik.com
 * @author Endre Stølsvik - 2020-11 - http://endre.stolsvik.com
 */
@EnableMats
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class MatsTestInfrastructureConfiguration  {

    // Optionally depend on MatsSerializer.
    @Autowired
    protected ObjectProvider<MatsSerializer> _matsSerializer;

    // Optionally depend on DataSource
    @Autowired
    protected ObjectProvider<DataSource> _dataSource;

    // Optionally depend on Spring PlatformTransactionManager
    @Autowired
    protected ObjectProvider<PlatformTransactionManager> _platformTransactionManagerObjectProvider;

    @Bean
    protected MatsFactory testMatsFactory() {
        // ?: Is there a MatsSerializer in the Spring context?
        MatsSerializer matsSerializer = _matsSerializer.getIfAvailable();
        if (matsSerializer == null) {
            // -> No, there was no MatsSerializer in the Spring context, so we make a standard from MatsSerializerJson.
            matsSerializer = MatsSerializerJson.create();
        }

        // ?: Is there a PlatformTransactionManager in the Spring context?
        PlatformTransactionManager platformTransactionManager = _platformTransactionManagerObjectProvider
                .getIfAvailable();
        if (platformTransactionManager != null) {
            // -> Yes, there is a PlatformTransactionManager in the Spring context, so use this to make the MatsFactory.
            return TestSpringMatsFactoryProvider.createSpringDataSourceTxTestMatsFactory(platformTransactionManager,
                    matsSerializer);
        }

        // E-> No, there was no PlatformTransactionManager in the Spring Context.

        // ?: Is there a DataSource in the Spring context?
        DataSource dataSource = _dataSource.getIfAvailable();
        if (dataSource != null) {
            // -> Yes, there is a DataSource in the Spring context, so use this to make the MatsFactory.
            return TestSpringMatsFactoryProvider.createSpringDataSourceTxTestMatsFactory(dataSource, matsSerializer);
        }

        // E-> No, neither PlatformTransactionManager nor DataSource in context, so make a non-DataSource tx MatsFactory
        return TestSpringMatsFactoryProvider.createJmsTxOnlyTestMatsFactory(matsSerializer);
    }

    @Bean
    protected MatsTestBrokerInterface testMatsTestMqInterface() {
        return MatsTestBrokerInterface.createForLaterPopulation();
    }

    @Bean
    protected MatsInitiator testMatsInitiator(MatsFactory matsFactory) {
        return matsFactory.getDefaultInitiator();
    }

    @Bean
    protected MatsTestLatch testMatsTestLatch() {
        return new MatsTestLatch();
    }

    @Bean
    @Lazy
    protected MatsFuturizer testMatsFuturizer(MatsFactory matsFactory) {
        return MatsFuturizer.createMatsFuturizer(matsFactory, "MatsSpringTest");
    }
}
