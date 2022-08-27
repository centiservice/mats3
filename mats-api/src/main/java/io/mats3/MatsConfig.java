package io.mats3;

import io.mats3.MatsFactory.FactoryConfig;

/**
 * All of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} have some configurable elements, provided by a
 * config instance, this is the top of that hierarchy.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsConfig {
    /**
     * To change the default concurrency of the Factory, or of the endpoint (which defaults to the concurrency of the
     * {@link MatsFactory}), or of the process stage (which defaults to the concurrency of the {@link MatsEndpoint}).
     * <p/>
     * The default for the {@link MatsFactory} is the number returned by {@link FactoryConfig#getNumberOfCpus()}, which
     * by default is the number of processors on the server it is running on, as determined by
     * {@link Runtime#availableProcessors()}.
     * <p/>
     * Will only have effect before the {@link MatsStage} is started. Can be reset by stopping, setting, and restarting.
     * <p/>
     * Setting to 0 will invoke default logic.
     *
     * @param concurrency
     *            the number of consumers on the queue(s) for the processing stage(s). If set to 0, default-logic is in
     *            effect.
     * @return the config object, for method chaining.
     */
    MatsConfig setConcurrency(int concurrency);

    /**
     * Returns the concurrency set up for this factory, or endpoint, or process stage. Will provide the default unless
     * overridden by {@link #setConcurrency(int)} before start. It is the {@link MatsStage}s that eventually will be
     * affected by this number, as that is where the consumers of the backend queues reside.
     * <p/>
     * If default logic is in effect (i.e. have not been set by {@link #setConcurrency(int)}, or that is set to 0), a
     * {@link MatsStage} will default to its {@link MatsEndpoint}, while an endpoint will default to the
     * {@link MatsFactory}. The default for the {@link MatsFactory} is 2 x the number returned by
     * {@link FactoryConfig#getNumberOfCpus()}, which by default is the number of processors on the server it is running
     * on, as determined by {@link Runtime#availableProcessors()}.
     *
     * @return the concurrency set up for this factory, or endpoint, or process stage. Will provide the default unless
     *         overridden by {@link #setConcurrency(int)} before start.
     */
    int getConcurrency();

    /**
     * @return whether the number provided by {@link #getConcurrency()} is using default-logic (as if the concurrency is
     *         set to 0) (<code>true</code>), or it if is set specifically (</code>false</code>).
     */
    boolean isConcurrencyDefault();

    /**
     * @return whether the MATS entity has been started and not stopped. For the {@link MatsFactory}, it returns true if
     *         any of the endpoints return true (which also implies that if there are no Endpoints registered, it will
     *         return <code>false</code>). For {@link MatsEndpoint}s, it returns true if any stage is running.
     */
    boolean isRunning();

    MatsConfig setAttribute(String name, Object object);

    <T> T getAttribute(String name);

    /**
     * All three of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} implements this interface.
     */
    interface StartStoppable {
        /**
         * Will start the entity - or the entities below it (the only "active" entity is a {@link MatsStage} Processor).
         * Calling this method when the entity is already running has no effect.
         * <p/>
         * Further documentation on extensions - note the special semantics for {@link MatsFactory}
         */
        void start();

        /**
         * If the entity is stopped or starting, this method won't return until it has actually started the receive-loop
         * (i.e. that some {@link MatsStage} Processor has actually entered its receive-loop, consuming messages). If
         * the entity has already gotten into the receive loop, the method immediately returns.
         * <p/>
         * Note: Currently, this only holds for the initial start. If the entity has started the receive-loop at some
         * point, it will always immediately return - even though it is currently stopped.
         * <p/>
         * Further documentation on extensions.
         *
         * @param timeoutMillis
         *            number of milliseconds before giving up the wait, returning <code>false</code>. 0 is indefinite
         *            wait, negative values are not allowed.
         * @return <code>true</code> if the entity started within the timeout, <code>false</code> if it did not start.
         */
        boolean waitForReceiving(int timeoutMillis);

        /**
         * Will stop the entity - or the entities below it (the only "active" entity is a {@link MatsStage} Processor).
         * This method is idempotent, calling it when the entity is already stopped has no effect.
         * <p/>
         * Further documentation on extensions - note the special semantics for {@link MatsFactory}
         *
         * @param gracefulShutdownMillis
         *            number of milliseconds to let the stage processors wait after having asked for them to shut down,
         *            and interrupting them if they have not shut down yet.
         * @return <code>true</code> if the running thread(s) were dead when returning, <code>false</code> otherwise.
         */
        boolean stop(int gracefulShutdownMillis);
    }
}
