package io.mats3.serial.json;

import java.util.Objects;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * Test object serialization and deserialization for {@link MatsSerializerJson}. It also handles Java Records, but
 * cannot test this in this project since it is Java 8.
 */
public class TestMatsSerializerJson {
    @Test
    public void deserializePrimitivesAndPrimitiveWrappersAndString() {
        MatsSerializerJson serializer = MatsSerializerJson.create();
        String meta = getMeta(serializer);

        Assert.assertEquals(Byte.valueOf((byte) 1), serializer.deserializeObject("1", Byte.class, meta));
        Assert.assertEquals(Byte.valueOf((byte) 1), serializer.deserializeObject("1", byte.class, meta));
        Assert.assertEquals(Short.valueOf((short) 1), serializer.deserializeObject("1", Short.class, meta));
        Assert.assertEquals(Short.valueOf((short) 1), serializer.deserializeObject("1", short.class, meta));
        Assert.assertEquals(Integer.valueOf(1), serializer.deserializeObject("1", Integer.class, meta));
        Assert.assertEquals(Integer.valueOf(1), serializer.deserializeObject("1", int.class, meta));
        Assert.assertEquals(Long.valueOf(1L), serializer.deserializeObject("1", Long.class, meta));
        Assert.assertEquals(Long.valueOf(1L), serializer.deserializeObject("1", long.class, meta));
        Assert.assertEquals(Float.valueOf(1f), serializer.deserializeObject("1", Float.class, meta));
        Assert.assertEquals(Float.valueOf(1f), serializer.deserializeObject("1", float.class, meta));
        Assert.assertEquals(Double.valueOf(1d), serializer.deserializeObject("1", Double.class, meta));
        Assert.assertEquals(Double.valueOf(1d), serializer.deserializeObject("1", double.class, meta));
        Assert.assertEquals(Character.valueOf((char) 1), serializer.deserializeObject("1", Character.class, meta));
        Assert.assertEquals(Character.valueOf((char) 1), serializer.deserializeObject("1", char.class, meta));
        Assert.assertEquals(Boolean.TRUE, serializer.deserializeObject("true", Boolean.class, meta));
        Assert.assertEquals(Boolean.TRUE, serializer.deserializeObject("true", boolean.class, meta));

        Assert.assertEquals("string", serializer.deserializeObject("\"string\"", String.class, meta));
    }

    @Test
    public void newInstanceTestForPrimitivesAndWrappersAndString() {
        MatsSerializerJson serializer = MatsSerializerJson.create();

        Assert.assertEquals(Byte.valueOf((byte) 0), serializer.newInstance(Byte.class));
        Assert.assertEquals(Byte.valueOf((byte) 0), serializer.newInstance(byte.class));
        Assert.assertEquals(Short.valueOf((short) 0), serializer.newInstance(Short.class));
        Assert.assertEquals(Short.valueOf((short) 0), serializer.newInstance(short.class));
        Assert.assertEquals(Integer.valueOf(0), serializer.newInstance(Integer.class));
        Assert.assertEquals(Integer.valueOf(0), serializer.newInstance(int.class));
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

        String meta = getMeta(serializer);
        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(ClassTest, ClassTest_deserialized);
    }

    @Test
    public void missingJsonField() {
        MatsSerializerJson serializer = MatsSerializerJson.create();
        String meta = getMeta(serializer);

        // Missing the string
        String json = "{\"integer\":1}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);

        // Missing the integer
        json = "{\"string\":\"endre\"}";

        ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 0), ClassTest_deserialized);
    }

    @Test
    public void extraJsonField() {
        MatsSerializerJson serializer = MatsSerializerJson.create();
        String meta = getMeta(serializer);

        // Extra fields
        String json = "{\"string\":\"endre\", \"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest("endre", 1), ClassTest_deserialized);

        // Missing the string, but also extra fields
        json = "{\"integer\":1, \"string2\":\"kamel\", \"integer2\":1, \"bool\":true}";

        ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 1), ClassTest_deserialized);
    }

    @Test
    public void emptyJsonToClass() {
        MatsSerializerJson serializer = MatsSerializerJson.create();
        String meta = getMeta(serializer);

        // Empty JSON
        String json = "{}";

        ClassTest ClassTest_deserialized = serializer.deserializeObject(json, ClassTest.class, meta);
        System.out.println("ClassTest deserialized: " + ClassTest_deserialized);
        Assert.assertEquals(new ClassTest(null, 0), ClassTest_deserialized);
    }

    @SuppressWarnings("overrides")
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

    private static String getMeta(MatsSerializer serializer) {
        return serializer.createNewMatsTrace("", "", KeepMatsTrace.FULL, false, false, 0, false)
                .getMatsSerializerMeta();
    }
}
