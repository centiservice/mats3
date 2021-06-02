package io.mats3.test.metrics;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ForkJoinPool;

import javax.jms.ConnectionFactory;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.api.intercept.MatsInterceptableMatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.MatsTestHelp;
import io.mats3.test.metrics.SetupTestMatsEndpoints.DataTO;
import io.mats3.test.metrics.SetupTestMatsEndpoints.StateTO;
import io.mats3.util_activemq.MatsLocalVmActiveMq;

import ch.qos.logback.core.CoreConstants;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

/**
 * @author Endre Stølsvik 2021-02-17 12:57 - http://stolsvik.com/, endre@stolsvik.com
 */
public class MatsMetricsJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";
    private static final String CONTEXT_ATTRIBUTE_JAVASCRIPT_PATH = "Path to JavaScript files";

    private static final String WEBSOCKET_PATH = "/matssocket";

    private static final String COMMON_AMQ_NAME = "CommonAMQ";

    private static final Logger log = LoggerFactory.getLogger(MatsMetricsJettyServer.class);

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private MatsInterceptableMatsFactory _matsFactory;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            log.info("ServletContextListener.contextInitialized(...): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());

            // ## Create DataSource using H2
            JdbcDataSource h2Ds = new JdbcDataSource();
            h2Ds.setURL("jdbc:h2:~/temp/matsproject_dev_h2database/matssocket_dev"
                    + ";AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            JdbcConnectionPool dataSource = JdbcConnectionPool.create(h2Ds);
            dataSource.setMaxConnections(5);

            // ## Create MatsFactory
            // ActiveMQ ConnectionFactory
            ConnectionFactory connectionFactory = MatsLocalVmActiveMq.createConnectionFactory(COMMON_AMQ_NAME);
            // MatsSerializer
            MatsSerializer<String> matsSerializer = MatsSerializerJson.create();
            // Create the MatsFactory
            _matsFactory = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(
                    MatsMetricsJettyServer.class.getSimpleName(), "*testing*",
                    JmsMatsJmsSessionHandler_Pooling.create(connectionFactory),
                    dataSource,
                    matsSerializer);
            // Configure the MatsFactory for testing (remember, we're running two instances in same JVM)
            // .. Concurrency of only 2
            _matsFactory.getFactoryConfig().setConcurrency(2);
            // .. Use port number of current server as postfix for name of MatsFactory, and of nodename
            Integer portNumber = (Integer) sce.getServletContext().getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);
            _matsFactory.getFactoryConfig().setName("MetricsTestServer_" + portNumber);
            _matsFactory.getFactoryConfig().setNodename("EndreBox_" + portNumber);
            // Put it in ServletContext, for servlet to get
            sce.getServletContext().setAttribute(JmsMatsFactory.class.getName(), _matsFactory);

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
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().println("Hello");
        }
    }

    /**
     * Prometheus metrics.
     */
    @WebServlet("/metrics")
    public static class MetricsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            __prometheusMeterRegistry.scrape(resp.getWriter());
        }
    }

    /**
     * Prometheus metrics.
     */
    @WebServlet("/sendRequest")
    public static class SendRequestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            log.info("Sending request ..");
            resp.getWriter().println("Sending request ..");
            MatsFactory matsFactory = (MatsFactory) req.getServletContext().getAttribute(JmsMatsFactory.class.getName());

            StateTO sto = new StateTO(420, 420.024);
            DataTO dto = new DataTO(42, "TheAnswer");
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (msg) -> msg.traceId(MatsTestHelp.traceId())
                            .keepTrace(KeepTrace.FULL)
                            .from(MatsTestHelp.from("test"))
                            .to(SetupTestMatsEndpoints.SERVICE)
                            .replyTo(SetupTestMatsEndpoints.TERMINATOR, sto)
                            .request(dto));
            resp.getWriter().println(".. Request sent.");
        }
    }


    /**
     * Servlet to shut down this JVM (<code>System.exit(0)</code>). Employed from the Gradle integration tests.
     */
    @WebServlet("/shutdown")
    public static class ShutdownServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.getWriter().println("Shutting down");

            // Shut down the process
            ForkJoinPool.commonPool().submit(() -> System.exit(0));
        }
    }



    public static Server createServer(int port) {
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setBaseResource(Resource.newClassPathResource("webapp"));
        // If any problems starting context, then let exception through so that we can exit.
        webAppContext.setThrowUnavailableOnStartupException(true);
        // Store the port number this server shall run under in the ServletContext.
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);

        // Override the default configurations, stripping down and adding AnnotationConfiguration.
        // https://www.eclipse.org/jetty/documentation/9.4.x/configuring-webapps.html
        // Note: The default resides in WebAppContext.DEFAULT_CONFIGURATION_CLASSES
        webAppContext.setConfigurations(new Configuration[] {
                // new WebInfConfiguration(),
                new WebXmlConfiguration(), // Evidently adds the DefaultServlet, as otherwise no read of "/webapp/"
                // new MetaInfConfiguration(),
                // new FragmentConfiguration(),
                new AnnotationConfiguration() // Adds Servlet annotation processing.
        });

        // :: Get Jetty to Scan project classes too: https://stackoverflow.com/a/26220672/39334
        // Find location for current classes
        URL classesLocation = MatsMetricsJettyServer.class.getProtectionDomain().getCodeSource().getLocation();
        // Set this location to be scanned.
        webAppContext.getMetaData().setWebInfClassesDirs(Collections.singletonList(Resource.newResource(
                classesLocation)));

        // :: Find the path to the JavaScript files (JS tests and MatsSocket.js), to provide them via Servlet.
        String pathToClasses = classesLocation.getPath();
        // .. strip down to the 'mats-websockets' path (i.e. this subproject).
        int pos = pathToClasses.indexOf("mats-websockets");
        String pathToJavaScripts = pos == -1
                ? null
                : pathToClasses.substring(0, pos) + "mats-websockets/client/javascript";
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_JAVASCRIPT_PATH, pathToJavaScripts);

        // Create the actual Jetty Server
        Server server = new Server(port);

        // Add StatisticsHandler (to enable graceful shutdown), put in the WebApp Context
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(webAppContext);
        server.setHandler(stats);

        // Add a Jetty Lifecycle Listener to cleanly shut down the MatsSocketServer.
        server.addLifeCycleListener(new AbstractLifeCycleListener() {
            @Override
            public void lifeCycleStopping(LifeCycle event) {
                log.info("===== STOP! ===========================================");
            }
        });

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
        MatsLocalVmActiveMq.createInVmActiveMq(COMMON_AMQ_NAME);

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

        // Read in the server count as an argument, or assume 2
        int serverCount = (args.length > 0) ? Integer.parseInt(args[0]) : 2;
        // Read in start port to count up from, defaulting to 8080
        int nextPort = (args.length > 1) ? Integer.parseInt(args[0]) : 8080;

        // Start the desired number of servers
        Server[] servers = new Server[serverCount];
        for (int i = 0; i < servers.length; i++) {
            int serverId = i + 1;

            // Keep looping until we have found a free port that the server was able to start on
            while (true) {
                int port = nextPort;
                servers[i] = createServer(port);
                log.info("######### Starting server [" + serverId + "] on [" + port + "]");

                // Add a life cycle hook to log when the server has started
                servers[i].addLifeCycleListener(new AbstractLifeCycleListener() {
                    @Override
                    public void lifeCycleStarted(LifeCycle event) {
                        log.info("######### Started server " + serverId + " on port " + port);
                        // Using System.out to ensure that we get this out, even if logger is ERROR or OFF
                        System.out.println("HOOK_FOR_GRADLE_WEBSOCKET_URL: #[ws://localhost:" + port + WEBSOCKET_PATH
                                + "]#");
                    }
                });

                // Try and start the server on the port we set. If this fails, we will increment the port number
                // and try again.
                try {
                    servers[i].start();
                    break;
                }
                catch (IOException e) {
                    // ?: Check IOException's message whether we failed to bind to the port
                    if (e.getMessage().contains("Failed to bind")) {
                        // Yes -> Log, and try the next port by looping again
                        log.info("######### Failed to start server [" + serverId
                                + "] on [" + port + "], trying next port.", e);
                    }
                    else {
                        // No -> Some other IOException, re-throw to stop the server from starting.
                        throw e;
                    }
                }
                catch (Exception e) {
                    log.error("Jetty failed to start. Need to forcefully System.exit(..) due to Jetty not"
                            + " cleanly taking down its threads.", e);
                    System.exit(2);
                }
                finally {
                    // Always increment the port number
                    nextPort++;
                }
            }
        }
    }
}
