package io.mats3.util;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.Assert;
import org.junit.Test;

import io.mats3.util.DeflateTools.NonblockingStack;

/**
 * Tests the {@link NonblockingStack} both single threaded, and synchronized multi-threaded.
 */
public class Test_NonBlockingStack {
    @Test
    public void simpleSingleThread() {
        NonblockingStack<Integer> testStack = new NonblockingStack<>();
        for (int i = 0; i < 100; i++) {
            testStack.push(i);
            Assert.assertEquals(i + 1, testStack.size());
            Assert.assertEquals(i + 1, testStack.sizeFromWalk());
        }

        for (int i = 99; i >= 0; i--) {
            Assert.assertEquals(i, testStack.pop().intValue());
            Assert.assertEquals(i, testStack.size());
        }
        Assert.assertEquals(0, testStack.size());
        Assert.assertEquals(0, testStack.sizeFromWalk());
        Assert.assertNull(testStack.pop());
        Assert.assertEquals(0, testStack.size());
        Assert.assertEquals(0, testStack.sizeFromWalk());
    }

    @Test
    public void multipleThreads() throws InterruptedException {
        NonblockingStack<Integer> testStack = new NonblockingStack<>();
        int count = 500;
        CountDownLatch latch_StartThreads = new CountDownLatch(1);
        CountDownLatch latch_ThreadsDone = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            int finalI = i;
            new Thread(() -> {
                try {
                    latch_StartThreads.await();
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                testStack.push(finalI);
                latch_ThreadsDone.countDown();
            }).start();
        }
        Thread.sleep(500); // Let the threads start up
        latch_StartThreads.countDown();
        latch_ThreadsDone.await();
        Assert.assertEquals(count, testStack.size());
        Assert.assertEquals(count, testStack.sizeFromWalk());

        Set<Integer> poppedSet = new HashSet<>();

        for (int i = 0; i < count; i++) {
            Assert.assertEquals(count - i, testStack.size());
            Assert.assertEquals(count - i, testStack.sizeFromWalk());
            int popped = testStack.pop();
            poppedSet.add(popped);
            // System.out.println("Popped " + i + ": " + (count - i) + " -> " + popped);
        }

        Assert.assertEquals(count, poppedSet.size());
        for (int i = 0; i < count; i++) {
            Assert.assertTrue(poppedSet.contains(i));
        }

        Assert.assertEquals(0, testStack.size());
        Assert.assertEquals(0, testStack.sizeFromWalk());
        Assert.assertNull(testStack.pop());
        Assert.assertEquals(0, testStack.size());
        Assert.assertEquals(0, testStack.sizeFromWalk());
    }

}
