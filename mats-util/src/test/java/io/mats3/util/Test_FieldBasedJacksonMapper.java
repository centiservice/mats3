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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;

/**
 * Testing some corners wrt. DateTimes, null, default value and Optional.empty() values.
 *
 * Note: The tests in 'TestMatsTraceTimestamps', running the MatsSerializerJson with MatsTraceFieldImpl are implicitly
 * a bit more exhaustive wrt. object trees.
 */
public class Test_FieldBasedJacksonMapper {
    private static final Logger log = LoggerFactory.getLogger(Test_FieldBasedJacksonMapper.class);
    /**
     * DTO used for the next tests.
     */
    @SuppressWarnings("overrides")
    static class ClassTest {
        String string;
        int integer;

        public ClassTest() {
            // Jackson requires a no-args constructor.
        }

        public ClassTest(String string, int integer) {
            this.string = string;
            this.integer = integer;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ClassTest testClass = (ClassTest) o;
            return integer == testClass.integer && Objects.equals(string, testClass.string);
        }

        @Override
        public String toString() {
            return "ClassTest{" +
                    "string='" + string + '\'' +
                    ", integer=" + integer +
                    '}';
        }
    }

    @Test
    public void straight_back_and_forth() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        ClassTest classTest = new ClassTest("endre", 1);

        System.out.println("ClassTest:              " + classTest);
        String json = mapper.writeValueAsString(classTest);
        System.out.println("JSON serialized:         " + json);
        // Since Jackson 3.0, the order of fields is deterministically alphabetical.
        Assert.assertEquals("{\"integer\":1,\"string\":\"endre\"}", json);

