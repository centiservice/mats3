package io.mats3.util.eagercache;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.time.Clock;
import java.util.Collections;
import java.util.stream.Collectors;

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
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.storebrand.healthcheck.HealthCheckLogger;
import com.storebrand.healthcheck.HealthCheckRegistry;
import com.storebrand.healthcheck.HealthCheckRegistry.CreateReportRequest;
import com.storebrand.healthcheck.HealthCheckReportDto;
import com.storebrand.healthcheck.impl.HealthCheckRegistryImpl;
import com.storebrand.healthcheck.impl.ServiceInfo;
import com.storebrand.healthcheck.output.HealthCheckTextOutput;

import io.mats3.localinspect.LocalHtmlInspectForMatsFactory;
import io.mats3.localinspect.LocalStatsMatsInterceptor;

public class EagerCache_TestJettyServer {

    private static final String CONTEXT_ATTRIBUTE_PORTNUMBER = "ServerPortNumber";

    private static final Logger log = LoggerFactory.getLogger(EagerCache_TestJettyServer.class);

    @WebListener
    public static class SCL_Endre implements ServletContextListener {

        private CommonSetup_TwoServers_TwoClients _serversClients;

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            log.info("ServletContextListener.contextInitialized(...): " + sce);
            ServletContext sc = sce.getServletContext();
            log.info("  \\- ServletContext: " + sc);

