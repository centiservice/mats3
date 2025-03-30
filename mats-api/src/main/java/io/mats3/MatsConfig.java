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

package io.mats3;

import java.util.Optional;

import io.mats3.MatsFactory.FactoryConfig;
import io.mats3.MatsInitiator.MatsInitiate;

/**
 * All of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} have some configurable elements, provided by a
 * config instance, this is the top of that hierarchy.
 *
 * @author Endre Stølsvik - 2015-07-11 - http://endre.stolsvik.com
 */
public interface MatsConfig {
    /**
     * Returns the concurrency set up for this factory, or endpoint, or stage. Will provide the default unless
     * overridden by {@link #setConcurrency(int)} before start.
     * <p />
     * It is the {@link MatsStage}s that eventually will be affected by this number, specifically how many standard and
     * {@link MatsInitiate#interactive() interactive} StageProcessors is fired up for each Stage. One StageProcessor is
     * one thread consuming from the stage-specific queue, thus if the concurrency for a Stage is resolved to be 6, then
     * by default 2 x 6 threads will be consuming from this stage's queue: 6 for normal priority, and 6 for interactive
     * priority.
     * <p />
     * If default logic is in effect (i.e. have not been set by {@link #setConcurrency(int)}, or that is set to 0), a
     * {@link MatsStage} will default to its {@link MatsEndpoint}, while an endpoint will default to the
     * {@link MatsFactory}. The default for the {@link MatsFactory} is the <b>minimum</b> of <code>[10, number_of_cpus x
     * 2]</code>. The 'number_of_cpus' is defined by {@link FactoryConfig#getNumberOfCpus() getNumberOfCpus()}, which by
     * default is the number of processors on the server it is running on, as determined by
     * {@link Runtime#availableProcessors()} (but this default can be changed by setting the System Property
     * "mats.cpus"). The reason for using this <i>minimum</i>-logic for default concurrency is that if you start up a
     * Mats Factory on a server with 32 CPUs, you would probably not want 64 threads x 2 (because of the separate
     * interactive thread count) consuming from each and every stage (i.e. 128 threads per stage), which would be quite
     * excessive.
     *
     * @return the concurrency set up for this factory, or endpoint, or process stage. Will provide the default unless
     *         overridden by {@link #setConcurrency(int)} before start.
     * @see #setConcurrency(int)
     * @see #isConcurrencyDefault()
     * @see #getInteractiveConcurrency()
     * @see #setInteractiveConcurrency(int)
     */
    int getConcurrency();

    /**
     * Changes the default concurrency of the Factory, or of the endpoint (which defaults to the concurrency of the
     * {@link MatsFactory}), or of the process stage (which defaults to the concurrency of the {@link MatsEndpoint}).
     * <p />
     * Will only have effect before the {@link MatsStage} is started. Can be reset by stopping, setting, and restarting.
     * <p />
     * Setting to 0 will invoke default logic - read about that in {@link #getConcurrency()}.
     *
     * @param concurrency
     *            the concurrency for the Factory, or Endpoint, or Stage. If set to 0, default-logic is in effect.
     * @return the config object, for method chaining.
     * @see #getConcurrency()
     */
    MatsConfig setConcurrency(int concurrency);

    /**
     * @return whether the number provided by {@link #getConcurrency()} is using default-logic (when the concurrency is
     *         set to 0) (<code>true</code>), or if it is set specifically (</code>false</code>).
     * @see #getConcurrency()
     */
    boolean isConcurrencyDefault();

    /**
     * Returns the interactive concurrency set up for this factory, or endpoint, or stage. Will provide the default
     * unless overridden by {@link #setConcurrency(int)} before start.
     * <p />
     * If default logic is in effect (i.e. have not been set by {@link #setConcurrency(int)}, or that is set to 0), a
     * {@link MatsStage} will see if the same Stage's normal concurrency is non-default, and if so, use that -
     * otherwise, it will use the interactive concurrency of its parent Endpoint. The {@link MatsEndpoint} will see if
     * the same Endpoint's normal concurrency is non-default, and if so, use that - otherwise it will use the
     * interactive concurrency of its parent Factory. The {@link MatsFactory} will by default use the normal concurrency
     * of the Factory.
     * <p />
     * Effectively, a more specific setting for interactive concurrency overrides a less specific. Stage is more
     * specific than Endpoint, which is more specific than Factory, and in addition interactive is more specific than
     * normal.
     *
     * @return the interactive concurrency set up for this factory, or endpoint, or process stage. Will provide the
     *         default unless overridden by {@link #setInteractiveConcurrency(int)} before start.
     * @see #setInteractiveConcurrency(int)
     * @see #isInteractiveConcurrencyDefault()
     * @see #getConcurrency()
     * @see #setConcurrency(int)
     */
    int getInteractiveConcurrency();

