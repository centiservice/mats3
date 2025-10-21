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

package io.mats3.serial.json;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsTrace.KeepMatsTrace;

/**
 * Test object serialization and deserialization for {@link MatsSerializerJson}.
 */
public class TestMatsSerializerJson {

    // #########################################################
    // ## NOTE!! MORE TESTS IN Test_FieldBasedJacksonMapper!! ##
    // #########################################################

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

    private static String getMeta(MatsSerializer serializer) {
        return serializer.createNewMatsTrace("", "", KeepMatsTrace.FULL, false, false, 0, false)
                .getMatsSerializerMeta();
    }
}
