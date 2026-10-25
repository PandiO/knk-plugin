package net.knightsandkings.knk.paper.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.knightsandkings.knk.core.metrics.MetricsHttpPolicy;
import net.knightsandkings.knk.core.metrics.MetricsRegistry;
import net.knightsandkings.knk.core.metrics.PrometheusWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Serves {@code GET /metrics} (Prometheus text) for {@code metrics.enabled: true} (KNG-232). A separate server
 * from {@code RegionHttpServer}: that one is called by knk-web-api with the plugin API key and runs work on the
 * main thread; this one is read by a scraper with its own bearer token and never touches Bukkit (collectors
 * read snapshots and JMX only).
 */
public final class MetricsHttpServer {
    private static final Logger LOGGER = Logger.getLogger(MetricsHttpServer.class.getName());
    private static final String PATH = "/metrics";

    private final MetricsRegistry registry;
    private final String bindAddress;
    private final int port;
    private final String token;
    private HttpServer server;
    private ExecutorService executor;

    public MetricsHttpServer(MetricsRegistry registry, String bindAddress, int port, String token) {
        this.registry = registry;
        this.bindAddress = bindAddress;
        this.port = port;
        this.token = token;
    }

    public void start() throws IOException {
        String problem = MetricsHttpPolicy.problem(bindAddress, token);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        // Two daemon threads: a scrape is a few kilobytes of string building.
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "knk-metrics-http");
            thread.setDaemon(true);
            return thread;
        });
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        InetSocketAddress bound = server.getAddress();
        LOGGER.info("Metrics endpoint listening on " + bound.getAddress().getHostAddress() + ":" + bound.getPort() + PATH
            + (token == null || token.isBlank() ? " (loopback only, no token)" : " (bearer token required)"));
    }

    public InetSocketAddress getAddress() {
        return server != null ? server.getAddress() : null;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!MetricsHttpPolicy.isAuthorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, "text/plain; charset=utf-8", "Unauthorized");
            } else if (!PATH.equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            } else if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            } else {
                send(exchange, 200, PrometheusWriter.CONTENT_TYPE, registry.scrape());
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
