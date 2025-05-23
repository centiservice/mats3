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

package io.mats3.test;

/**
 * Test-utility: Simple barrier-functionality facilitating communication back from some async processing to the main
 * thread. Can be just resolved, or resolved with an Object, or with an Exception - which the main thread awaits. If the
 * opposite of what you're awaiting for is what is resolved (i.e. you're awaiting for a result, but the barrier is
 * resolved with an exception), an {@link AssertionError} is raised from the await call. This tool is less Mats-specific
 * than {@link MatsTestLatch}, and does not use a Result interface, but rather just a generic Object as result. If you'd
 * like to e.g. communicate back from a Terminator and need both the incoming message and the state - and possibly the
 * ProcessContext - then you should evaluate the {@link MatsTestLatch} instead.
 * <p/>
 * Reset/reuse is not automatic: You must call {@link #reset()} after having resolved and awaited it, otherwise a
 * new await will immediately return with the result/exception of the last resolution.
 *
 * @see MatsTestLatch
 * @author Endre Stølsvik 2024-10-13 02:19 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsTestBarrier {

    private final Object _lock = new Object();
    private boolean _resolved = false;
    private Object _result;
    private Throwable _exception;

    /**
     * Resolves the barrier without result.
     */
    public void resolve() {
        synchronized (_lock) {
            if (_resolved) {
                throw new IllegalStateException("Barrier already resolved.");
            }
            _resolved = true;
            _result = null;
            _lock.notifyAll();
        }
    }

    /**
     * Resolves the barrier, and sets the result.
     * 
     * @param result
     *            the result of the async processing.
     */
    public void resolve(Object result) {
        synchronized (_lock) {
            if (_resolved) {
                throw new IllegalStateException("Barrier already resolved.");
            }
            _resolved = true;
            _result = result;
            _lock.notifyAll();
        }
    }

    /**
     * Resolves the barrier with an exception.
     * 
     * @param exception
     *            the exception that occurred during the async processing.
     */
    public void resolveException(Throwable exception) {
        synchronized (_lock) {
            if (_resolved) {
                throw new IllegalStateException("Barrier already resolved.");
            }
            _resolved = true;
            _exception = exception;
            _lock.notifyAll();
        }
    }

    /**
     * Waits for the barrier to be resolved for 30 seconds, returning the result. If the result is already in, it
     * immediately returns. If the result does not come within timeout, an {@link AssertionError} is raised.
     */
    public <T> T await() {
        return await(30_000);
    }

    /**
     * Waits for the barrier to be resolved, returning the result. If the result is already in, it immediately returns.
     * If the result does not come within timeout, an {@link AssertionError} is raised.
     *
     * @param timeoutMillis
     *            the max time to wait.
     * @return the result. Throws {@link AssertionError} if not gotten within timeout.
     */
    public <T> T await(long timeoutMillis) {
        synchronized (_lock) {
            if (!_resolved) {
                try {
                    _lock.wait(timeoutMillis);
                }
                catch (InterruptedException e) {
                    throw new AssertionError("Interrupted while waiting for barrier to resolve.", e);
                }
            }
            if (!_resolved) {
                throw new AssertionError("Barrier was not resolved within timeout [" + timeoutMillis + " ms].");
            }
            if (_exception != null) {
                throw new AssertionError("Barrier was unexpectedly resolved with exception: "
                        + _exception.getMessage(), _exception);
            }
            @SuppressWarnings("unchecked")
            T ret = (T) _result;
            return ret;
        }
    }

    /**
     * Assert that this barrier is NOT resolved - which inherently is problematic since it is an assertion that
     * something did NOT happen <i>yet</i>! This method will wait for the
     * {@link MatsTestLatch#WAIT_MILLIS_FOR_NON_OCCURRENCE} milliseconds for the barrier to not resolve, and then raise
     * an {@link AssertionError} if it did resolve.
     */
    public void awaitNoResult() {
        synchronized (_lock) {
            if (!_resolved) {
                try {
                    _lock.wait(MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE);
                }
                catch (InterruptedException e) {
                    throw new AssertionError("Interrupted while waiting for barrier to NOT resolve.", e);
                }
            }
            if (_resolved) {
                throw new AssertionError("Barrier was unexpectedly resolved within timeout ["
                        + MatsTestLatch.WAIT_MILLIS_FOR_NON_OCCURRENCE + " ms] - we expected it to NOT resolve!");
            }
        }
    }

    /**
     * Waits for the barrier to be resolved exceptionally for 30 seconds, returning the exception. If the exception is
     * already in, it immediately returns. If the exception does not come within timeout, an {@link AssertionError} is
     * raised.
     *
     * @return the exception. Throws {@link AssertionError} if not gotten within timeout.
     */
    public Throwable awaitException() {
        return awaitException(30_000);
    }

    /**
     * Waits for the barrier to be resolved exceptionally, returning the exception. If the exception is already in, it
     * immediately returns. If the exception does not come within timeout, an {@link AssertionError} is raised.
     * 
     * @param timeoutMillis
     *            the max time to wait.
     * @return the exception. Throws {@link AssertionError} if not gotten within timeout.
     */
    public Throwable awaitException(long timeoutMillis) {
        synchronized (_lock) {
            if (!_resolved) {
                try {
                    _lock.wait(timeoutMillis);
                }
                catch (InterruptedException e) {
                    throw new AssertionError("Interrupted while waiting for barrier to resolve.", e);
                }
            }
            if (!_resolved) {
                throw new AssertionError("Barrier was not resolved within timeout [" + timeoutMillis + " ms].");
            }
            if (_result != null) {
                throw new AssertionError("Barrier was unexpectedly resolved with result: " + _result);
            }
            return _exception;
        }
    }

    /**
     * Resets the barrier, so that it can be reused.
     */
    public void reset() {
        synchronized (_lock) {
            _resolved = false;
            _result = null;
            _exception = null;
        }
    }
}
