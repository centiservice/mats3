package io.mats3.localinspect;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;

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
import io.mats3.localinspect.SetupTestMatsEndpoints.DataTO;
import io.mats3.localinspect.SetupTestMatsEndpoints.StateTO;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;
import io.mats3.util_activemq.MatsLocalVmActiveMq;

import ch.qos.logback.core.CoreConstants;

/**
 * Test server for the HTML interface generation. Pulling up a bunch of endpoints, and having a traffic generator to put
 * them to some use.
 *
 * @author Endre Stølsvik 2021-03-25 12:29 - http://stolsvik.com/, endre@stolsvik.com
 */
public class LocalHtmlInspectTestJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final String COMMON_AMQ_NAME = "CommonAMQ";

    private static final Logger log = LoggerFactory.getLogger(LocalHtmlInspectTestJettyServer.class);

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
                    LocalHtmlInspectTestJettyServer.class.getSimpleName(), "*testing*",
                    JmsMatsJmsSessionHandler_Pooling.create(connectionFactory),
                    dataSource,
                    matsSerializer);

            // Hold start
            _matsFactory.holdEndpointsUntilFactoryIsStarted();

            // Configure the MatsFactory for testing
            _matsFactory.getFactoryConfig().setConcurrency(SetupTestMatsEndpoints.BASE_CONCURRENCY);
            // .. Use port number of current server as postfix for name of MatsFactory, and of nodename
            Integer portNumber = (Integer) sce.getServletContext().getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);
            _matsFactory.getFactoryConfig().setName(LocalHtmlInspectTestJettyServer.class.getSimpleName()
                    + "_" + portNumber);
            _matsFactory.getFactoryConfig().setNodename("EndreBox_" + portNumber);

            // Put it in ServletContext, for servlet to get
            sce.getServletContext().setAttribute(JmsMatsFactory.class.getName(), _matsFactory);

            // Install the stats keeper interceptor
            LocalStatsMatsInterceptor.install(_matsFactory);

            // Create the HTML interfaces, and store them in the ServletContext attributes, for the rendering Servlet.
            LocalHtmlInspectForMatsFactory interface1 = LocalHtmlInspectForMatsFactory.create(_matsFactory);
            LocalHtmlInspectForMatsFactory interface2 = LocalHtmlInspectForMatsFactory.create(_matsFactory);
            sce.getServletContext().setAttribute("interface1", interface1);
            sce.getServletContext().setAttribute("interface2", interface2);

            // Create MatsFuturizer, and store then in the ServletContext attributes, for sending in testServlet
            MatsFuturizer matsFuturizer = MatsFuturizer.createMatsFuturizer(_matsFactory,
                    SetupTestMatsEndpoints.SERVICE_PREFIX);
            sce.getServletContext().setAttribute(MatsFuturizer.class.getName(), matsFuturizer);

            // :: Set up Mats Test Endpoints.
            SetupTestMatsEndpoints.setupMatsTestEndpoints(_matsFactory);

            _matsFactory.start();
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("ServletContextListener.contextDestroyed(..): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());
            _matsFactory.stop(5000);
        }
    }

    /**
     * RenderingServlet
     */
    @WebServlet("/")
    public static class RootServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/html; charset=utf-8");
            LocalHtmlInspectForMatsFactory interface1 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("interface1");
            LocalHtmlInspectForMatsFactory interface2 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("interface2");

            boolean includeBootstrap3 = req.getParameter("includeBootstrap3") != null;
            boolean includeBootstrap4 = req.getParameter("includeBootstrap4") != null;
            boolean includeBootstrap5 = req.getParameter("includeBootstrap5") != null;

            PrintWriter out = resp.getWriter();
            out.println("<html>");
            out.println("  <head>");
            if (includeBootstrap3) {
                out.println("    <script src=\"https://code.jquery.com/jquery-1.10.1.min.js\"></script>\n");
                out.println("    <link rel=\"stylesheet\" href=\"https://netdna.bootstrapcdn.com/"
                        + "bootstrap/3.0.2/css/bootstrap.min.css\" />\n");
                out.println("    <script src=\"https://netdna.bootstrapcdn.com/"
                        + "bootstrap/3.0.2/js/bootstrap.min.js\"></script>\n");
            }
            if (includeBootstrap4) {
                out.println("    <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@4.6.0/dist/css/bootstrap.min.css\""
                        + " integrity=\"sha384-B0vP5xmATw1+K9KRQjQERJvTumQW0nPEzvF6L/Z6nronJ3oUOFUFpCjEUQouq2+l\""
                        + " crossorigin=\"anonymous\">\n");
                out.println("<script src=\"https://code.jquery.com/jquery-3.5.1.slim.min.js\""
                        + " integrity=\"sha384-DfXdz2htPH0lsSSs5nCTpuj/zy4C+OGpamoFVy38MVBnE+IbbVYUew+OrCXaRkfj\""
                        + " crossorigin=\"anonymous\"></script>\n");
                out.println("<script src=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@4.6.0/dist/js/bootstrap.bundle.min.js\""
                        + " integrity=\"sha384-Piv4xVNRyMGpqkS2by6br4gNJ7DXjqk09RmUpJ8jgGtD7zP9yug3goQfGII0yAns\""
                        + " crossorigin=\"anonymous\"></script>\n");
            }
            if (includeBootstrap5) {
                out.println("<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@5.0.0-beta3/dist/css/bootstrap.min.css\""
                        + " integrity=\"sha384-eOJMYsd53ii+scO/bJGFsiCZc+5NDVN2yr8+0RDqr0Ql0h+rP48ckxlpbzKgwra6\""
                        + " crossorigin=\"anonymous\">\n");
                out.println("<script src=\"https://cdn.jsdelivr.net/"
                        + "npm/bootstrap@5.0.0-beta3/dist/js/bootstrap.bundle.min.js\""
                        + " integrity=\"sha384-JEW9xMcG8R+pH31jmWH6WWP0WintQrMb4s7ZOdauHnUtxwoG2vI5DkLtS3qm9Ekf\""
                        + " crossorigin=\"anonymous\"></script>\n");
            }
            out.println("  </head>");
            // NOTE: Setting "margin: 0" just to be able to compare against the Bootstrap-versions without too
            // much "accidental difference" due to the Bootstrap's setting of margin=0.
            out.println("  <body style=\"margin: 0;\">");
            out.println("    <style>");
            interface1.getStyleSheet(out); // Include just once, use the first.
            out.println("    </style>");
            out.println("    <script>");
            interface1.getJavaScript(out); // Include just once, use the first.
            out.println("    </script>");
            // out.println(" <h1>Test h1</h1>");
            // out.println(" Endre tester h1");
            //
            // out.println(" <h2>Test h2</h2>");
            // out.println(" Endre tester h2");
            //
            // out.println(" <h3>Test h3</h3>");
            // out.println(" Endre tester h3");
            //
            // out.println(" <h4>Test h4</h4>");
            // out.println(" Endre tester h4<br /><br />");
            //
            // out.println(" <a href=\"sendRequest\">Send request</a> - to initialize Initiator"
            // + " and get some traffic.<br /><br />");

            // :: Bootstrap3 sets the body's font size to 14px.
            // We scale all the affected rem-using elements back up to check consistency.
            if (includeBootstrap3) {
                out.write("<div style=\"font-size: 114.29%\">\n");
            }
            interface1.createFactoryReport(out, true, true, true);
            interface2.createFactoryReport(out, true, true, true);
            if (includeBootstrap3) {
                out.write("</div>\n");
            }

            out.println("  </body>");
            out.println("</html>");
        }
    }

    /**
     * SEND A REQUEST THROUGH THE MATS SERVICE.
     */
    @WebServlet("/sendRequest")
    public static class SendRequestServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            long nanosAsStart_entireProcedure = System.nanoTime();
            log.info("Sending request ..");
            PrintWriter out = resp.getWriter();
            out.println("Sending request ..");
            MatsFactory matsFactory = (MatsFactory) req.getServletContext().getAttribute(JmsMatsFactory.class
                    .getName());

            StateTO sto = new StateTO(420, 420.024);
            DataTO dto = new DataTO(42, "TheAnswer");
            matsFactory.getDefaultInitiator().initiateUnchecked(
                    (init) -> {
                        for (int i = 0; i < 200; i++) {
                            init.traceId("traceId" + i)
                                    .keepTrace(KeepTrace.MINIMAL)
                                    .nonPersistent()
                                    .from("LocalInterfaceTest.initiator")
                                    .to(SetupTestMatsEndpoints.SERVICE)
                                    .replyTo(SetupTestMatsEndpoints.TERMINATOR, sto)
                                    .request(dto);
                        }
                    });
            out.println(".. Request sent.");

            out.println("\nPerforming MatsFuturizers..");

            MatsFuturizer matsFuturizer = (MatsFuturizer) req.getServletContext()
                    .getAttribute(MatsFuturizer.class.getName());

            out.println("\nTo " + SetupTestMatsEndpoints.SERVICE);
            CompletableFuture<Reply<DataTO>> future1 = matsFuturizer.futurizeNonessential(
                    "TestTraceId" + Long.toHexString(Math.abs(ThreadLocalRandom.current().nextLong())),
                    SetupTestMatsEndpoints.SERVICE_PREFIX + ".FuturizerTest_Main",
                    SetupTestMatsEndpoints.SERVICE, DataTO.class, new DataTO(1, "To_SERVICE"));
            try {
                Reply<DataTO> reply = future1.get();
                out.println("-> got reply: " + reply.getReply());
            }
            catch (InterruptedException | ExecutionException e) {
                out.println("Failed! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            out.println("\nTo " + SetupTestMatsEndpoints.SERVICE_MID);
            CompletableFuture<Reply<DataTO>> future2 = matsFuturizer.futurizeNonessential(
                    "TestTraceId" + Long.toHexString(Math.abs(ThreadLocalRandom.current().nextLong())),
                    SetupTestMatsEndpoints.SERVICE_PREFIX + ".FuturizerTest_Mid",
                    SetupTestMatsEndpoints.SERVICE_MID, DataTO.class, new DataTO(2, "TO_MID"));
            try {
                Reply<DataTO> reply = future2.get();
                out.println("-> got reply: " + reply.getReply());
            }
            catch (InterruptedException | ExecutionException e) {
                out.println("Failed! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            out.println("\nTo " + SetupTestMatsEndpoints.SERVICE_LEAF);
            CompletableFuture<Reply<DataTO>> future3 = matsFuturizer.futurizeNonessential(
                    "TestTraceId" + Long.toHexString(Math.abs(ThreadLocalRandom.current().nextLong())),
                    SetupTestMatsEndpoints.SERVICE_PREFIX + ".FuturizerTest_Leaf",
                    SetupTestMatsEndpoints.SERVICE_LEAF, DataTO.class, new DataTO(3, "TO_LEAF"));
            try {
                Reply<DataTO> reply = future3.get();
                out.println("-> got reply: " + reply.getReply());
            }
            catch (InterruptedException | ExecutionException e) {
                out.println("Failed! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.println("\n.. Futurizations done.\n");

            out.println("\n.. Send directly to Terminator.\n");
            // Perform a send directly to the Terminator
            matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_terminator")
                    .to(SetupTestMatsEndpoints.TERMINATOR)
                    .send(dto));

            out.println("\n.. Send 'null' directly to Terminator.\n");
            matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_terminator")
                    .to(SetupTestMatsEndpoints.TERMINATOR)
                    .send(null));

            out.println("\n.. Publish directly to SubscriptionTerminator.\n");
            // Perform a publish directly to the SubscriptionTerminator
            matsFactory.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_subscriptionTerminator")
                    .to(SetupTestMatsEndpoints.SUBSCRIPTION_TERMINATOR)
                    .publish(dto));

            long nanosTaken_TotalProcess = System.nanoTime() - nanosAsStart_entireProcedure;
            out.println("\nAll done - time taken: " + (nanosTaken_TotalProcess / 1_000_000d) + " ms.\n");
        }
    }

    /**
     * Servlet to shut down this JVM (<code>System.exit(0)</code>).
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
        URL classesLocation = LocalHtmlInspectTestJettyServer.class.getProtectionDomain().getCodeSource()
                .getLocation();
        // Set this location to be scanned.
        webAppContext.getMetaData().setWebInfClassesDirs(Collections.singletonList(Resource.newResource(
                classesLocation)));

        // Create the actual Jetty Server
        Server server = new Server(port);

        // Add StatisticsHandler (to enable graceful shutdown), put in the WebApp Context
        StatisticsHandler stats = new StatisticsHandler();
        stats.setHandler(webAppContext);
        server.setHandler(stats);

        // Add a Jetty Lifecycle Listener to cleanly shut down stuff
        server.addLifeCycleListener(new AbstractLifeCycleListener() {
            @Override
            public void lifeCycleStarted(LifeCycle event) {
                log.info("######### Started server on port " + port);
            }

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

    public static void main(String... args) throws Exception {
        // Turn off LogBack's absurd SCI
        System.setProperty(CoreConstants.DISABLE_SERVLET_CONTAINER_INITIALIZER_KEY, "true");

        // Create common AMQ server
        MatsLocalVmActiveMq.createInVmActiveMq(COMMON_AMQ_NAME);

        Server server = createServer(8080);
        server.start();
    }
}
