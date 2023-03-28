package io.mats3.serial.json;

import org.junit.Assert;
import org.junit.Test;

import java.util.Objects;

/**
 * Test object serialization and deserialization for {@link MatsSerializerJson}. It also handles Java Records, but
 * cannot test this in this project since it is Java 8.
 */
public class TestMatsSerializerJson {

    @Test
    public void deserializePrimitivesAndPrimitiveWrappersAndString() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        Assert.assertEquals(Byte.valueOf((byte) 1), serializer.deserializeObject("1", Byte.class));
        Assert.assertEquals(Byte.valueOf((byte) 1), serializer.deserializeObject("1", byte.class));
        Assert.assertEquals(Short.valueOf((short) 1), serializer.deserializeObject("1", Short.class));
        Assert.assertEquals(Short.valueOf((short) 1), serializer.deserializeObject("1", short.class));
        Assert.assertEquals(Integer.valueOf(1), serializer.deserializeObject("1", Integer.class));
        Assert.assertEquals(Integer.valueOf(1), serializer.deserializeObject("1", int.class));
        Assert.assertEquals(Long.valueOf(1L), serializer.deserializeObject("1", Long.class));
        Assert.assertEquals(Long.valueOf(1L), serializer.deserializeObject("1", long.class));
        Assert.assertEquals(Float.valueOf(1f), serializer.deserializeObject("1", Float.class));
        Assert.assertEquals(Float.valueOf(1f), serializer.deserializeObject("1", float.class));
        Assert.assertEquals(Double.valueOf(1d), serializer.deserializeObject("1", Double.class));
        Assert.assertEquals(Double.valueOf(1d), serializer.deserializeObject("1", double.class));
        Assert.assertEquals(Character.valueOf((char) 1), serializer.deserializeObject("1", Character.class));
        Assert.assertEquals(Character.valueOf((char) 1), serializer.deserializeObject("1", char.class));
        Assert.assertEquals(Boolean.TRUE, serializer.deserializeObject("true", Boolean.class));
        Assert.assertEquals(Boolean.TRUE, serializer.deserializeObject("true", boolean.class));

        Assert.assertEquals("string", serializer.deserializeObject("\"string\"", String.class));
    }

    @Test
    public void newInstanceTestForPrimitivesAndWrappersAndString() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        Assert.assertEquals(Byte.valueOf((byte) 0), serializer.newInstance(Byte.class));
        Assert.assertEquals(Byte.valueOf((byte) 0), serializer.newInstance(byte.class));
        Assert.assertEquals(Short.valueOf((short) 0), serializer.newInstance(Short.class));
        Assert.assertEquals(Short.valueOf((short) 0), serializer.newInstance(short.class));
        Assert.assertEquals(Integer.valueOf(0), serializer.newInstance(Integer.class));
        Assert.assertEquals(Integer.valueOf(0), serializer.newInstance( int.class));
        Assert.assertEquals(Long.valueOf(0L), serializer.newInstance(Long.class));
        Assert.assertEquals(Long.valueOf(0L), serializer.newInstance(long.class));
        Assert.assertEquals(Float.valueOf(0f), serializer.newInstance(Float.class));
        Assert.assertEquals(Float.valueOf(0f), serializer.newInstance(float.class));
        Assert.assertEquals(Double.valueOf(0d), serializer.newInstance(Double.class));
        Assert.assertEquals(Double.valueOf(0d), serializer.newInstance(double.class));
        Assert.assertEquals(Character.valueOf((char) 0), serializer.newInstance(Character.class));
        Assert.assertEquals(Character.valueOf((char) 0), serializer.newInstance(char.class));
        Assert.assertEquals(Boolean.FALSE, serializer.newInstance(Boolean.class));
        Assert.assertEquals(Boolean.FALSE, serializer.newInstance(boolean.class));

        Assert.assertEquals("", serializer.newInstance(String.class));
    }


    @Test
    public void straight_back_and_forth() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        ClassTest ClassTest = new ClassTest("endre", 1);

        System.out.println("ClassTest:              " + ClassTest);
        String json = serializer.serializeObject(ClassTest);
        System.out.println("JSON serialized:         " + json);
        Assert.assertEquals("{\"string\":\"endre\",\"integer\":1}", json);

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(ClassTest, ClassTest_deserialized);
    }

    @Test
    public void missingJsonField() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        // Missing the string
        String json = "{\"integer\":1}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);

        // Missing the integer
        json = "{\"string\":\"endre\"}";

        ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 0), ClassTest_deserialized);
    }

    @Test
    public void extraJsonField() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        // Extra fields
        String json = "{\"string\":\"endre\", \"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 1), ClassTest_deserialized);

        // Missing the string, but also extra fields
        json = "{\"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);
    }

    @Test
    public void emptyJsonToClass() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        // Empty JSON
        String json = "{}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 0), ClassTest_deserialized);
    }


    static class ClassTest {
        final String string;
        final int integer;

        public ClassTest() {
            this.string = null;
            this.integer = 0;
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
}
