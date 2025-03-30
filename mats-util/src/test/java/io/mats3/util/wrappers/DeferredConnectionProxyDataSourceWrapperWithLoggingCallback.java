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

package io.mats3.util.wrappers;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;

import io.mats3.test.MatsTestHelp;

/**
 * Test-Extension of the Wrapper which facilitates testing, enabling fine-grained assertions.
 *
 * @author Endre Stølsvik 2021-01-29 23:05 - http://stolsvik.com/, endre@stolsvik.com
 */

public class DeferredConnectionProxyDataSourceWrapperWithLoggingCallback extends
        DeferredConnectionProxyDataSourceWrapper {
    private static final Logger log = MatsTestHelp.getClassLogger();

    public DeferredConnectionProxyDataSourceWrapperWithLoggingCallback(DataSource dataSource) {
        super(dataSource);
    }

    private boolean _actualConnectionWasRetrieved;
    private Connection _actualConnectionRetrieved;
    private final List<MethodInvoked> _methodInvokes = new ArrayList<>();

    @Override
    protected void actualConnectionWasRetrieved(DeferredConnectionProxy connectionProxy, Connection actualConnection) {
        super.actualConnectionWasRetrieved(connectionProxy, actualConnection);
        _actualConnectionRetrieved = actualConnection;
        _actualConnectionWasRetrieved = true;
    }

    @Override
    protected void methodInvoked(boolean answeredByProxy, DeferredConnectionProxy connectionProxy,
            Connection actualConnection, Method method, Object[] args, Object result) {
        log.info("Method invoked: [" + method.getName() + "(" + (args == null ? "" : Arrays.asList(args)) + ")],"
                + " with result [" + result + "], answered by [" + (answeredByProxy ? "PROXY" : "ACTUAL Connection")
                + "], on actual Connection [" + actualConnection + "].");
        _methodInvokes.add(new MethodInvoked(answeredByProxy, connectionProxy, actualConnection, method, args, result));
    }

    public boolean wasActualConnectionRetrieved() {
        return _actualConnectionWasRetrieved;
    }

    public Connection getActualConnectionRetrieved() {
        return _actualConnectionRetrieved;
    }

    public void clearWasActualConnectionRetrieved() {
        _actualConnectionWasRetrieved = false;
        _actualConnectionRetrieved = null;
    }

    public List<MethodInvoked> getMethodsInvoked() {
        return _methodInvokes;
    }

    public void clearMethodsInvoked() {
        _methodInvokes.clear();
    }

    public static class MethodInvoked {
        private final boolean _answeredByProxy;
        private final DeferredConnectionProxy _connectionProxy;
        private final Connection _actualConnection;
        private final Method _method;
        private final Object[] _args;
        private final Object _result;

        public MethodInvoked(boolean answeredByProxy,
                DeferredConnectionProxy connectionProxy, Connection actualConnection, Method method, Object[] args,
                Object result) {
            _answeredByProxy = answeredByProxy;
            _connectionProxy = connectionProxy;
            _actualConnection = actualConnection;
            _method = method;
            _args = args;
            _result = result;
        }

        public boolean isAnsweredByProxy() {
            return _answeredByProxy;
        }

        public DeferredConnectionProxy getConnectionProxy() {
            return _connectionProxy;
        }

        public Connection getActualConnection() {
            return _actualConnection;
        }

        public Method getMethod() {
            return _method;
        }

        public Object[] getArgs() {
            return _args;
        }

        public Object getResult() {
            return _result;
        }
    }
}
