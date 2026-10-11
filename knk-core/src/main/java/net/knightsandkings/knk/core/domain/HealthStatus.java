package net.knightsandkings.knk.core.domain;

/**
 * Health status response from the API backend.
 * <p>
 * knk-web-api's {@code GET /health/ready} answers {@code healthy}, {@code degraded} or
 * {@code unhealthy} (KNG-115); {@code UP}/{@code OK} are still accepted for older or
 * third-party endpoints. Matching is case-insensitive.
 */
public record HealthStatus(
    String status,
    String version
) {
    public HealthStatus {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status cannot be null or blank");
        }
    }

    /**
     * True when the API can serve requests. {@code degraded} counts as up: the API answers, a
     * non-critical dependency is impaired (KNG-115 decision for review).
     */
    public boolean isHealthy() {
        String s = status.trim();
        return "healthy".equalsIgnoreCase(s)
            || "degraded".equalsIgnoreCase(s)
            || "UP".equalsIgnoreCase(s)
            || "OK".equalsIgnoreCase(s);
    }

    /** True when the API reported {@code degraded}: up, but with an impaired dependency. */
    public boolean isDegraded() {
        return "degraded".equalsIgnoreCase(status.trim());
    }
}