    /**
     * Like {@link #setConcurrency(int)}, but changes the "interactive concurrency" specifically - this is relevant for
     * the Mats Flows that are initiated with the {@link MatsInitiate#interactive() interactive} flag set.
     * <p />
     * Setting to 0 will invoke default logic - which is to use the {@link #getConcurrency() normal concurrency}.
     *
     * @param concurrency
     *            the interactive concurrency for the Factory, or Endpoint, or Stage. If set to 0, default-logic is in
     *            effect.
     * @return the config object, for method chaining.
     * @see #getInteractiveConcurrency()
     */
    MatsConfig setInteractiveConcurrency(int concurrency);

    /**
     * @return whether the number provided by {@link #getInteractiveConcurrency()} is using default-logic (when the
     *         concurrency is set to 0) (<code>true</code>), or if it is set specifically (</code>false</code>).
     * @see #getInteractiveConcurrency()
     */
    boolean isInteractiveConcurrencyDefault();

    /**
     * @return whether the Mats entity has been started and not stopped. For the {@link MatsFactory}, it returns true if
     *         any of the endpoints return true (which also implies that if there are no Endpoints registered, it will
     *         return <code>false</code>). For {@link MatsEndpoint}s, it returns true if any stage is running.
     */
    boolean isRunning();

    /**
     * Sets an attribute for this entity (factory, endpoint, stage) - can e.g. be used by tooling or interceptors. If
     * the value is <code>null</code>, the mapping for the specified key is cleared.
     *
     * @param key
     *            the key name for this attribute. Not <code>null</code>.
     * @param value
     *            the value for this attribute. If the value is <code>null</code>, the mapping for the specified key is
     *            cleared.
     * @return the config object, for method chaining.
     */
    MatsConfig setAttribute(String key, Object value);

    /**
     * Returning the attribute value for the specified key, or <code>null</code> if no attribute is set for the key.
     * It uses the hack of generics to avoid explicit casting, but you should really know the type, otherwise let the
     * left-hand side be <code>Object</code>.
     *
     * @param key
     *            the key name for this attribute.
     * @param <T>
     *            the type of this attribute, to avoid explicit casting - but you should really know the type, otherwise
     *            request <code>Object</code>.
     * @return the attribute value.
     * @see #getAttribute(String, Class)
     */
    <T> T getAttribute(String key);

    /**
     * Returning the attribute value for the specified key, wrapped in Optional. If no attribute is set for the key, an
     * empty Optional is returned. If the attribute is set, but is not of the requested type, a ClassCastException is
     * thrown. This method is practical if you want to check if the attribute is set, and then immediately use it, e.g.
     * <code>String name = config.getAttribute("name", String.class).orElse("DefaultName");</code>.
     *
     * @param key
     *            the key name for this attribute.
     * @param <T>
     *            the type of this attribute - or you may request <code>Object</code> if you do not know the type.
     * @return Optional of the attribute value.
     * @see #getAttribute(String)
     */
    default <T> Optional<T> getAttribute(String key, Class<T> type) {
        Object value = getAttribute(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("Attribute with key '" + key + "' is not of type " + type.getName() + ", but "
                    + value.getClass().getName());
        }
        return Optional.of(type.cast(value));
    }

    /**
     * All three of {@link MatsFactory}, {@link MatsEndpoint} and {@link MatsStage} implements this interface.
     */
    interface StartStoppable {
        /**
         * Will start the entity - or the entities below it (the only "active" entity is a {@link MatsStage} Processor).
         * Calling this method when the entity is already running has no effect.
         * <p />
         * Further documentation on extensions - note the special semantics for {@link MatsFactory}
         */
        void start();

        /**
         * If the entity is stopped or starting, this method won't return until it has actually started the receive-loop
         * (i.e. that some {@link MatsStage} Processor has actually entered its receive-loop, consuming messages). If
         * the entity has already gotten into the receive loop, the method immediately returns. (For a
         * {@link MatsFactory}, all its Endpoints must have started ok, for an Endpoint all Stages must have started ok,
         * and for a Stage, at least one its StageProcessors must have started ok)
         * <p />
         * Note: Currently, this only holds for the initial start. If the entity has started the receive-loop at some
         * point, it will always immediately return - even though it is currently stopped.
         * <p />
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
         * <p />
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
