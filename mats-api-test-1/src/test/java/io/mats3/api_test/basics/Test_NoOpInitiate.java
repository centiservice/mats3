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

package io.mats3.api_test.basics;

import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.test.junit.Rule_Mats;

/**
 * Tests that it is OK to NOT do anything in initiate.
 *
 * @author Endre Stølsvik 2020-03-01 23:31 - http://stolsvik.com/, endre@stolsvik.com
 */
public class Test_NoOpInitiate {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void doTest() {
        MATS.getMatsInitiator().initiateUnchecked(init -> {
            /* no-op: Not sending/requesting/doing anything .. */
        });
    }
}
