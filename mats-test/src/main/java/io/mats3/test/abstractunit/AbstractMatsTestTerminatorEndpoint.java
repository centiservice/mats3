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

package io.mats3.test.abstractunit;

import io.mats3.MatsEndpoint.ProcessTerminatorLambda;

/**
 * Common base class which consolidates the common logic utilized by:
 * <ul>
 *     <li>Rule_MatsTerminatorEndpoint</li>
 *     <li>Rule_MatsTerminatorStateEndpoint</li>
 *     <li>Extension_MatsTerminatorEndpoint</li>
 *     <li>Extension_MatsTerminatorStateEndpoint</li>
 * </ul>
 * Found in their respective packages <i>Rule_ (junit)</i> and <i>Extension_ (jupiter)</i>.
 * <ul>
 * <li>mats-test-junit</li>
 * <li>mats-test-jupiter</li>
 * </ul>
 * Difference between this and {@link AbstractMatsTestEndpoint} is related to the
 * {@link #setProcessLambda} method, which in the case of this class accepts a {@link ProcessTerminatorLambda}.
 *
 * @author Kevin Mc Tiernan, 2025-04-01, kevin.mc.tiernan@storebrand.no
 * @see AbstractMatsTestEndpoint
 */
public abstract class AbstractMatsTestTerminatorEndpoint<S, I> extends AbstractMatsTestEndpointBase<Void, S, I> {

    /**
     * Base constructor for {@link AbstractMatsTestEndpointBase}, takes all values needed to set up the test endpoint.
     *
     * @param endpointId
     *         Identifier of the endpoint being created.
     * @param stateClass
     *         Class of the state object.
     * @param incomingMsgClass
     *         Class of the incoming message. (Request)
     */
    protected AbstractMatsTestTerminatorEndpoint(String endpointId, Class<S> stateClass, Class<I> incomingMsgClass) {
        super(endpointId, Void.class, stateClass, incomingMsgClass);
    }

    /**
     * Specify the processing lambda to be executed by the endpoint aka the endpoint logic. This is typically invoked
     * either as part of the directly inside a test method to setup the behavior for that specific test or once through
     * the initial setup when creating the test endpoint.
     *
     * @param processLambda
     *            which the endpoint should execute on an incoming request.
     */
    public abstract AbstractMatsTestTerminatorEndpoint<S, I> setProcessLambda(ProcessTerminatorLambda<S, I> processLambda);
}
