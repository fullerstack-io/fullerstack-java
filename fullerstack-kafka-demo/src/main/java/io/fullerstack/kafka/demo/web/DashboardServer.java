package io.fullerstack.kafka.demo.web;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;

import java.net.URL;

/**
 * Embedded Jetty server for dashboard.
 *
 * Serves:
 * - Static HTML/CSS/JS at http://localhost:8080
 * - WebSocket endpoint at ws://localhost:8080/ws
 */
public class DashboardServer {

    private static Server server;

    public static void startServer(int port) throws Exception {
        if (server != null && server.isRunning()) {
            System.out.println("Dashboard server already running");
            return;
        }

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Serve static files from resources/static
        URL webRootLocation = DashboardServer.class.getResource("/static/");
        if (webRootLocation == null) {
            throw new IllegalStateException("Unable to find /static/ in classpath");
        }
        context.setResourceBase(webRootLocation.toExternalForm());
        context.setWelcomeFiles(new String[]{"index.html"});

        // Add default servlet for static content
        context.addServlet(DefaultServlet.class, "/");

        // Configure WebSocket
        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, wsContainer) -> {
            wsContainer.setMaxTextMessageSize(65536);
            wsContainer.addMapping("/ws", DashboardWebSocket.class);
        });

        server.setHandler(context);
        server.start();

        System.out.println("========================================");
        System.out.println("  Dashboard available at:");
        System.out.println("  http://localhost:" + port);
        System.out.println("========================================");
    }

    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    public static boolean isRunning() {
        return server != null && server.isRunning();
    }
}
