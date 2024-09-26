package io.mats3.util.eagercache;

import java.util.concurrent.ExecutorService;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is just a "visual inspection of log lines" test to see that the single-threaded ExecutorService works as
 * expected.
 */
public class Test_SingleThreadPool {
    private static final Logger log = LoggerFactory.getLogger(Test_SingleThreadPool.class);

    @Test
    public void run() {
        ExecutorService threadPoolExecutor = MatsEagerCacheClient._createSingleThreadedExecutorService(
                "EndreXY:test-thread-name");

        for (int i = 0; i < 5; i++) {
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
            }); // This will block until the single thread is available

            log.info(" \\- Submitted task " + i);
        }

        threadPoolExecutor.shutdown();
    }
}