        ClassTest ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(classTest, ClassTest_deserialized);
    }

    @Test
    public void missingJsonField() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Missing the string
        String json = "{\"integer\":1}";

        ClassTest ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);

        // Missing the integer
        json = "{\"string\":\"endre\"}";

        ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 0), ClassTest_deserialized);
    }

    @Test
    public void extraJsonField() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Extra fields
        String json = "{\"string\":\"endre\", \"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 1), ClassTest_deserialized);

        // Missing the string, but also extra fields
        json = "{\"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);
    }

    @Test
    public void emptyJsonToClass() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        // Empty JSON
        String json = "{}";

        ClassTest ClassTest_deserialized = mapper.readValue(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 0), ClassTest_deserialized);
    }


    /**
     * DTO used for the next tests.
     */
    @SuppressWarnings("overrides")
    static class DateAndDateTime {
        LocalDate date;
        LocalTime time;
        LocalDateTime dateTime;
        ZonedDateTime zoneDateTime;
        Duration duration;

        public DateAndDateTime() {
            // Jackson requires a no-args constructor.
        }

        public DateAndDateTime(LocalDate date, LocalTime time, LocalDateTime dateTime, ZonedDateTime zoneDateTime, Duration duration) {
            this.date = date;
            this.time = time;
            this.dateTime = dateTime;
            this.zoneDateTime = zoneDateTime;
            this.duration = duration;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            DateAndDateTime that = (DateAndDateTime) o;
            return Objects.equals(date, that.date) && Objects.equals(time, that.time) && Objects.equals(dateTime, that.dateTime) && Objects.equals(zoneDateTime, that.zoneDateTime) && Objects.equals(duration, that.duration);
        }

        @Override
        public String toString() {
            return "DateAndDateTime{" +
                    "date=" + date +
                    ", time=" + time +
                    ", dateTime=" + dateTime +
                    ", zoneDateTime=" + zoneDateTime +
                    ", duration=" + duration +
                    '}';
        }
    }

    @Test
    public void dateAndDateTime1() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        LocalDate date = LocalDate.of(2025, 1, 1);
        LocalTime time = LocalTime.of(1, 1, 1);
        LocalDateTime dateTime = LocalDateTime.of(2025, 1, 1, 1, 1, 1, 1);
        ZonedDateTime zoneDateTime = ZonedDateTime.of(2025, 1, 1, 1, 1, 1, 1, ZoneId.of("Europe/Oslo"));
        Duration duration = Duration.ofSeconds(1);

        DateAndDateTime dateAndDateTime = new DateAndDateTime(date, time, dateTime, zoneDateTime, duration);
        log.info("DateAndDateTime original: " + dateAndDateTime);

        String json = mapper.writeValueAsString(dateAndDateTime);
        log.info("JSON serialized:         " + json);
        Assert.assertEquals("{\"date\":\"2025-01-01\",\"dateTime\":\"2025-01-01T01:01:01.000000001\",\"duration\":\"PT1S\",\"time\":\"01:01:01\",\"zoneDateTime\":\"2025-01-01T01:01:01.000000001+01:00[Europe/Oslo]\"}", json);

        DateAndDateTime deserialized = mapper.readValue(json, DateAndDateTime.class);
        log.info("DateAndDateTime deserialized: " + deserialized);
        Assert.assertEquals(dateAndDateTime, deserialized);
    }

    @Test
    public void dateAndDateTime2() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        LocalDate date = LocalDate.of(1975, Month.MARCH, 11);
        LocalTime time = LocalTime.of(23, 59, 59, 999999999);
        LocalDateTime dateTime = LocalDateTime.of(2025, Month.MAY, 15, 23, 59);
        ZonedDateTime zoneDateTime = ZonedDateTime.of(2025, 4, 20, 1, 2, 3, 999999999, ZoneOffset.UTC);
        Duration duration = Duration.ofSeconds(1);

        DateAndDateTime dateAndDateTime = new DateAndDateTime(date, time, dateTime, zoneDateTime, duration);
        log.info("DateAndDateTime original: " + dateAndDateTime);

        String json = mapper.writeValueAsString(dateAndDateTime);
        log.info("JSON serialized:         " + json);
        Assert.assertEquals("{\"date\":\"1975-03-11\",\"dateTime\":\"2025-05-15T23:59:00\",\"duration\":\"PT1S\",\"time\":\"23:59:59.999999999\",\"zoneDateTime\":\"2025-04-20T01:02:03.999999999Z\"}", json);

        DateAndDateTime deserialized = mapper.readValue(json, DateAndDateTime.class);
        log.info("DateAndDateTime deserialized: " + deserialized);
        Assert.assertEquals(dateAndDateTime, deserialized);
    }

    /**
     * DTO used for the next tests.
     */
    @SuppressWarnings("overrides")
    static class WrappersAndOptional {
        int i;
        Integer integer;
        Optional<String> optionalString;

        public WrappersAndOptional() {
            // Jackson requires a no-args constructor.
        }

        public WrappersAndOptional(int i, Integer integer, Optional<String> optionalString) {
            this.i = i;
            this.integer = integer;
            this.optionalString = optionalString;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            WrappersAndOptional that = (WrappersAndOptional) o;
            return i == that.i && Objects.equals(integer, that.integer) && Objects.equals(optionalString, that.optionalString);
        }

        @Override
        public String toString() {
            return "WrappersAndOptional{" +
                    "i=" + i +
                    ", integer=" + integer +
                    ", optionalString=" + optionalString +
                    '}';
        }
    }

    @Test
    public void wrappersAndOptional_Set() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        WrappersAndOptional wrappersAndOptional = new WrappersAndOptional(1, 2, Optional.of("3"));
        log.info("WrappersAndOptional original: " + wrappersAndOptional);

        String json = mapper.writeValueAsString(wrappersAndOptional);
        log.info("JSON serialized:         " + json);
        Assert.assertEquals("{\"i\":1,\"integer\":2,\"optionalString\":\"3\"}", json);

        WrappersAndOptional deserialized = mapper.readValue(json, WrappersAndOptional.class);
        log.info("WrappersAndOptional deserialized: " + deserialized);
        Assert.assertEquals(wrappersAndOptional, deserialized);
    }

    @Test
    public void wrappersAndOptional_DefaultAndEmpty() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        WrappersAndOptional wrappersAndOptional = new WrappersAndOptional(0, null, Optional.empty());
        log.info("WrappersAndOptional original: " + wrappersAndOptional);

        String json = mapper.writeValueAsString(wrappersAndOptional);
        log.info("JSON serialized:         " + json);
        // NOTICE: The Integer is NOT SERIALIZED, since it is null. The Optional is serialized as null.
        Assert.assertEquals("{\"i\":0,\"optionalString\":null}", json);

        WrappersAndOptional deserialized = mapper.readValue(json, WrappersAndOptional.class);
        log.info("WrappersAndOptional deserialized: " + deserialized);
        Assert.assertEquals(wrappersAndOptional, deserialized);
    }

    @Test
    public void wrappersAndOptional_IntegerZero() {
        ObjectMapper mapper = FieldBasedJacksonMapper.getMats3DefaultJacksonObjectMapper();

        WrappersAndOptional wrappersAndOptional = new WrappersAndOptional(0, Integer.valueOf(0), null);
        log.info("WrappersAndOptional original: " + wrappersAndOptional);

        String json = mapper.writeValueAsString(wrappersAndOptional);
        log.info("JSON serialized:         " + json);
        // NOTICE: The Integer is serialized, since it is not null (even though 0).
        // The Optional is NOT serialized.
        Assert.assertEquals("{\"i\":0,\"integer\":0}", json);

        WrappersAndOptional deserialized = mapper.readValue(json, WrappersAndOptional.class);
        log.info("WrappersAndOptional deserialized: " + deserialized);
        Assert.assertEquals(wrappersAndOptional, deserialized);
    }
}
