package io.mats3.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Configures a Jackson JSON field-based ObjectMapper to be used in Mats3, ensuring a common standard configuration.
 * <p>
 * The ObjectMapper is configured be as lenient and compact as possible. The configuration is as such:
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
 */
public class FieldBasedJacksonMapper {
    /**
     * Used by all Mats3 components to get a Jackson ObjectMapper.
     * 
     * @return a Jackson ObjectMapper configured for Mats3.
     */
    public static ObjectMapper createJacksonObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Read and write any access modifier fields (e.g. private)
        mapper.setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

        // Drop nulls
        mapper.setSerializationInclusion(Include.NON_NULL);

        // If props are in JSON that aren't in Java DTO, do not fail.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Write e.g. Dates as "1975-03-11" instead of timestamp, and instead of array-of-ints [1975, 3, 11].
        // Uses ISO8601 with milliseconds and timezone (if present).
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        // Handle Optional, OptionalLong, OptionalDouble
        mapper.registerModule(new Jdk8Module());

        // 10x constraints, and make max string length effectively infinite.
        StreamReadConstraints streamReadConstraints = StreamReadConstraints
                .builder()
                .maxNestingDepth(10000) // default 1000
                .maxNumberLength(10000) // default 1000
                .maxStringLength(Integer.MAX_VALUE)
                .build();
        mapper.getFactory().setStreamReadConstraints(streamReadConstraints);

        return mapper;
    }
}
