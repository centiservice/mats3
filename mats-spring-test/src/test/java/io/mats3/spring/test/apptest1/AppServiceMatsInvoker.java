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

package io.mats3.spring.test.apptest1;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;

import org.springframework.stereotype.Service;

import io.mats3.spring.test.SpringTestDataTO;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 *
 * @author Endre Stølsvik 2020-11-16 21:10 - http://stolsvik.com/, endre@stolsvik.com
 */
@Service
public class AppServiceMatsInvoker {
    @Inject
    private MatsFuturizer _matsFuturizer;

    public SpringTestDataTO invokeMatsWithFuturizer(double number, String string) {
        CompletableFuture<Reply<SpringTestDataTO>> completableFuture = _matsFuturizer.futurizeNonessential("TraceId",
                "AppServiceMatsInvoker",
                AppMain_MockAndTestingHarnesses.ENDPOINT_ID_MAINENDPOINT,
                SpringTestDataTO.class,
                new SpringTestDataTO(number, string));

        try {
            return completableFuture.get(10, TimeUnit.SECONDS).get();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException("Couldn't get result", e);
        }
    }
}
