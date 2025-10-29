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

package io.mats3.test.metrics;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Collections;

import jakarta.jms.ConnectionFactory;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee11.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee11.webapp.Configuration;
import org.eclipse.jetty.ee11.webapp.WebAppConfiguration;
import org.eclipse.jetty.ee11.webapp.WebAppContext;
import org.eclipse.jetty.ee11.webapp.WebXmlConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.api.intercept.MatsMetricsInterceptor;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.intercept.micrometer.MatsMicrometerInterceptor.SuggestedTimingHistogramsMeterFilter;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.test.metrics.SetupTestMatsEndpoints.DataTO;
import io.mats3.test.metrics.SetupTestMatsEndpoints.StateTO;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.exporter.servlet.jakarta.PrometheusMetricsServlet;

import ch.qos.logback.core.CoreConstants;

/**
 * @author Endre Stølsvik 2021-02-17 12:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsMetrics_TestJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final Logger log = LoggerFactory.getLogger(MatsMetrics_TestJettyServer.class);

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private MatsFactory _matsFactory;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            log.info("ServletContextListener.contextInitialized(...): " + sce);
            ServletContext sc = sce.getServletContext();
            log.info("  \\- ServletContext: " + sc);

            // ## Create DataSource using H2
            JdbcDataSource h2Ds = new JdbcDataSource();
            h2Ds.setURL("jdbc:h2:~/temp/matsproject_dev_h2database/matssocket_dev"
                    + ";AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            JdbcConnectionPool dataSource = JdbcConnectionPool.create(h2Ds);
            dataSource.setMaxConnections(5);

            // ## Create MatsFactory
            // Get JMS ConnectionFactory from ServletContext
            ConnectionFactory connFactory = (ConnectionFactory) sc.getAttribute(ConnectionFactory.class.getName());
            // MatsSerializer
            MatsSerializer matsSerializer = MatsSerializerJson.create();
            // Create the MatsFactory
            _matsFactory = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(
                    MatsMetrics_TestJettyServer.class.getSimpleName(), "*testing*",
                    JmsMatsJmsSessionHandler_Pooling.create(connFactory),
                    dataSource,
                    matsSerializer);
            // Configure the MatsFactory for testing (remember, we're running two instances in same JVM)
            // .. Concurrency of only 2
            _matsFactory.getFactoryConfig().setConcurrency(2);
            // .. Use port number of current server as postfix for name of MatsFactory, and of nodename
            Integer portNumber = (Integer) sc.getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);
            _matsFactory.getFactoryConfig().setName("MetricsTestServer_" + portNumber);
            _matsFactory.getFactoryConfig().setNodename("EndreBox_" + portNumber);
            // Put it in ServletContext, for servlet to get
            sc.setAttribute(JmsMatsFactory.class.getName(), _matsFactory);

            SetupTestMatsEndpoints.setupMatsAndMatsSocketEndpoints(_matsFactory);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("ServletContextListener.contextDestroyed(..): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());
            _matsFactory.stop(5000);
        }
    }

    /**
     * Menu.
     */
    @WebServlet("/")
    public static class RootServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/html; charset=utf-8");
            PrintWriter out = res.getWriter();
            out.println("<h1>Menu</h1>");
            out.println("<a href=\"./sendRequest\">Send Mats requests</a><br />");
            out.println("<a href=\"./sendRequest?suppressMetrics\">Send Mats requests, but suppress metrics</a>"
                    + " (But: Endpoint.Leaf and Terminator does not allow suppression)<br />");
            out.println("<a href=\"./metrics\">Metrics Prometheus Scrape</a><br />");
            out.println("<a href=\"./shutdown\">Shutdown</a><br />");
        }
    }

    /**
     * Prometheus metrics - utilizing servlet from `io.prometheus:prometheus-metrics-exporter-servlet-jakarta`
     */
    @WebServlet("/metrics")
    public static class MetricsServlet extends PrometheusMetricsServlet {
        public MetricsServlet() {
            // Notice that this constructor uses PrometheusProperties loaded via PrometheusPropertiesLoader.
            super(__prometheusMeterRegistry.getPrometheusRegistry());
        }
    }

    /**
     * Send Mats request.
     */
    @WebServlet("/sendRequest")
    public static class SendRequestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setContentType("text/html; charset=utf-8");
            log.info("Sending request ..");

            PrintWriter out = res.getWriter();
            out.println("Sending request ...<br>");
            MatsFactory matsFactory = (MatsFactory) req.getServletContext().getAttribute(JmsMatsFactory.class
                    .getName());

            // Should we suppress metrics?
            boolean suppressMetrics = req.getParameter("suppressMetrics") != null;
            if (suppressMetrics) {
                out.println("<b>.. adding suppress-metrics TraceProperty..</b><br>");
            }

            StateTO sto = new StateTO(420, 420.024);
            DataTO dto = new DataTO(42, "TheAnswer");
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> {
                        msg.logMeasurement("test.initmeasure1", "Test measurement 1 from initiation", "baseUnit1", Math.PI);
                        msg.logMeasurement("test.initmeasure2", "Test measurement 2 from initiation", "baseUnit2", Math.PI,
                                "labelKeyA", "labelValueA");
                        msg.logMeasurement("test.initmeasure3", "Test measurement 3 from initiation", "baseUnit3", Math.PI,
                                "labelKeyA", "labelValueA", "labelKeyB", "labelValueB");

                        msg.logTimingMeasurement("test.inittiming1", "Test TIMING 1 from initiation", 1_000);
                        msg.logTimingMeasurement("test.inittiming2", "Test TIMING 2 from initiation", 1_000_000,
                                "labelKeyA", "labelValueA");
                        msg.logTimingMeasurement("test.inittiming3", "Test TIMING 3 from initiation",
                                1_000_000_000, "labelKeyA", "labelValueA", "labelKeyB", "labelValueB");

                        if (suppressMetrics) {
                            msg.setTraceProperty(MatsMetricsInterceptor.SUPPRESS_METRICS_TRACE_PROPERTY_KEY, true);
                        }

                        msg.traceId(MatsTestHelp.traceId())
                                .keepTrace(KeepTrace.FULL)
                                .from("/sendRequestInitiated")
                                .to(SetupTestMatsEndpoints.ENDPOINT)
                                .replyTo(SetupTestMatsEndpoints.TERMINATOR, sto)
                                .request(dto);
                    });
            out.println(".. Request sent.<br>");
            out.println("<a href=\"./\">Back</a>");
        }
    }

    public static Server createServer(ConnectionFactory jmsConnectionFactory, int port) {
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        ResourceFactory resourceFactory = ResourceFactory.of(webAppContext);
        webAppContext.setBaseResource(resourceFactory.newClassLoaderResource("webapp"));
        // If any problems starting context, then let exception through so that we can exit.
        webAppContext.setThrowUnavailableOnStartupException(true);
        // Store the port number this server shall run under in the ServletContext.
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);
        // Store the JMS ConnectionFactory in the ServletContext
        webAppContext.getServletContext().setAttribute(ConnectionFactory.class.getName(), jmsConnectionFactory);

        // Override the default configurations, stripping down and adding AnnotationConfiguration.
        // Full list: jetty-ee11-webapp-12.1.2.jar!/META-INF/services/org.eclipse.jetty.ee11.webapp.Configuration
        // .. plus: jetty-ee11-annotations-12.1.2.jar!/META-INF/services/org.eclipse.jetty.ee11.webapp.Configuration
        webAppContext.setConfigurations(new Configuration[] {
                new WebAppConfiguration(), // Exposes the o.e.j.ee11.servlet.listener.IntrospectorCleaner class!
                new WebXmlConfiguration(), // Evidently adds the DefaultServlet, as otherwise no read of "/webapp/"
                new AnnotationConfiguration() // Adds Servlet annotation processing.
        });

        // :: Get Jetty to Scan project classes too: https://stackoverflow.com/a/26220672/39334
        // Find location for current classes
        URL classesLocation = MatsMetrics_TestJettyServer.class.getProtectionDomain().getCodeSource().getLocation();
        // Set this location to be scanned.
        webAppContext.getMetaData().setWebInfClassesResources(Collections.singletonList(resourceFactory.newResource(
                classesLocation)));

        // Create the actual Jetty Server
        Server server = new Server(port);

        // Add StatisticsHandler (to enable graceful shutdown), put in the WebApp Context
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(webAppContext);
        server.setHandler(stats);

        // :: Graceful shutdown
        server.setStopTimeout(1000);
        server.setStopAtShutdown(true);
        return server;
    }

    private static PrometheusMeterRegistry __prometheusMeterRegistry;

    public static void main(String... args) throws Exception {
        // Turn off LogBack's absurd SCI
        System.setProperty(CoreConstants.DISABLE_SERVLET_CONTAINER_INITIALIZER_KEY, "true");

        // Create common AMQ
        MatsTestBroker matsTestBroker = MatsTestBroker.createUniqueInVmActiveMq();
        ConnectionFactory jmsConnectionFactory = matsTestBroker.getConnectionFactory();

        // ## Configure Micrometer
        __prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        Metrics.addRegistry(__prometheusMeterRegistry);

        // new LogbackMetrics().bindTo(Metrics.globalRegistry);
        // new JvmThreadMetrics().bindTo(Metrics.globalRegistry);
        // new JvmGcMetrics().bindTo(Metrics.globalRegistry);
        // new JvmMemoryMetrics().bindTo(Metrics.globalRegistry);
        // new DiskSpaceMetrics(new File("/")).bindTo(Metrics.globalRegistry);
        // new ProcessorMetrics().bindTo(Metrics.globalRegistry); // metrics related to the CPU stats
        // new UptimeMetrics().bindTo(Metrics.globalRegistry);

        Metrics.globalRegistry.config().meterFilter(new SuggestedTimingHistogramsMeterFilter());

        Server server = createServer(jmsConnectionFactory, 8080);
        try {
            server.start();
        }
        catch (Exception e) {
            log.error("Problems starting Jetty", e);
            server.stop();
            matsTestBroker.close();
        }
    }
}
