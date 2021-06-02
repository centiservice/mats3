package io.mats3.api.intercept;

import java.time.Instant;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator;
import io.mats3.MatsInitiator.InitiateLambda;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * <b>EXPERIMENTAL!!</b> (Will probably change. Implementation started 2020-01-08)
 * <p />
 * Meant for intercepting initiations with ability to modify the initiation, and to implement extra logging and metrics
 * gathering.
 * <p />
 * When more than one interceptor is installed, the ordering becomes interesting. If the following class is added twice,
 * <b>first</b> with 1 as the argument, and then <b>secondly</b> with 2 as the argument ..:
 * 
 * <pre>
 * private static class MyMatsInitiateInterceptor implements MatsInitiateInterceptor {
 *
 *     private final int _number;
 *
 *     MyMatsInitiateInterceptor(int number) {
 *         _number = number;
 *     }
 *
 *     &#64;Override
 *     public void initiateStarted(InitiateStartedContext initiateStartedContext) {
 *         log.info("Started #" + _number);
 *     }
 *
 *     &#64;Override
 *     public void initiateIntercept(InitiateInterceptContext initiateInterceptContext,
 *             InitiateLambda initiateLambda, MatsInitiate matsInitiate) {
 *         log.info("Intercept pre #" + _number);
 *
 *         // Wrap the MatsInitiate to catch "send(dto)", before invoking the lambda
 *         MatsInitiateWrapper wrappedMatsInitiate = new MatsInitiateWrapper(matsInitiate) {
 *             &#64;Override
 *             public MessageReference send(Object messageDto) {
 *                 log.info(".. send pre #" + _number);
 *                 MessageReference send = super.send(messageDto);
 *                 log.info(".. send post #" + _number);
 *                 return send;
 *             }
 *         };
 *
 *         // Invoke the lambda, with the wrapped MatsInitiate
 *         initiateLambda.initiate(wrappedMatsInitiate);
 *
 *         log.info("Intercept post #" + _number);
 *     }
 *
 *     &#64;Override
 *     public void initiateCompleted(InitiateCompletedContext initiateCompletedContext) {
 *         log.info("Completed #" + _number);
 *     }
 * }
 * </pre>
 * 
 * .. then the following sequence of log lines and operations ensues:
 * <ol>
 * <li>First the {@link MatsInitiateInterceptor#initiateStarted(InitiateStartedContext) initiateStarted(..)} are invoked
 * in sequence:</li>
 * <li><code>Started #1</code></li>
 * <li><code>Started #2</code></li>
 * <li>Then the initiation goes into the transaction, and a "reverse stack" of lambdas are generated from the two
 * interceptors'
 * {@link MatsInitiateInterceptUserLambda#initiateInterceptUserLambda(InitiateInterceptUserLambdaContext, InitiateLambda, MatsInitiate)
 * initiateIntercept(..)} methods, and then the resulting lambda is invoked:</li>
 * <li><code>Intercept pre #1</code></li>
 * <li><code>Intercept pre #2</code></li>
 * <li>.. the actual user provided lambda (the one provided by the endpoint) is invoked, performing a
 * <code>send(..)</code>, which goes through the stack of MatsInitiateWrappers which the
 * <code>initiateIntercept(..)</code> invocations generated (thus hitting the last wrapping by #2 first):</li>
 * <li><code>.. send pre #2</code></li>
 * <li><code>.. send pre #1</code></li>
 * <li>.. the actual Mats API send(..) method is invoked, before we traverse out of the MatsInitiateWrappers:</li>
 * <li><code>.. send post #1</code></li>
 * <li><code>.. send post #2</code></li>
 * <li>.. and then the user lambda exits, traversing back up the <code>initiateIntercept(..)</code> stack</li>
 * <li><code>Intercept post #2</code></li>
 * <li><code>Intercept post #1</code></li>
 * <li>Finally, the {link {@link MatsInitiateInterceptor#initiateCompleted(InitiateCompletedContext)
 * initiateComplete(..)} is invoked, in explicit reversed order:</li>
 * <li><code>Completed #2</code></li>
 * <li><code>Completed #1</code></li>
 * </ol>
 * If you envision that these two interceptors are created for logging, where the first interceptor puts values on the
 * MDC, while the second one logs, then this makes a bit of sense: First the MDC is set, then an entry log line is
 * emitted, then an exit log line is emitted, then the MDC is cleared. <b>However</b>, interceptions of the inner call
 * from the actual user provided lambda to MatsInitiate.send(..) ends up in what is seemingly the "wrong"
 * <i>sequential</i> order, due to how the interception is logically performed: The #2 is the <i>last</i>
 * initiateIntercept(..) that is invoked, which thus is the code that <i>lastly</i> wraps the MatsInitiate instance, and
 * hence when the user code invokes matsInitiate.send(..), that lastly added wrapping is the <i>first</i> to be
 * executed. What this means, is that you should probably not make severe ordering dependencies between interceptors
 * which depends on wrapping the MatsInitiate instance, as your head will most probably hurt from the ensuing twisted
 * reasoning!
 * <p />
 * <b>Note: This is only invoked for proper, actual initiations "from outside of Mats", i.e. using a
 * {@link MatsInitiator} gotten with {@link MatsFactory#getDefaultInitiator()} or
 * {@link MatsFactory#getOrCreateInitiator(String)}</b> <i>- and notice the special semantics of getDefaultInitiator(),
 * whereby if such an seemingly "from the outside" initiation is invoked when code-flow-wise within a Stage, you will
 * actually be "elevated" to be initiated "within Mats" using the Stage initiator - and hence this interceptor will not
 * be invoked (it is no longer "from outside of Mats").</i>
 * <p />
 * The concept of "outside" vs. "inside" perhaps seems subtle, but there is a distinct difference: No processing will
 * ever happen in a Mats fabric if no initiations happens "from the outside": It is always a "from the outside"
 * initiation that will set process flows in action. Such a process flow might then set several new process flows in
 * action (i.e. initiations "from the inside"), but those are dependent on the initial process flow that was set in
 * motion "from the outside", and would never have been initiated was it not for such initiation.
 * <p />
 * To catch initiations "from the inside", you will employ a {@link MatsStageInterceptor}.
 *
 * @author Endre Stølsvik - 2021-01-08 - http://endre.stolsvik.com
 */
public interface MatsInitiateInterceptor {

    /**
     * Invoked right before user lambda is invoked.
     */
    default void initiateStarted(InitiateStartedContext context) {
        /* no-op */
    }

    /**
     * Enables the intercepting of the invocation of the user lambda in an Initiation, with ability to wrap the
     * {@link MatsInitiate} (and thus modify any request, send or publishes) - or even take over the entire initiation.
     * Wrt. changing messages, you should also consider
     * {@link MatsInitiateInterceptOutgoingMessages#initiateInterceptOutgoingMessages(InitiateInterceptOutgoingMessagesContext)}.
     * <p />
     * Pulled out in separate interface, so that we don't need to invoke it if the interceptor doesn't need it.
     */
    interface MatsInitiateInterceptUserLambda {
        default void initiateInterceptUserLambda(InitiateInterceptUserLambdaContext context,
                InitiateLambda initiateLambda, MatsInitiate matsInitiate) {
            // Default: Call directly through
            initiateLambda.initiate(matsInitiate);
        }
    }

    /**
     * While still within the initiation context, this interception enables modifying outgoing messages from the user
     * lambda, setting trace properties, adding "sideloads", deleting a message, or initiating additional messages.
     * <p />
     * Pulled out in separate interface, so that we don't need to invoke it if the interceptor doesn't need it.
     */
    interface MatsInitiateInterceptOutgoingMessages extends MatsInitiateInterceptor {
        void initiateInterceptOutgoingMessages(InitiateInterceptOutgoingMessagesContext context);
    }

    default void initiateCompleted(InitiateCompletedContext context) {
        /* no-op */
    }

    interface InitiateInterceptContext {
        MatsInitiator getInitiator();

        Instant getStartedInstant();
    }

    interface InitiateStartedContext extends InitiateInterceptContext {
        void initiate(InitiateLambda lambda);
    }

    interface InitiateInterceptUserLambdaContext extends InitiateInterceptContext {
        void initiate(InitiateLambda lambda);
    }

    interface InitiateInterceptOutgoingMessagesContext extends InitiateInterceptContext,
            CommonInterceptOutgoingMessagesContext {
    }

    interface InitiateCompletedContext extends InitiateInterceptContext, CommonCompletedContext {
    }
}
