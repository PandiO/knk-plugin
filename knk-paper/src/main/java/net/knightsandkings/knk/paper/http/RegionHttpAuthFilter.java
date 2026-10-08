package net.knightsandkings.knk.paper.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-key check for {@link RegionHttpServer}: when the plugin has an API key ({@code api.auth.api-key}),
 * every request must carry it in {@code X-API-Key}; otherwise it is answered 401 before any handler runs.
 * knk-web-api sends its {@code Security:PluginApiKey}, which is the same value. Without a configured key the
 * filter lets everything through (the server then relies on its bind address alone).
 */
public final class RegionHttpAuthFilter extends Filter {
    public static final String HEADER = "X-API-Key";
    static final String UNAUTHORIZED_BODY =
        "{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid X-API-Key header.\"}";

    private final byte[] expectedKey;

    public RegionHttpAuthFilter(String apiKey) {
        this.expectedKey = apiKey == null || apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    /** True when a key is configured, so requests without it are refused. */
    public boolean isKeyRequired() {
        return expectedKey != null;
    }

    /** Whether a request carrying {@code providedKey} (null when the header is absent) may proceed. */
    public boolean isAuthorized(String providedKey) {
        if (expectedKey == null) {
            return true;
        }
        if (providedKey == null) {
            return false;
        }
        // Constant-time compare: the time taken doesn't reveal how much of the key matched.
        return MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (isAuthorized(exchange.getRequestHeaders().getFirst(HEADER))) {
            chain.doFilter(exchange);
            return;
        }
        byte[] body = UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(401, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @Override
    public String description() {
        return "Requires the plugin API key in the " + HEADER + " header when one is configured";
    }
}
