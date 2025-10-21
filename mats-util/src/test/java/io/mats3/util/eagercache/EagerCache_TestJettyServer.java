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

package io.mats3.util.eagercache;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.time.Clock;
import java.util.Collections;
import java.util.stream.Collectors;

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
import io.mats3.util.DummyFinancialService;
import io.mats3.util.eagercache.MatsEagerCacheClient.MatsEagerCacheClientMock;
import io.mats3.util.eagercache.MatsEagerCacheHtmlGui.AccessControl;
import io.mats3.util.eagercache.MatsEagerCacheServer.MatsEagerCacheServerImpl;

/**
 * Test Jetty Server for the EagerCache, which "boots" 2x Cache Server and Clients, and then creates HTML GUIs and
 * Storebrand HealthChecks for them - to see how this works out in an actual HTML context.
 */
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
                _serversClients = CommonSetup_TwoServers_TwoClients.createWithServerAdjust(10, (server) -> {
                    // 2 minutes. (Should be *hours* in production!)
                    server.setPeriodicFullUpdateIntervalMinutes(2);
                    // Setting a bit higher than the testing requires.
                    ((MatsEagerCacheServerImpl) server)._setCoalescingDelays(1000, 3000);
                });
            }
            catch (JsonProcessingException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            // Create a Mock MatsEagerCacheClient
            MatsEagerCacheClientMock<DataCarrier> mockClient = MatsEagerCacheClient.mock("Customers");
            mockClient.setMockData(new DataCarrier(DummyFinancialService.createRandomReplyDTO(1234L, 20).customers));
            mockClient.start();

            // Put the Cache Servers and Clients in ServletContext, for servlet to get
            sc.setAttribute("clientMatsFactory1", _serversClients.clientMatsFactory1);
            sc.setAttribute("clientMatsFactory2", _serversClients.clientMatsFactory2);
            sc.setAttribute("serverMatsFactory1", _serversClients.serverMatsFactory1);
            sc.setAttribute("serverMatsFactory2", _serversClients.serverMatsFactory2);

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
            LocalHtmlInspectForMatsFactory serverLocal1 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.serverMatsFactory1);
            LocalHtmlInspectForMatsFactory serverLocal2 = LocalHtmlInspectForMatsFactory.create(
                    _serversClients.serverMatsFactory2);

            // Put the LocalInspect HTML GUIs into ServletContext
            sc.setAttribute("clientLocal1", clientLocal1);
            sc.setAttribute("clientLocal2", clientLocal2);
            sc.setAttribute("serverLocal1", serverLocal1);
            sc.setAttribute("serverLocal2", serverLocal2);

            // ::: Create Storebrand HealthCheckRegistry and MatsEeagerCacheStorebrandHealthCheck
            HealthCheckLogger healthCheckLogger = dto -> log.info("HealthCheck: " + dto);
            HealthCheckRegistryImpl healthCheckRegistry = new HealthCheckRegistryImpl(Clock.systemDefaultZone(),
                    healthCheckLogger, new ServiceInfo("MatsEagerCache", "#testing#"));
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry, _serversClients.cacheClient1);
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry, _serversClients.cacheClient2);
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry, mockClient);
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry, _serversClients.cacheServer1);
            MatsEagerCacheStorebrandHealthCheck.registerHealthCheck(healthCheckRegistry, _serversClients.cacheServer2);
            // Start the HealthCheck subsystem
            healthCheckRegistry.startHealthChecks();

            // .. put the HealtCheckRegistry in the ServletContext, for the HealthCheckServlet to get.
            sc.setAttribute(HealthCheckRegistry.class.getName(), healthCheckRegistry);

            // :: Create the MatsEagerCacheHtmlGui instances for Cache Clients and Servers
            MatsEagerCacheHtmlGui clientCacheGui1 = MatsEagerCacheHtmlGui.create(_serversClients.cacheClient1);
            MatsEagerCacheHtmlGui clientCacheGui2 = MatsEagerCacheHtmlGui.create(_serversClients.cacheClient2);
            MatsEagerCacheHtmlGui mockClientGui = MatsEagerCacheHtmlGui.create(mockClient);
            MatsEagerCacheHtmlGui serverCacheGui1 = MatsEagerCacheHtmlGui.create(_serversClients.cacheServer1);
            MatsEagerCacheHtmlGui serverCacheGui2 = MatsEagerCacheHtmlGui.create(_serversClients.cacheServer2);

            // Put the Cache Clients and Servers HTML GUIs into ServletContext
            sc.setAttribute("clientCacheGui1", clientCacheGui1);
            sc.setAttribute("clientCacheGui2", clientCacheGui2);
            sc.setAttribute("mockClientGui", mockClientGui);
            sc.setAttribute("serverCacheGui1", serverCacheGui1);
            sc.setAttribute("serverCacheGui2", serverCacheGui2);
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

            // ::: Perform routing to the correct MatsBrokerMonitorHtmlGUI instance

            // :: Get the routingId from the request parameter
            String routingId = req.getParameter("routingId");
            if (routingId == null) {
                out.println("{\"error\": \"No 'routingId' parameter provided.\"}");
                return;
            }

            // :: Find which MatsEagerCacheGui to use, and output its JSON
            boolean found = false;
            MatsEagerCacheHtmlGui[] allGuis = getAllGuis(req);
            for (MatsEagerCacheHtmlGui gui : allGuis) {
                if (gui.getRoutingId().equals(routingId)) {
                    // Use "standard" AccessControl - which is a Dummy, allowing all.
                    AccessControl ac = getAccessControl();
                    // Output the MatsEagerCacheGui's JSON
                    gui.json(out, req.getParameterMap(), body, ac);
                    found = true;
                }
            }
            if (!found) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND, "No MatsEagerCacheGui found for supplied routingId.");
                log.error("No MatsEagerCacheGui found for supplied routingId [" + routingId + "].");
            }
        }

        protected AccessControl getAccessControl() {
            return MatsEagerCacheHtmlGui.getAccessControlAllowAll(System.getProperty("user.name"));
        }

        protected MatsEagerCacheHtmlGui[] getAllGuis(HttpServletRequest req) {
            ServletContext sc = req.getServletContext();
            MatsEagerCacheHtmlGui mockClientGui = (MatsEagerCacheHtmlGui) sc.getAttribute("mockClientGui");
            MatsEagerCacheHtmlGui clientCacheGui1 = (MatsEagerCacheHtmlGui) sc.getAttribute("clientCacheGui1");
            MatsEagerCacheHtmlGui clientCacheGui2 = (MatsEagerCacheHtmlGui) sc.getAttribute("clientCacheGui2");
            MatsEagerCacheHtmlGui serverCacheGui1 = (MatsEagerCacheHtmlGui) sc.getAttribute("serverCacheGui1");
            MatsEagerCacheHtmlGui serverCacheGui2 = (MatsEagerCacheHtmlGui) sc.getAttribute("serverCacheGui2");
            return new MatsEagerCacheHtmlGui[] { mockClientGui, clientCacheGui1, clientCacheGui2, serverCacheGui1,
                    serverCacheGui2 };
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
            MatsEagerCacheHtmlGui[] allGuis = getAllGuis(req);

            PrintWriter out = resp.getWriter();
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("  <body>");
            out.println("    <style>");
            out.println("\n    /* Include the CSS from the LocalInspect HTML GUIs */\n\n");
            clientLocal1.getStyleSheet(out); // Include just once, use the first.
            out.println("\n    /* Include the CSS from the MatsEagerCache HTML GUIs */\n\n");
            MatsEagerCacheHtmlGui.styleSheet(out);
            out.println("    </style>");
            out.println("    <script>");
            out.println("\n    /* Include the JavaScript from the LocalInspect HTML GUIs */\n\n");
            clientLocal1.getJavaScript(out); // Include just once, use the first.
            out.println("\n    /* Include the JavaScript from the MatsEagerCache HTML GUIs */\n\n");
            MatsEagerCacheHtmlGui.javaScript(out);
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

            // Use "standard" AccessControl - which is a Dummy, allowing all.
            AccessControl ac = getAccessControl();

            // Output the MatsEagerCacheGui's HTML
            for (MatsEagerCacheHtmlGui gui : allGuis) {
                gui.html(out, req.getParameterMap(), ac);
            }

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
        ResourceFactory resourceFactory = ResourceFactory.of(webAppContext);
        webAppContext.setBaseResource(resourceFactory.newClassLoaderResource("webapp"));
        // If any problems starting context, then let exception through so that we can exit.
        webAppContext.setThrowUnavailableOnStartupException(true);
        // Store the port number this server shall run under in the ServletContext.
        webAppContext.getServletContext().setAttribute(CONTEXT_ATTRIBUTE_PORTNUMBER, port);

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
        URL classesLocation = EagerCache_TestJettyServer.class.getProtectionDomain().getCodeSource()
                .getLocation();
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
