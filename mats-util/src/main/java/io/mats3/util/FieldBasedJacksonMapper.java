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

package io.mats3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.json.JsonMapper.Builder;

/**
 * Configures a Jackson JSON field-based ObjectMapper to be used in Mats3, ensuring a common standard configuration.
 * <p>
 * The ObjectMapper is configured to be as lenient and compact as possible. The configuration is as such:
 * <ul>
 * <li>Only read and write fields, ignore methods and constructors (except for Records, which Jackson handles somewhat
 * differently).</li>
 * <li>Read and write any access modifier fields (private, package, public)</li>
 * <li>If the JSON have a value that does not map to a field in the DTO, do not fail</li>
 * <li>Drop nulls from JSON</li>
 * <li>Write times and dates using Strings of ISO-8601, e.g "1975-03-11" instead of millis-since-epoch or array-of-ints
 * [1975, 3, 11]</li>
 * <li>Handle Optional, OptionalLong, OptionalDouble</li>
 * <li>Handles Java Records</li>
 * <li>Make the security constraints when reading JSON 10x lenient, i.e. nesting level, number length and string length
 * to high values. (In particular the accepted string length must be very high, since the DTOs and STOs are "doubly
 * serialized" and will be present in the MatsTrace DTO as Strings.)</li>
 * </ul>
 * Note: If Jackson's Blackbird Module is on the classpath, it will be used. It is a moderate performance improvement,
 * in extreme cases up to 20% faster on the deserialization side. The detection logic can be overridden using system
 * property "mats.jackson.useBlackbird", setting it to "true" or "false" before this class is loaded - if true, and the
 * module is not on the classpath, it will throw an exception. Add it to your project with:<br>
 * <code>implementation "tools.jackson.module:jackson-module-blackbird:${jacksonVersion}"</code>
 * <p>
 * Thread-safety: The returned ObjectMappers are thread-safe, meant for sharing.
 */
public class FieldBasedJacksonMapper {
    private static final Logger log = LoggerFactory.getLogger(FieldBasedJacksonMapper.class);

    private static final Class<?> __blackbirdModuleClass;
    private static final boolean __useBlackbird;
    static {
        // Check if 'tools.jackson.module.blackbird.BlackbirdModule' is on the classpath, and if so, store
        // the class (since we need to instantiate it reflectively), and decide on the __useBlackbird flag.
        Class<?> blackbirdModuleClass;
        try {
            blackbirdModuleClass = Class.forName("tools.jackson.module.blackbird.BlackbirdModule");

        }
        catch (ClassNotFoundException e) {
            blackbirdModuleClass = null;
        }
        __blackbirdModuleClass = blackbirdModuleClass;

        // Logic: Defaults to true if Blackbird is on the classpath - but can be overridden by system property.
        __useBlackbird = System.getProperty("mats.jackson.useBlackbird",
                blackbirdModuleClass != null ? "true" : "false").equalsIgnoreCase("true");
    }

    // "Initialization-on-demand holder idiom", using a static inner class to hold the singleton instance.
    private static class SingletonObjectMapperHolder {
        private static final ObjectMapper INSTANCE = internalJacksonObjectMapper(__useBlackbird,
                "Creating default Mats3 singleton");
    }

    /**
     * Returns the singleton ObjectMapper used by all Mats3 components - <b>You must not further configure this
     * ObjectMapper instance, as it is shared by all Mats3 components.</b>
     * <p>
     * Thread-safety: It is thread-safe, meant for sharing.
     *
     * @return the default ObjectMapper used by Mats3 components - <b>do not mess with this!</b>
     */
    public static ObjectMapper getMats3DefaultJacksonObjectMapper() {
        return SingletonObjectMapperHolder.INSTANCE;
    }

    /**
     * Creates a new Jackson ObjectMapper configured exactly the same as the default ObjectMapper used by Mats3
     * components - <b>Note that it is imperative that you do not create a new ObjectMapper for each JSON serialization
     * or deserialization</b>, as this is an expensive operation, but worse, it will - according to documentation for
     * the Blackbird module - lead to a memory leak.
     * <p>
     * Thread-safety: It is thread-safe, meant for sharing.
     *
     * @return a new Jackson ObjectMapper configured as if for Mats3.
     */
    public static ObjectMapper createJacksonObjectMapper() {
        return internalJacksonObjectMapper(__useBlackbird, "Instantiating new");
    }

    private static ObjectMapper internalJacksonObjectMapper(boolean useBlackbird, String sayWhat) {
        // Use StackWalker to get the caller's stack frame
        StackWalker walker = StackWalker.getInstance();
        StackWalker.StackFrame callerFrame = walker.walk(stream -> stream.skip(1)
                .filter(f -> !f.getClassName().contains(".FieldBasedJacksonMapper"))
                .findFirst().orElse(null));

        String callerInfo;
        if (callerFrame != null) {
            String callingClassName = callerFrame.getClassName();
            String callingMethodName = callerFrame.getMethodName();
            callerInfo = callingClassName + "." + callingMethodName;
        }
        else {
            callerInfo = "Unknown";
        }

        // Much larger constraints, and make max string length effectively infinite.
        StreamReadConstraints streamReadConstraints = StreamReadConstraints
                .builder()
                .maxNestingDepth(10_000) // default 1000 (from 3.0: 500)
                .maxNumberLength(100_000) // default 1000
                .maxStringLength(Integer.MAX_VALUE)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(streamReadConstraints)
                .build();

        Builder builder = JsonMapper.builder(factory);

        String currentJavaVersion = System.getProperty("java.version");
        if (useBlackbird) {
            log.info(sayWhat + " Jackson JsonMapper, USING Jackson Blackbird Module! (Java: "
                    + currentJavaVersion + ", caller: " + callerInfo + ")");
            if (__blackbirdModuleClass == null) {
                throw new IllegalStateException("You have requested to use Jackson Blackbird Module, but it is not on"
                        + " the classpath. Add it to your project with: "
                        + "implementation \"tools.jackson.module:jackson-module-blackbird:${jacksonVersion}\"");
            }
            JacksonModule blackbirdModule;
            try {
                blackbirdModule = (JacksonModule) __blackbirdModuleClass
                        .getDeclaredConstructor().newInstance();
                builder.addModule(blackbirdModule);
            }
            catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        else {
            log.info(sayWhat + " Jackson JsonMapper, NOT using Jackson Blackbird Module. (Java: "
                    + currentJavaVersion + ", caller: " + callerInfo + ")");
        }

        // Drop null values from JSON
        // Had a hope to use NON_DEFAULT, but that didn't pan out: If a field is 'Integer', then with NON_DEFAULT it
        // would not be serialized if it was Integer.of(0): Because that's 0, and considered "default".
        // But the point of using Integer was the "nullable int", in that NOT having it set is different from 0.
        builder.changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL));
        // Read and write any access modifier fields (e.g. private)
        builder.changeDefaultVisibility(vc -> vc.with(Visibility.NONE).withFieldVisibility(Visibility.ANY));
        // Allow final fields to be written to.
        builder.enable(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS);

        // If props are in JSON that aren't in Java DTO, do not fail.
        builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // :: Dates:
        // Write times and dates using Strings of ISO-8601.
        builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Tack on "[Europe/Oslo]" if present in a ZoneDateTime
        builder.enable(DateTimeFeature.WRITE_DATES_WITH_ZONE_ID);
        // Do not OVERRIDE (!!) the timezone id if it actually is present!
        builder.disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

        return builder.build();
    }
}
