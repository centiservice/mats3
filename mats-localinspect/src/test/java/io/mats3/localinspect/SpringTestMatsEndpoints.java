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

package io.mats3.localinspect;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsEndpoint;
import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsFactory;
import io.mats3.spring.EnableMats;
import io.mats3.spring.MatsClassMapping;
import io.mats3.spring.MatsClassMapping.Stage;
import io.mats3.spring.MatsEndpointSetup;
import io.mats3.spring.MatsMapping;

import jakarta.annotation.PostConstruct;

/**
 * @author Endre Stølsvik 2022-02-21 21:40 - http://stolsvik.com/, endre@stolsvik.com
 */
@EnableMats
public class SpringTestMatsEndpoints {
    private static final Logger log = LoggerFactory.getLogger(SpringTestMatsEndpoints.class);
    @Inject
    private MatsFactory _matsFactory;

    @PostConstruct
    protected void postConstruct() {
        log.info("Postconstructed! MatsFactory: " + _matsFactory);
    }

    @MatsMapping("SpringConfig.matsMapping")
    public DataTO testMatsMapping(DataTO incoming) {
        return null;
    }


    @MatsClassMapping("SpringConfig.matsClassMapping")
    public static class TestMatsClassMapping {
        @Inject
        private MatsFactory _matsFactory;

        private String _state;

        private List<String> _initializedList = new ArrayList<>();

        private ProcessContext<DataTO> _processContext;

        @Stage(Stage.INITIAL)
        public void initial(DataTO in) {
            _processContext.request("StaticTest.elg", in);
        }

        @Stage(10)
        public DataTO last(DataTO in) {
            return in;
        }
    }

    @MatsEndpointSetup(endpointId = "SpringConfig.matsEndpointSetup", reply = DataTO.class, state = StateTO.class)
    public void setup(MatsEndpoint<DataTO, StateTO> ep) {
        ep.stage(DataTO.class, (ctx, state, msg) -> {
            ctx.request("SpringConfig.matsMapping", msg);
        });
        ep.lastStage(DataTO.class, (ctx, state, msg) -> {
            return msg;
        });
    }
}
