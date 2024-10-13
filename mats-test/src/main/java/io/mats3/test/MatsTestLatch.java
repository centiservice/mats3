package io.mats3.test;

import io.mats3.MatsEndpoint.DetachedProcessContext;

/**
 * Test-utility: Gives a latch-functionality facilitating communication back from typically a Mats Terminator to the
 * main-thread that sent a message to some processor, and is now waiting for the Terminator to get the result.
 *
 * @see MatsTestBarrier
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class MatsTestLatch {

    /**
     * Some tests needs to assert that something <i>does not</i> happen, i.e. actually wait for the timeout to expire.
     * This time should obviously be as short as possible, but due to extreme inconsistencies on the runners of some
     * continuous integration systems, we've set it at 1 second (1000 millis). This constant works as a central place to
     * define this, so that it is not spread out through lots of tests.
     */
    public static final int WAIT_MILLIS_FOR_NON_OCCURRENCE;
    static {
        WAIT_MILLIS_FOR_NON_OCCURRENCE = System.getenv("CI") != null
                ? 1000
                : 250;
    }

    public interface Result<S, I> {
        DetachedProcessContext getContext();

        S getState();

        I getData();
    }

    /**
     * Convenience method of {@link #waitForResult(long)} meant for waiting for something that is expected to happen:
     * Waits for 30 seconds for the test-latch to be {@link #resolve(DetachedProcessContext, Object, Object) resolved}.
     * Since tests employing this method should resolve <i>as fast as possible</i>, it makes little sense to limit this
     * waiting time to "a short time", as either the test works, and it resolves literally as fast as possible, or the
     * test is broken, and then it doesn't really matter that you have to wait 30 seconds to find out. What we don't
     * want is a test that fails due to e.g. garbage collection kicking in, or some other infrastructural matters that
     * do not relate to the test.
     *
     * @return same as {@link #waitForResult(long)}.
     */
    public <S, I> Result<S, I> waitForResult() {
        return waitForResult(30_000);
    }

    /**
     * Waits for the specified time for {@link #resolve(DetachedProcessContext, Object, Object)} resolve(..)} to be
     * invoked by some other thread, returning the result. If the result is already in, it immediately returns. If the
     * result does not come within timeout, an {@link AssertionError} is raised.
     * <p />
     * <b>Notice: If you wait for something that <i>should</i> happen, you should rather use the non-arg method
     * {@link #waitForResult()}.
     *
     * @param timeout
     *            the max time to wait.
     * @return the {@link Result}. Throws {@link AssertionError} if not gotten within timeout.
     */
    public <S, I> Result<S, I> waitForResult(long timeout) {
        synchronized (this) {
            if (!_resolved) {
                try {
                    this.wait(timeout);
                }
                catch (InterruptedException e) {
                    throw new AssertionError("Should not get InterruptedException here.", e);
                }
            }

            if (!_resolved) {
                throw new AssertionError("After waiting for " + timeout + " ms, the result was not present.");
            }

            Result<S, I> result = new Result<S, I>() {
                @SuppressWarnings("unchecked")
                private I _idto = (I) _dto;
                @SuppressWarnings("unchecked")
                private S _isto = (S) _sto;
                private DetachedProcessContext _icontext = _context;

                @Override
                public DetachedProcessContext getContext() {
                    return _icontext;
                }

                @Override
                public S getState() {
                    return _isto;
                }

                @Override
                public I getData() {
                    return _idto;
                }
            };

            // Null out the latch, for reuse.
            _resolved = false;
            _sto = null;
            _dto = null;
            _context = null;
            return result;
        }
    }

    private boolean _resolved;
    private Object _dto;
    private Object _sto;
    private DetachedProcessContext _context;

    /**
     * When this method is invoked, the waiting threads will be released - <b>this variant does not take the
     * ProcessContext</b>, use {@link #resolve(DetachedProcessContext, Object, Object) the other one}!
     *
     * @param sto
     *            State object.
     * @param dto
     *            the incoming state object that the Mats processor initially received.
     * @see #resolve(DetachedProcessContext, Object, Object)
     */
    public void resolve(Object sto, Object dto) {
        synchronized (this) {
            if (_resolved) {
                throw new IllegalStateException("Already set, but not consumed: Cannot set again.");
            }
            _resolved = true;
            _dto = dto;
            _sto = sto;
            this.notifyAll();
        }
    }

    /**
     * When this method is invoked, the waiting threads will be released.
     *
     * @param context
     *            (Detached)ProcessContext
     * @param sto
     *            State object.
     * @param dto
     *            the incoming state object that the Mats processor initially received.
     */
    public void resolve(DetachedProcessContext context, Object sto, Object dto) {
        synchronized (this) {
            if (_resolved) {
                throw new IllegalStateException("Already set, but not consumed: Cannot set again.");
            }
            _resolved = true;
            _dto = dto;
            _sto = sto;
            _context = context;
            this.notifyAll();
        }
    }
}
