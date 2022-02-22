package io.mats3.localinspect;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.jms.ConnectionFactory;
import javax.servlet.ServletContext;
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
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.component.LifeCycle.Listener;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import ch.qos.logback.core.CoreConstants;
import io.mats3.MatsFactory;
import io.mats3.MatsInitiator.KeepTrace;
import io.mats3.api.intercept.MatsInterceptableMatsFactory;
import io.mats3.impl.jms.JmsMatsFactory;
import io.mats3.impl.jms.JmsMatsJmsSessionHandler_Pooling;
import io.mats3.serial.MatsSerializer;
import io.mats3.serial.json.MatsSerializerJson;
import io.mats3.test.broker.MatsTestBroker;
import io.mats3.util.MatsFuturizer;
import io.mats3.util.MatsFuturizer.Reply;

/**
 * Test server for the HTML interface generation. Pulling up a bunch of endpoints, and having a traffic generator to put
 * them to some use.
 *
 * @author Endre Stølsvik 2021-03-25 12:29 - http://stolsvik.com/, endre@stolsvik.com
 */
public class LocalHtmlInspect_TestJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final Logger log = LoggerFactory.getLogger(LocalHtmlInspect_TestJettyServer.class);

    private static final String SERVICE_ORDER = "OrderService";
    private static final String SERVICE_DISPATCH = "DispatchService";
    private static final String SERVICE_DELIVERY = "DeliveryService";

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private MatsInterceptableMatsFactory _matsFactory1;
        private MatsInterceptableMatsFactory _matsFactory2;
        private MatsFuturizer _matsFuturizer1;
        private MatsFuturizer _matsFuturizer2;

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

            // Get JMS ConnectionFactory from ServletContext
            ConnectionFactory connFactory = (ConnectionFactory) sc.getAttribute(ConnectionFactory.class.getName());

            // MatsSerializer
            MatsSerializer<String> matsSerializer = MatsSerializerJson.create();

            // .. Use port number of current server as postfix for nodename
            Integer port = (Integer) sce.getServletContext().getAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER);

            // ## Create MatsFactory 1
            // Create the MatsFactory
            _matsFactory1 = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(
                    SERVICE_ORDER, "*testing*",
                    JmsMatsJmsSessionHandler_Pooling.create(connFactory),
                    dataSource,
                    matsSerializer);
            // Hold start
            _matsFactory1.holdEndpointsUntilFactoryIsStarted();
            _matsFactory1.getFactoryConfig().setName("FactoryName1");
            // Configure the MatsFactory for testing
            _matsFactory1.getFactoryConfig().setConcurrency(3);
            _matsFactory1.getFactoryConfig().setNodename(_matsFactory1.getFactoryConfig().getNodename() + "_" + port);

            // ## Create MatsFactory 2
            // Create the MatsFactory
            _matsFactory2 = JmsMatsFactory.createMatsFactory_JmsAndJdbcTransactions(
                    SERVICE_DELIVERY, "*testing*",
                    JmsMatsJmsSessionHandler_Pooling.create(connFactory),
                    dataSource,
                    matsSerializer);
            // Hold start
            _matsFactory2.holdEndpointsUntilFactoryIsStarted();
            _matsFactory2.getFactoryConfig().setName("FactoryName2");
            // Configure the MatsFactory for testing
            _matsFactory2.getFactoryConfig().setConcurrency(2);
            _matsFactory2.getFactoryConfig().setNodename(_matsFactory2.getFactoryConfig().getNodename() + "_" + port);

            // Put it in ServletContext, for servlet to get
            sce.getServletContext().setAttribute("matsFactory1", _matsFactory1);
            sce.getServletContext().setAttribute("matsFactory2", _matsFactory2);

            // Install the stats keeper interceptor
            LocalStatsMatsInterceptor.install(_matsFactory1);
            LocalStatsMatsInterceptor.install(_matsFactory2);

            // Create the HTML interfaces, and store them in the ServletContext attributes, for the rendering Servlet.
            LocalHtmlInspectForMatsFactory interface1 = LocalHtmlInspectForMatsFactory.create(_matsFactory1);
            LocalHtmlInspectForMatsFactory interface2 = LocalHtmlInspectForMatsFactory.create(_matsFactory2);
            sce.getServletContext().setAttribute("interface1", interface1);
            sce.getServletContext().setAttribute("interface2", interface2);

            // Create MatsFuturizer, and store then in the ServletContext attributes, for sending in testServlet
            _matsFuturizer1 = MatsFuturizer.createMatsFuturizer(_matsFactory1, SERVICE_ORDER);
            _matsFuturizer2 = MatsFuturizer.createMatsFuturizer(_matsFactory2, SERVICE_DELIVERY);
            sce.getServletContext().setAttribute("matsFuturizer1", _matsFuturizer1);
            sce.getServletContext().setAttribute("matsFuturizer2", _matsFuturizer2);

            // :: Set up Mats Test Endpoints.
            SetupTestMatsEndpoints setup = new SetupTestMatsEndpoints();
            setup.setupMatsTestEndpoints(_matsFactory1, SERVICE_ORDER, 3);
            setup.setupMatsTestEndpoints(_matsFactory1, SERVICE_DISPATCH, 3);
            setup.setupMatsTestEndpoints(_matsFactory2, SERVICE_DELIVERY, 2);

            // Fire up a Spring context
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            ctx.register(SpringTestMatsEndpoints.class);
            ctx.getBeanFactory().registerSingleton("matsFactory", _matsFactory1);
            ctx.refresh();

            _matsFactory1.start();
            _matsFactory2.start();
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("ServletContextListener.contextDestroyed(..): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());
            _matsFactory1.stop(5000);
            _matsFactory2.stop(5000);
            _matsFuturizer1.close();
            _matsFuturizer2.close();
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
            out.println("<!DOCTYPE html>");
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
            out.println("<h1>Initiate Mats flow</h1>");
            out.println(" <a href=\"sendRequest\">Send request</a> - to initialize Initiator"
                    + " and get some traffic.<br /><br />");

            // :: Bootstrap3 sets the body's font size to 14px.
            // We scale all the affected rem-using elements back up to check consistency.
            if (includeBootstrap3) {
                out.write("<div style=\"font-size: 114.29%\">\n");
            }
            out.println("<h2>Summary for Factory 1</h2>");
            interface1.createFactorySummary(out, true, true);
            out.println("<h2>Summary for Factory 2</h2>");
            interface2.createFactorySummary(out, true, true);
            out.println("<h1>Introspection GUI 1</h1>");
            interface1.createFactoryReport(out, true, true, true);
            out.println("<h1>Introspection GUI 2</h1>");
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
            PrintWriter out = resp.getWriter();
            out.println("Sending bunch of requests");
            out.flush();
            MatsFactory matsFactory1 = (MatsFactory) req.getServletContext().getAttribute("matsFactory1");
            MatsFactory matsFactory2 = (MatsFactory) req.getServletContext().getAttribute("matsFactory2");
            sendSomeRequests(out, matsFactory1, SERVICE_ORDER);
            sendSomeRequests(out, matsFactory1, SERVICE_DISPATCH);
            sendSomeRequests(out, matsFactory1, SERVICE_DELIVERY);
            sendSomeRequests(out, matsFactory2, SERVICE_ORDER);
            sendSomeRequests(out, matsFactory2, SERVICE_DISPATCH);
            double msTaken_TotalProcess = (System.nanoTime() - nanosAsStart_entireProcedure) / 1_000_000d;

            out.println("REQUESTS SENT, time taken: [" + msTaken_TotalProcess + " ms]");
            out.flush();

            out.println("\nPerforming MatsFuturizers..");

            MatsFuturizer matsFuturizer = (MatsFuturizer) req.getServletContext().getAttribute("matsFuturizer1");

            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE, matsFuturizer, "INTERACTIVE_To_Main", true);
            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE_MID, matsFuturizer, "INTERACTIVE_To_Mid", true);
            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE_LEAF, matsFuturizer, "INTERACTIVE_To_Leaf", true);

            out.println("\n.. Futurizations done.\n");

            DataTO directDto = new DataTO(42, "TheAnswer");

            out.println("\n.. Send directly to Terminator.\n");
            // Perform a send directly to the Terminator
            matsFactory1.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_terminator")
                    .to(SERVICE_ORDER + SetupTestMatsEndpoints.TERMINATOR)
                    .send(directDto));

            out.println("\n.. Send 'null' directly to Terminator.\n");
            matsFactory1.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_terminator")
                    .to(SERVICE_ORDER + SetupTestMatsEndpoints.TERMINATOR)
                    .send(null));

            out.println("\n.. Publish directly to SubscriptionTerminator.\n");
            // Perform a publish directly to the SubscriptionTerminator
            matsFactory1.getDefaultInitiator().initiateUnchecked(init -> init.traceId("traceId_directToTerminator")
                    .keepTrace(KeepTrace.MINIMAL)
                    .nonPersistent()
                    .from("LocalInterfaceTest.initiator_direct_to_subscriptionTerminator")
                    .to(SERVICE_ORDER + SetupTestMatsEndpoints.SUBSCRIPTION_TERMINATOR)
                    .publish(directDto));

            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE, matsFuturizer, "INTERACTIVE_To_Main", true);
            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE_MID, matsFuturizer, "INTERACTIVE_To_Mid", true);
            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE_LEAF, matsFuturizer, "INTERACTIVE_To_Leaf", true);

            futurizeSingle(out, SetupTestMatsEndpoints.SERVICE, matsFuturizer, "normal_To_Main", false);

            long nanosTaken_TotalProcess = System.nanoTime() - nanosAsStart_entireProcedure;
            out.println("\nAll done - time taken: " + (nanosTaken_TotalProcess / 1_000_000d) + " ms.\n");
        }

        private void sendSomeRequests(PrintWriter out, MatsFactory matsFactory, String applicationName) {
            out.println("Sending request ..");
            StateTO sto = new StateTO(420, 420.024);
            DataTO dto = new DataTO(42, "TheAnswer");
            for (int i = 1; i <= 10; i++) {
                int finalI = i;
                matsFactory.getDefaultInitiator().initiateUnchecked(
                        (init) -> {
                            for (int j = 1; j <= 20; j++) {
                                init.traceId("traceId" + (finalI * j - 1))
                                        .keepTrace(KeepTrace.MINIMAL)
                                        .nonPersistent()
                                        .from("init_to_" + applicationName)
                                        .to(applicationName + SetupTestMatsEndpoints.SERVICE)
                                        .replyTo(applicationName + SetupTestMatsEndpoints.TERMINATOR, sto)
                                        .request(dto);
                            }
                        });
            }
            out.println(".. Request sent.");
            out.flush();
        }

        private void futurizeSingle(PrintWriter out, String subService, MatsFuturizer matsFuturizer,
                String from, boolean interactive) {
            out.println((interactive ? "INTERACTIVE" : "normal") + " TO " + SERVICE_ORDER + subService);
            long nanosAtStart = System.nanoTime();
            CompletableFuture<Reply<DataTO>> future1 = matsFuturizer.futurize("TestTraceId" + Long.toHexString(Math.abs(
                    ThreadLocalRandom.current().nextLong())), from, SERVICE_ORDER + subService, 1, TimeUnit.MINUTES,
                    DataTO.class, new DataTO(Math.PI, from), init -> {
                        if (interactive) {
                            init.interactive();
                        }
                    });
            try {
                Reply<DataTO> reply = future1.get();
                out.println("-> got reply: " + reply.getReply());
            }
            catch (InterruptedException | ExecutionException e) {
                out.println("Failed! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            double msTaken = (System.nanoTime() - nanosAtStart) / 1_000_000d;
            out.println("Taken: [" + msTaken + " ms]\n");
            out.flush();
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

    public static Server createServer(ConnectionFactory jmsConnectionFactory, int port) {
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setBaseResource(Resource.newClassPathResource("webapp"));
        // If any problems starting context, then let exception through so that we can exit.
        webAppContext.setThrowUnavailableOnStartupException(true);
        // Store the port number this server shall run under in the ServletContext.
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);
        // Store the JMS ConnectionFactory in the ServletContext
        webAppContext.getServletContext().setAttribute(ConnectionFactory.class.getName(), jmsConnectionFactory);

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
        URL classesLocation = LocalHtmlInspect_TestJettyServer.class.getProtectionDomain().getCodeSource()
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
        server.addLifeCycleListener(new Listener() {
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
        MatsTestBroker matsTestBroker = MatsTestBroker.createUniqueInVmActiveMq();
        ConnectionFactory jmsConnectionFactory = matsTestBroker.getConnectionFactory();

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
