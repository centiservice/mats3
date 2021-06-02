package io.mats3.spring.jms.factories;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import io.mats3.spring.jms.factories.ScenarioConnectionFactoryWrapper.ScenarioDecider;

/**
 * Configurable {@link ScenarioDecider}, whose defaults implements the logic described in
 * {@link ScenarioConnectionFactoryProducer} and handles all the Spring Profiles specified in {@link MatsProfiles}.
 */
public class ConfigurableScenarioDecider implements ScenarioDecider {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableScenarioDecider.class);
    private static final String LOG_PREFIX = "#SPRINGJMATS# ";

    /**
     * Configures a {@link ScenarioDecider} that implements the logic described in
     * {@link ScenarioConnectionFactoryProducer} and handles all the Spring Profiles specified in {@link MatsProfiles}.
     * 
     * @return a configured {@link ScenarioDecider}
     */
    public static ConfigurableScenarioDecider createDefaultScenarioDecider() {
        return new ConfigurableScenarioDecider(
                new StandardSpecificScenarioDecider(MatsProfiles.PROFILE_MATS_REGULAR, MatsProfiles.PROFILE_PRODUCTION,
                        MatsProfiles.PROFILE_STAGING),
                new StandardSpecificScenarioDecider(MatsProfiles.PROFILE_MATS_LOCALHOST),
                new StandardSpecificScenarioDecider(MatsProfiles.PROFILE_MATS_LOCALVM,
                        MatsProfiles.PROFILE_MATS_TEST),
                () -> {
                    throw new IllegalStateException("No MatsScenario was decided - you must make a decision!"
                            + " Please read JavaDoc at " + ScenarioConnectionFactoryProducer.class.getSimpleName()
                            + " and " + ConfigurableScenarioDecider.class.getSimpleName() + ".");
                });
    }

    protected SpecificScenarioDecider _regular;
    protected SpecificScenarioDecider _localhost;
    protected SpecificScenarioDecider _localVm;
    protected Supplier<MatsScenario> _defaultScenario;

    /**
     * Takes a {@link SpecificScenarioDecider} for each of the {@link MatsScenario}s, and a default Supplier of
     * MatsScenario if none of the SpecificScenarioDeciders kicks in - notice that it makes sense that the default
     * instead of providing a MatsScenario instead throws an e.g. {@link IllegalStateException} (this is what the
     * {@link ScenarioDecider} from {@link #createDefaultScenarioDecider()} does).
     */
    public ConfigurableScenarioDecider(SpecificScenarioDecider regular, SpecificScenarioDecider localhost,
            SpecificScenarioDecider localVm, Supplier<MatsScenario> defaultScenario) {
        setRegularDecider(regular);
        setLocalhostDecider(localhost);
        setLocalVmDecider(localVm);
        setDefaultScenario(defaultScenario);
    }

    /**
     * No-args constructor - must set all the {@link SpecificScenarioDecider} and default MatsScenario by setters.
     */
    public ConfigurableScenarioDecider() {
    }

    public ConfigurableScenarioDecider setRegularDecider(SpecificScenarioDecider regular) {
        _regular = regular;
        return this;
    }

    public ConfigurableScenarioDecider setLocalhostDecider(SpecificScenarioDecider localhost) {
        _localhost = localhost;
        return this;
    }

    public ConfigurableScenarioDecider setLocalVmDecider(SpecificScenarioDecider localVm) {
        _localVm = localVm;
        return this;
    }

    public ConfigurableScenarioDecider setDefaultScenario(Supplier<MatsScenario> defaultScenario) {
        _defaultScenario = defaultScenario;
        return this;
    }

    @Override
    public MatsScenario decision(Environment env) {
        String envString = "Active Spring Profiles: " + Arrays.asList(env.getActiveProfiles());
        if (env instanceof ConfigurableEnvironment) {
            ConfigurableEnvironment configurableEnvironment = (ConfigurableEnvironment) env;
            MutablePropertySources propertySources = configurableEnvironment.getPropertySources();
            Stream<PropertySource<?>> stream = StreamSupport.stream(propertySources.spliterator(), false);
            envString += ", Spring Environment instanceof ConfigurableEnvironment ("
                    + env.getClass().getSimpleName() + "); PropertySources: "
                    + stream.map(source -> source.getClass().getSimpleName() + "{" + source.getName() + "}")
                            .collect(Collectors.joining(", ", "[", "]"));
        }
        else {
            envString += ", Spring Environment !instanceOf ConfigurableEnvironment, env.toString(): " + env;
        }
        log.info(LOG_PREFIX + "Finding which MatsScenario is active, " + envString);

        int activeScenarios = 0;
        // :: Find which MatsScenario is active
        MatsScenario scenario = null;
        String match;
        if ((match = _regular.scenarioActive(env).orElse(null)) != null) {
            scenario = MatsScenario.REGULAR;
            activeScenarios++;
            log.info(LOG_PREFIX + "  \\- " + match + ": choosing MatsScenario '" + scenario + "'");
        }
        if ((match = _localhost.scenarioActive(env).orElse(null)) != null) {
            scenario = MatsScenario.LOCALHOST;
            activeScenarios++;
            log.info(LOG_PREFIX + "  \\- " + match + ": choosing MatsScenario '" + scenario + "'");
        }
        if ((match = _localVm.scenarioActive(env).orElse(null)) != null) {
            scenario = MatsScenario.LOCALVM;
            activeScenarios++;
            log.info(LOG_PREFIX + "  \\- " + match + ": choosing MatsScenario '" + scenario + "'");
        }
        // ?: Was no scenario decided?
        if (scenario == null) {
            // -> No scenario was decided, so use the default scenario (which default (!) throws IllegalState..)
            log.info(LOG_PREFIX
                    + "  \\- NO Scenario explicitly specified - invoking the default MatsScenario Supplier.");
            scenario = _defaultScenario.get();
        }
        // ?: If more than one Mats MatsScenario is active, throw.
        if (activeScenarios > 1) {
            throw new IllegalStateException("When trying to find which Mats MatsScenario was active, we found that"
                    + " more than one scenario was active - this is not allowed.\n" + envString);
        }

        // ?: If MatsScenario.REGULAR is active, then mocks shall NOT be active!
        if (scenario == MatsScenario.REGULAR) {
            // -> Yes, it is REGULAR, so check for any "mats-mocks" profile active
            Optional<String> mockProfile = Arrays.stream(env.getActiveProfiles())
                    .filter(profile -> profile.toLowerCase().startsWith(MatsProfiles.PROFILE_MATS_MOCKS)).findAny();
            if (mockProfile.isPresent()) {
                throw new IllegalStateException("Found that Mats Scenario [" + scenario
                        + "] was active, but at the same time, we found that '" + mockProfile.get()
                        + "' profile was active. This is not allowed.\n" + envString);
            }
        }

        // ----- We have found the active Mats Scenario, and we've checked that if REGULAR, then no mats-mocks.

        return scenario;
    }

    /**
     * An implementation of this interface can decide whether a specific Mats Scenario is active. The Spring
     * {@link Environment} is provided from which Spring Profiles and properties/variables can be gotten. Notice that in
     * the default Spring configuration, the Environment is populated by System Properties (Java command line
     * "-Dproperty=vale"-properties) and System Environment. However, an implementation of this interface might use
     * whatever it find relevant to do a decision.
     * 
     * @see StandardSpecificScenarioDecider
     */
    @FunctionalInterface
    public interface SpecificScenarioDecider {
        /**
         * Decides whether a specific Scenario is active.
         * 
         * @param env
         *            the Spring {@link Environment}, from which Spring Profiles and properties/variables can be gotten.
         *            Notice that in the default Spring configuration, the Environment is populated by System Properties
         *            (Java command line "-Dproperty=value"-properties) and System Environment.
         * @return an {@link Optional}, which if present means that the specific Mats Scenario is active - and the
         *         returned String is used in logging to show why this Scenario was chosen (a string like e.g.
         *         <code>"Found active Spring Profile 'mats-test'"</code> would make sense). If
         *         {@link Optional#empty()}, this specific Mats Scenario was not active.
         */
        Optional<String> scenarioActive(Environment env);
    }

    /**
     * Standard implementation of {@link SpecificScenarioDecider} used in the default configuration of
     * {@link ConfigurableScenarioDecider}, which takes a set of profile-or-properties names and checks whether they are
     * present as a Spring Profile, or (with the "-" replaced by ".") whether it exists as a property in the Spring
     * Environment.
     */
    public static class StandardSpecificScenarioDecider implements SpecificScenarioDecider {
        private final String[] _profileOrPropertyNames;

        public StandardSpecificScenarioDecider(String... profileOrPropertyNames) {
            _profileOrPropertyNames = profileOrPropertyNames;
        }

        @Override
        public Optional<String> scenarioActive(Environment env) {
            return isProfileOrPropertyPresent(env, _profileOrPropertyNames);
        }

        public static Optional<String> isProfileOrPropertyPresent(Environment env, String... profileNames) {
            for (String profileName : profileNames) {
                if (env.containsProperty(profileName)) {
                    return Optional.of("Found Spring Environment Property '" + profileName + "'");
                }
                if (env.containsProperty(profileName.replace('-', '.'))) {
                    return Optional.of("Found Spring Environment Property '" + profileName.replace('-', '.') + "'");
                }
                if (env.acceptsProfiles(profileName)) {
                    return Optional.of("Found active Spring Profile '" + profileName + "'");
                }
            }
            return Optional.empty();
        }
    }
}