            // Create the MatsFactories
            try {
                _serversClients = new CommonSetup_TwoServers_TwoClients(10, (server) -> {
                    server.setPeriodicFullUpdateIntervalMinutes(0.2d); // 12 seconds. (Should be *hours* in production!)
                });
            }
            catch (JsonProcessingException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            // Get JMS ConnectionFactory from ServletContext
            ConnectionFactory connFactory = (ConnectionFactory) sc.getAttribute(ConnectionFactory.class.getName());

            // Put it in ServletContext, for servlet to get
            sce.getServletContext().setAttribute("clientMatsFactory1", _serversClients.clientMatsFactory1);
            sce.getServletContext().setAttribute("clientMatsFactory2", _serversClients.clientMatsFactory2);
            sce.getServletContext().setAttribute("serverMatsFactory1", _serversClients.serverMatsFactory1);
            sce.getServletContext().setAttribute("serverMatsFactory2", _serversClients.serverMatsFactory2);

            // Install the stats keeper interceptor
            LocalStatsMatsInterceptor.install(_serversClients.clientMatsFactory1);
            LocalStatsMatsInterceptor.install(_serversClients.clientMatsFactory2);
            LocalStatsMatsInterceptor.install(_serversClients.serverMatsFactory1);
            LocalStatsMatsInterceptor.install(_serversClients.serverMatsFactory2);

            // :: Create the HTML LocalInspect UIs, and store them in the ServletContext attributes
            LocalHtmlInspectForMatsFactory clientLocal1 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.clientMatsFactory1);
            LocalHtmlInspectForMatsFactory clientLocal2 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.clientMatsFactory2);
            // servers
            LocalHtmlInspectForMatsFactory serverLocal1 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.serverMatsFactory1);
            LocalHtmlInspectForMatsFactory serverLocal2 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.serverMatsFactory2);

            // Put into ServletContext
            sc.setAttribute("clientLocal1", clientLocal1);
            sc.setAttribute("clientLocal2", clientLocal2);
            sc.setAttribute("serverLocal1", serverLocal1);
            sc.setAttribute("serverLocal2", serverLocal2);

            // ::: Create Storebrand HealthCheckRegistry and MatsEeagerCacheStorebrandHealthCheck
            HealthCheckLogger healthCheckLogger = dto -> log.info("HealthCheck: " + dto);
            HealthCheckRegistryImpl healthCheckRegistry = new HealthCheckRegistryImpl(Clock.systemDefaultZone(),
                    healthCheckLogger, new ServiceInfo("MatsEagerCache", "#testing#"));
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry,
                    _serversClients.cacheClient1.getCacheClientInformation());
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry,
                    _serversClients.cacheClient2.getCacheClientInformation());
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry,
                    _serversClients.cacheServer1.getCacheServerInformation());
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry,
                    _serversClients.cacheServer2.getCacheServerInformation());
            // Start the HealthCheck subsystem
            healthCheckRegistry.startHealthChecks();

            // :: Create the MatsEagerCacheGui instances
            MatsEagerCacheHtmlGui clientCacheGui1 = MatsEagerCacheHtmlGui.create(
                    _serversClients.cacheClient1.getCacheClientInformation());
            MatsEagerCacheHtmlGui clientCacheGui2 = MatsEagerCacheHtmlGui.create(
                    _serversClients.cacheClient2.getCacheClientInformation());
            MatsEagerCacheHtmlGui serverCacheGui1 = MatsEagerCacheHtmlGui.create(
                    _serversClients.cacheServer1.getCacheServerInformation());
            MatsEagerCacheHtmlGui serverCacheGui2 = MatsEagerCacheHtmlGui.create(
                    _serversClients.cacheServer2.getCacheServerInformation());

            // Put into ServletContext
            sc.setAttribute("clientCacheGui1", clientCacheGui1);
            sc.setAttribute("clientCacheGui2", clientCacheGui2);
            sc.setAttribute("serverCacheGui1", serverCacheGui1);
            sc.setAttribute("serverCacheGui2", serverCacheGui2);

            // .. put the HealtCheckRegistry in the ServletContext, for the HealthCheckServlet to get.
            sc.setAttribute(HealthCheckRegistry.class.getName(), healthCheckRegistry);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            log.info("ServletContextListener.contextDestroyed(..): " + sce);
            log.info("  \\- ServletContext: " + sce.getServletContext());
            _serversClients.close();
        }
    }

    /**
     * RenderingServlet
     */
    @WebServlet("/")
    public static class RootServlet extends HttpServlet {

        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
            doJson(req, res);
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
            doJson(req, res);
        }

        @Override
        protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
            doJson(req, res);
        }

        protected void doJson(HttpServletRequest req, HttpServletResponse res) throws IOException {
            // :: MatsBrokerMonitorHtmlGUI instance
            String body = req.getReader().lines().collect(Collectors.joining("\n"));
            res.setContentType("application/json; charset=utf-8");
            PrintWriter out = res.getWriter();

            // TODO: Do routing to correct interface!

            // Pick out MatsEagerCacheGui from ServletContext
            MatsEagerCacheHtmlGui clientCacheGui1 = (MatsEagerCacheHtmlGui) req.getServletContext().getAttribute(
                    "clientCacheGui1");

            clientCacheGui1.json(out, req.getParameterMap(), body);
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/html; charset=utf-8");
            // Pick out the LocalInspect GUIs from ServletContext
            LocalHtmlInspectForMatsFactory clientLocal1 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("clientLocal1");
            LocalHtmlInspectForMatsFactory clientLocal2 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("clientLocal2");
            LocalHtmlInspectForMatsFactory serverLocal1 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("serverLocal1");
            LocalHtmlInspectForMatsFactory serverLocal2 = (LocalHtmlInspectForMatsFactory) req.getServletContext()
                    .getAttribute("serverLocal2");

            // Pick out all the MatsEagerCacheGui instances from ServletContext
            MatsEagerCacheHtmlGui clientCacheGui1 = (MatsEagerCacheHtmlGui) req.getServletContext()
                    .getAttribute("clientCacheGui1");
            MatsEagerCacheHtmlGui clientCacheGui2 = (MatsEagerCacheHtmlGui) req.getServletContext()
                    .getAttribute("clientCacheGui2");
            MatsEagerCacheHtmlGui serverCacheGui1 = (MatsEagerCacheHtmlGui) req.getServletContext()
                    .getAttribute("serverCacheGui1");
            MatsEagerCacheHtmlGui serverCacheGui2 = (MatsEagerCacheHtmlGui) req.getServletContext()
                    .getAttribute("serverCacheGui2");

            PrintWriter out = resp.getWriter();
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("  <body>");
            out.println("    <style>");
            clientLocal1.getStyleSheet(out); // Include just once, use the first.
            clientCacheGui1.outputStyleSheet(out); // Include just once, use the first.
            out.println("    </style>");
            out.println("    <script>");
            clientLocal1.getJavaScript(out); // Include just once, use the first.
            clientCacheGui1.outputJavaScript(out); // Include just once, use the first.
            out.println("    </script>");
            out.println("<h1>Initiate Mats flow</h1>");
            out.println(" <a href=\"sendRequest\">Send request</a> - to initialize Initiator"
                    + " and get some traffic.<br /><br />");

            boolean sync = req.getParameter("sync") != null;
            HealthCheckRegistry healthCheckRegistry = (HealthCheckRegistry) req.getServletContext()
                    .getAttribute(HealthCheckRegistry.class.getName());
            HealthCheckReportDto report = healthCheckRegistry
                    .createReport(new CreateReportRequest().forceFreshData(sync));
            out.println("<h1>HealthChecks</h1>");
            out.println("<pre>");
            HealthCheckTextOutput.printHealthCheckReport(report, out);
            out.println("</pre>");

            // Output the MatsEagerCacheGui's HTML
            clientCacheGui1.html(out, req.getParameterMap());
            clientCacheGui2.html(out, req.getParameterMap());
            serverCacheGui1.html(out, req.getParameterMap());
            serverCacheGui2.html(out, req.getParameterMap());

            out.println("<h2>Summary Client MatsFactory 1</h2>");
            clientLocal1.createFactorySummary(out, true, true);
            out.println("<h2>Summary Client MatsFactory 2</h2>");
            clientLocal2.createFactorySummary(out, true, true);
            out.println("<h2>Summary Server MatsFactory 1</h2>");
            serverLocal1.createFactorySummary(out, true, true);
            out.println("<h2>Summary Server MatsFactory 2</h2>");
            serverLocal2.createFactorySummary(out, true, true);

            out.println("<h1>Introspection Client MatsFactory 1</h1>");
            clientLocal1.createFactoryReport(out, true, true, true);
            out.println("<h1>Introspection Client MatsFactory 2</h1>");
            clientLocal2.createFactoryReport(out, true, true, true);
            out.println("<h1>Introspection Server MatsFactory 1</h1>");
            serverLocal1.createFactoryReport(out, true, true, true);
            out.println("<h1>Introspection Server MatsFactory 2</h1>");
            serverLocal2.createFactoryReport(out, true, true, true);

            out.println("  </body>");
            out.println("</html>");
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
        URL classesLocation = EagerCache_TestJettyServer.class.getProtectionDomain().getCodeSource()
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

        // :: Graceful shutdown
        server.setStopTimeout(1000);
        server.setStopAtShutdown(true);
        return server;
    }

    public static void main(String... args) throws Exception {
        // Turn off LogBack's absurd SCI
        System.setProperty(ch.qos.logback.core.CoreConstants.DISABLE_SERVLET_CONTAINER_INITIALIZER_KEY, "true");

        Server server = createServer(8080);
        try {
            server.start();
        }
        catch (Exception e) {
            log.error("Problems starting Jetty", e);
            server.stop();
        }
    }
}
