package net.knightsandkings.knk.core.metrics;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Who may scrape the plugin's {@code /metrics}: a bearer token, required unless the server binds to loopback only. */
public final class MetricsHttpPolicy {
    private MetricsHttpPolicy() {
    }

    /** Why the endpoint must not start with these settings, or {@code null} when they are fine. */
    public static String problem(String bindAddress, String token) {
        boolean hasToken = token != null && !token.isBlank();
        if (!hasToken && !isLoopback(bindAddress)) {
            return "metrics.token must be set when metrics.bind-address is not a loopback address (got: " + bindAddress + ")";
        }
        return null;
    }

    static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    /** Whether an {@code Authorization} header satisfies {@code token} (no token configured: anyone, loopback only). */
    public static boolean isAuthorized(String authorizationHeader, String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String prefix = "Bearer ";
        if (authorizationHeader == null || authorizationHeader.length() < prefix.length()
            || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        byte[] provided = authorizationHeader.substring(prefix.length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, token.getBytes(StandardCharsets.UTF_8));
    }
}
