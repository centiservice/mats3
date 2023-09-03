package io.mats3;

import io.mats3.MatsConfig.StartStoppable;
import io.mats3.MatsEndpoint.EndpointConfig;
import io.mats3.MatsEndpoint.ProcessLambda;
import io.mats3.MatsEndpoint.ProcessSingleLambda;

/**
 * A representation of a process stage of a {@link MatsEndpoint}. Either constructed implicitly (for single-stage
 * endpoints, and terminators), or by invoking the {@link MatsEndpoint#stage(Class, io.mats3.MatsEndpoint.ProcessLambda)
 * MatsEndpoint.stage(...)}-methods on {@link MatsFactory#staged(String, Class, Class) multi-stage} endpoints.
 * <p />
 * Note: It should be possible to use instances of <code>MatsStage</code> as keys in a <code>HashMap</code>, i.e. their
 * equals and hashCode should remain stable throughout the life of the MatsFactory - and similar instances but with
 * different MatsFactory are <i>not</i> equals. Depending on the implementation, instance equality may be sufficient.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsStage<R, S, I> extends StartStoppable {

    /**
     * @return the {@link StageConfig} for this stage.
     */
    StageConfig<R, S, I> getStageConfig();

    /**
     * @return the parent {@link MatsEndpoint}.
     */
    MatsEndpoint<R, S> getParentEndpoint();

    /**
     * Starts this stage, thereby firing up the queue processing using a set of threads, the number decided by the
     * {@link StageConfig#getConcurrency()} for each stage.
     * <p/>
     * Will generally be invoked implicitly by {@link MatsEndpoint#start()}. The only reason for calling this should be
     * if its corresponding {@link #stop(int)} method has been invoked to stop processing.
     */
    @Override
    void start();

    /**
     * Will wait until at least one processor of the stage has entered its receive-loop.
     */
    @Override
    boolean waitForReceiving(int timeoutMillis);

    /**
     * Stops this stage. This may be used to temporarily stop processing of this stage by means of some external
     * monitor/inspecting mechanism (e.g. in cases where it has been showed to produce results that breaks downstream
     * stages or endpoints, or itself produces <i>Dead Letter Queue</i>-entries due to some external problem). It is
     * possible to {@link #start()} the stage again.
     */
    @Override
    boolean stop(int gracefulShutdownMillis);

    /**
     * Provides for both configuring the stage (before it is started), and introspecting the configuration.
     */
    interface StageConfig<R, S, I> extends MatsConfig {

        /**
         * @return the stageId for this Stage - for the initial stage of an endpoint, this is the same as the
         *         {@link EndpointConfig#getEndpointId()}.
         */
        String getStageId();

        /**
         * @return the index number of the stage, where 0 is the initial stage where the {@link #getStageId()} is equal
         *         to the Endpoint's {@link EndpointConfig#getEndpointId() endpointId}. Subsequent stages will have
         *         indices 1, 2, 3 etc. This will typically also be reflected in the stageId for all non-initial stages,
         *         where their stageIds are equal to <code>"{endpointId}.stage{stageIndex}"</code> (the initial stage is
         *         just <code>"{endpointId}"</code>, of course).
         */
        int getStageIndex();

        /**
         * @return the class expected for incoming messages to this process stage.
         */
        Class<I> getIncomingClass();

        /**
         * @return the {@link ProcessLambda} which will process this stage. Note that all stages are actually internally
         *         a generic ProcessLambda, even though when created, a seemingly specific type was used (e.g.
         *         {@link ProcessSingleLambda}). Those variants are all just convenience wrappers around the generic
         *         variant, and this method will return the underlying generic one.
         */
        ProcessLambda<R, S, I> getProcessLambda();

        /**
         * @return the currently number of running Stage Processors (the actual concurrency - this might be different
         *         from {@link #getConcurrency} if the concurrency was set when stage was running.
         */
        int getRunningStageProcessors();

        /**
         * Sets the origin for this Stage, i.e. where it was created. Use this to set something sane if the
         * {@link #getOrigin() automatically created creation info} is useless. It should be a single line, no line
         * feeds, but tooling might split the String into multiple lines on the character ';'. It should definitely be
         * short, but informative.
         *
         * @return <code>this</code>, for chaining.
         */
        StageConfig<R, S, I> setOrigin(String info);

        /**
         * @return some human-interpretable information about where in the codebase this Stage was created. If this is
         *         displayed in a multi-line capable situation, you should split on ';'. An attempt at some automatic
         *         creation is performed, based on creating an exception and introspecting the result. If this doesn't
         *         yield any good result, it might be overridden by {@link #setOrigin(String)}.
         */
        String getOrigin();

        // Overridden to return the more specific StageConfig instead of MatsConfig
        @Override
        StageConfig<R, S, I> setAttribute(String key, Object value);

        // Overridden to return the more specific StageConfig instead of MatsConfig
        @Override
        StageConfig<R, S, I> setConcurrency(int concurrency);

        // Overridden to return the more specific StageConfig instead of MatsConfig
        @Override
        StageConfig<R, S, I> setInteractiveConcurrency(int concurrency);
    }
}