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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.mats3.util.MatsFuturizer.Reply;

/**
 * Illustrates the usage of {@link Extension_Mats}
 *
 * @author Kevin Mc Tiernan, 2020-10-20, kmctiernan@gmail.com
 */
public class J_ExtensionMatsTest {

    @RegisterExtension
    public static final Extension_Mats MATS = Extension_Mats.create();

    @BeforeAll
    static void setupEndpoint() {
        // :: Arrange
        MATS.getMatsFactory().single("MyEndpoint", String.class, String.class, (ctx, msg) -> "Hello " + msg);
    }

    /**
     * Simple test to verify that we actually have a factory and a valid broker.
     */
    @Test
    public void verifyValidMatsFactoryCreated() throws InterruptedException, ExecutionException, TimeoutException {
        // :: Act
        String reply = MATS.getMatsFuturizer().futurizeNonessential("VerifyValidMatsFactory",
                getClass().getSimpleName(),
                "MyEndpoint",
                String.class,
                "World!")
                .thenApply(Reply::get)
                .get(10, TimeUnit.SECONDS);

        // :: Assert
        Assertions.assertEquals("Hello World!", reply);
    }
}
