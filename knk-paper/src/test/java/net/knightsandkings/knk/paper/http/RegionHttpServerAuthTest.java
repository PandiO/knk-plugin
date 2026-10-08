package net.knightsandkings.knk.paper.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/** WP10: the region callback server only answers callers that send the plugin's API key. */
class RegionHttpServerAuthTest {

    @Nested
    class KeyCheck {
        @Test
        void withAKeyOnlyTheExactKeyIsAccepted() {
            RegionHttpAuthFilter filter = new RegionHttpAuthFilter("s3cret-key");

            assertTrue(filter.isKeyRequired());
            assertTrue(filter.isAuthorized("s3cret-key"));
            assertFalse(filter.isAuthorized(null));
            assertFalse(filter.isAuthorized(""));
            assertFalse(filter.isAuthorized("s3cret-ke"));
            assertFalse(filter.isAuthorized("s3cret-key "));
            assertFalse(filter.isAuthorized("S3CRET-KEY"));
        }

        @Test
        void withoutAKeyEverythingPasses() {
            for (String unset : new String[] {null, "", "   "}) {
                RegionHttpAuthFilter filter = new RegionHttpAuthFilter(unset);
                assertFalse(filter.isKeyRequired());
                assertTrue(filter.isAuthorized(null));
                assertTrue(filter.isAuthorized("anything"));
            }
        }

        @Test
        void aRequestWithoutTheKeyGets401AndNeverReachesTheHandler() throws Exception {
            RegionHttpAuthFilter filter = new RegionHttpAuthFilter("s3cret-key");
            HttpExchange exchange = mock(HttpExchange.class);
            Headers requestHeaders = new Headers();
            Headers responseHeaders = new Headers();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
            when(exchange.getResponseBody()).thenReturn(body);
            Filter.Chain chain = mock(Filter.Chain.class);

            filter.doFilter(exchange, chain);

            verify(exchange).sendResponseHeaders(401, RegionHttpAuthFilter.UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8).length);
            verify(chain, never()).doFilter(exchange);
            assertEquals(RegionHttpAuthFilter.UNAUTHORIZED_BODY, body.toString(StandardCharsets.UTF_8));
            assertTrue(responseHeaders.getFirst("Content-Type").startsWith("application/json"));
        }

        @Test
        void aRequestWithTheKeyIsPassedOn() throws Exception {
            RegionHttpAuthFilter filter = new RegionHttpAuthFilter("s3cret-key");
            HttpExchange exchange = mock(HttpExchange.class);
            Headers requestHeaders = new Headers();
            requestHeaders.add("x-api-key", "s3cret-key");
            when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
            Filter.Chain chain = mock(Filter.Chain.class);

            filter.doFilter(exchange, chain);

            verify(chain).doFilter(exchange);
            verify(exchange, never()).sendResponseHeaders(anyInt(), anyLong());
        }
    }

    /**
     * A real server on an ephemeral loopback port. The requests used are answered by the filter or by the
     * handlers' own validation, so they never reach the Bukkit scheduler or WorldGuard (hence no task handler).
     */
    @Nested
    class RunningServer {
        private RegionHttpServer server;
        private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

        @AfterEach
        void stop() {
            if (server != null) {
                server.stop();
            }
        }

        private InetSocketAddress start(String apiKey) throws Exception {
            server = new RegionHttpServer(mock(Plugin.class), null, "127.0.0.1", 0, apiKey);
            server.start();
            return server.getAddress();
        }

        private HttpResponse<String> get(InetSocketAddress address, String path, String apiKey) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + address.getPort() + path)).GET();
            if (apiKey != null) {
                request.header("X-API-Key", apiKey);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Test
        void bindsToTheConfiguredAddress() throws Exception {
            InetSocketAddress address = start("k");

            assertEquals("127.0.0.1", address.getAddress().getHostAddress());
        }

        @Test
        void refusesEveryPathWithoutTheKey() throws Exception {
            InetSocketAddress address = start("s3cret-key");

            HttpResponse<String> containment = get(address, "/api/regions/a/contains-region/b", null);
            HttpResponse<String> rename = get(address, "/Regions/rename", "wrong");
            HttpResponse<String> other = get(address, "/anything", null);

            assertEquals(401, containment.statusCode());
            assertTrue(containment.body().contains("\"Unauthorized\""));
            assertEquals(401, rename.statusCode());
            assertEquals(401, other.statusCode());
        }

        @Test
        void withTheKeyTheRequestReachesTheHandler() throws Exception {
            InetSocketAddress address = start("s3cret-key");

            // A malformed path is answered 400 by the handler itself, before any WorldGuard work.
            HttpResponse<String> response = get(address, "/api/regions/x", "s3cret-key");
            // Wrong method on rename: 405 from the handler.
            HttpResponse<String> rename = get(address, "/Regions/rename", "s3cret-key");

            assertEquals(400, response.statusCode());
            assertEquals(405, rename.statusCode());
        }

        @Test
        void withoutAConfiguredKeyRequestsAreNotChecked() throws Exception {
            InetSocketAddress address = start("");

            assertEquals(400, get(address, "/api/regions/x", null).statusCode());
        }
    }
}
