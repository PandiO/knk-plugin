package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.HealthStatusDto;
import net.knightsandkings.knk.api.mapper.HealthStatusMapper;
import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HealthApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * HTTP client implementation of HealthApi using OkHttp.
 * <p>
 * Probes knk-web-api's readiness endpoint {@code GET /health/ready} (KNG-115). That route sits at
 * the API root, not under {@code /api}, so the URL is built from a separate health root (by
 * default {@code base-url} without its trailing {@code /api}). The HTTP status is the primary
 * signal: 2xx reads the body's {@code status} ({@code healthy}/{@code degraded}), 503 is
 * "unhealthy" (API up, a dependency such as MySQL down), anything else is an {@link ApiException}.
 * The probe uses its own short call timeout so an unreachable API fails fast.
 */
public class HealthApiImpl implements HealthApi {
    private static final Logger LOGGER = Logger.getLogger(HealthApiImpl.class.getName());

    public static final String READINESS_ENDPOINT = "/health/ready";
    public static final Duration DEFAULT_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RESPONSE_SNIPPET_LENGTH = 200;
    private static final int SERVICE_UNAVAILABLE = 503;

    private final String healthRootUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthProvider authProvider;
    private final ExecutorService executor;
    private final boolean debugLogging;

    public HealthApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        this(deriveHealthRootUrl(baseUrl), DEFAULT_PROBE_TIMEOUT, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /**
     * @param healthRootUrl API root the {@code /health/ready} path is appended to (no {@code /api})
     * @param probeTimeout  whole-call timeout for one probe; null keeps the shared client's timeouts
     */
    public HealthApiImpl(
        String healthRootUrl,
        Duration probeTimeout,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        this.healthRootUrl = stripTrailingSlashes(healthRootUrl);
        this.httpClient = probeTimeout == null ? httpClient : httpClient.newBuilder()
            .callTimeout(probeTimeout)
            .connectTimeout(probeTimeout)
            .readTimeout(probeTimeout)
            .build();
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.executor = executor;
        this.debugLogging = debugLogging;
    }

    /**
     * The API root for health routes: {@code baseUrl} without trailing slashes and without a final
     * {@code /api} segment ({@code http://localhost:5294/api} gives {@code http://localhost:5294}).
     */
    public static String deriveHealthRootUrl(String baseUrl) {
        String root = stripTrailingSlashes(baseUrl);
        if (root.toLowerCase(java.util.Locale.ROOT).endsWith("/api")) {
            root = root.substring(0, root.length() - "/api".length());
        }
        return root;
    }

    /** Full URL this implementation probes. */
    public String readinessUrl() {
        return healthRootUrl + READINESS_ENDPOINT;
    }

    private static String stripTrailingSlashes(String url) {
        if (url == null) {
            throw new IllegalArgumentException("health root URL is required");
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public CompletableFuture<HealthStatus> getHealth() {
        return CompletableFuture.supplyAsync(() -> {
            String url = readinessUrl();
            long startTime = System.currentTimeMillis();

            Request.Builder requestBuilder = new Request.Builder().url(url);

            // Add auth header if provider is configured
            // Note: We don't log the actual auth header value to avoid leaking secrets
            if (authProvider != null && authProvider.getAuthHeader() != null) {
                requestBuilder.addHeader(
                    authProvider.getAuthHeaderName(),
                    authProvider.getAuthHeader()
                );
            }

            Request request = requestBuilder.build();

            if (debugLogging) {
                LOGGER.info("API Request: GET " + url);
            }

            try (Response response = httpClient.newCall(request).execute()) {
                long latency = System.currentTimeMillis() - startTime;

                if (debugLogging) {
                    LOGGER.info(String.format("API Response: GET %s [%d] in %dms",
                        url, response.code(), latency));
                }

                String body = response.body() != null ? response.body().string() : "";

                if (response.code() == SERVICE_UNAVAILABLE) {
                    // The API answered but reports itself not ready (e.g. MySQL unreachable).
                    HealthStatusDto dto = parseQuietly(body);
                    return new HealthStatus("unhealthy", dto != null ? dto.version() : null);
                }

                if (!response.isSuccessful()) {
                    String snippet = body.substring(0, Math.min(body.length(), MAX_RESPONSE_SNIPPET_LENGTH));
                    if (body.length() > MAX_RESPONSE_SNIPPET_LENGTH) {
                        snippet += "...";
                    }

                    throw new ApiException(
                        url,
                        response.code(),
                        "Health check failed",
                        snippet
                    );
                }

                // 2xx is ready; an empty or status-less body means plain "healthy".
                HealthStatusDto dto = parseQuietly(body);
                if (dto == null || dto.status() == null || dto.status().isBlank()) {
                    return new HealthStatus("healthy", dto != null ? dto.version() : null);
                }
                return HealthStatusMapper.toDomain(dto);

            } catch (ConnectException | SocketTimeoutException e) {
                // For connection/timeout errors, wrap with URL context
                throw new ApiException(url, "Failed to connect to API: " + e.getClass().getSimpleName(), e);
            } catch (IOException e) {
                // Includes OkHttp's InterruptedIOException("timeout") from the call timeout.
                throw new ApiException(url, "Failed to execute health check request", e);
            }
        }, executor);
    }

    private HealthStatusDto parseQuietly(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(body, HealthStatusDto.class);
        } catch (IOException e) {
            return null;
        }
    }
}
