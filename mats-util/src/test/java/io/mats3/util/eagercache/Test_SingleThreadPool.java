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

package io.mats3.util.eagercache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientImpl;

/**
 * This is just a "visual inspection of log lines" test to see that the single-threaded ExecutorService works as
 * expected. You will notice that task 0 is submitted, and then the submitting of task 1 is blocked until task 0 is
 * finished, and so on.
 */
public class Test_SingleThreadPool {
    private static final Logger log = LoggerFactory.getLogger(Test_SingleThreadPool.class);

    @Test
    public void run() throws InterruptedException {
        ExecutorService threadPoolExecutor = MatsEagerCacheClientImpl._createSingleThreadedExecutorService(
                "EndreXY:test-thread-name");

        for (int i = 0; i < 3; i++) {
            int finalI = i;
            log.info("Submitting task " + i);

            threadPoolExecutor.execute(() -> {
                log.info(Thread.currentThread().getName() + " is executing task #" + finalI);
                try {
                    Thread.sleep(100); // Simulating some work
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                log.info(Thread.currentThread().getName() + " finished task #" + finalI);
            }); // This will block until the single thread is available

            log.info(" \\- Submitted task " + i);
        }

        threadPoolExecutor.shutdown();
        threadPoolExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
}
