package net.knightsandkings.knk.paper.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import net.knightsandkings.knk.core.metrics.MetricsRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** KNG-232: the /metrics server answers Prometheus text, only with the token, only for GET /metrics. */
class MetricsHttpServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final MetricsRegistry registry = new MetricsRegistry();
    private MetricsHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private int start(String token) throws IOException {
        registry.register(out -> out.gauge("knk_test_value", "Test.", 7));
        server = new MetricsHttpServer(registry, "127.0.0.1", 0, token);
        server.start();
        return server.getAddress().getPort();
    }

    private HttpResponse<String> send(int port, String method, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesThePrometheusTextWithTheToken() throws Exception {
        int port = start("secret");

        HttpResponse<String> response = send(port, "GET", "/metrics", "Bearer secret");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
        assertTrue(response.body().contains("knk_test_value 7"), response.body());
    }

    @Test
    void refusesAMissingOrWrongToken() throws Exception {
        int port = start("secret");

        assertEquals(401, send(port, "GET", "/metrics", null).statusCode());
        assertEquals(401, send(port, "GET", "/metrics", "Bearer nope").statusCode());
        assertEquals(401, send(port, "GET", "/anything", null).statusCode(), "the key check runs before routing");
    }

    @Test
    void otherPathsAndMethodsAreRefused() throws Exception {
        int port = start("secret");

        assertEquals(404, send(port, "GET", "/", "Bearer secret").statusCode());
        assertEquals(405, send(port, "POST", "/metrics", "Bearer secret").statusCode());
    }

    @Test
    void onLoopbackNoTokenIsNeeded() throws Exception {
        int port = start("");

        assertEquals(200, send(port, "GET", "/metrics", null).statusCode());
    }

    @Test
    void anOpenAddressWithoutATokenDoesNotStart() {
        MetricsHttpServer open = new MetricsHttpServer(registry, "0.0.0.0", 0, "");

        assertThrows(IllegalStateException.class, open::start);
    }
}
