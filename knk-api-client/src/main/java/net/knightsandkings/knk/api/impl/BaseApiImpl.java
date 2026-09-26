package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class BaseApiImpl {
    protected static Logger LOGGER = Logger.getLogger(BaseApiImpl.class.getName());
    protected static final int MAX_RESPONSE_SNIPPET_LENGTH = 1500;

    /**
     * Header making a ledger write safe to retry (currency ledger, KNG-21): a new random value
     * per player/staff action. OkHttp's retry of the same Request resends the same value, and the
     * API returns the stored result instead of posting again.
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    protected final String baseUrl;
    protected final OkHttpClient httpClient;
    protected final ObjectMapper objectMapper;
    protected final AuthProvider authProvider;
    protected final ExecutorService executor;
    protected final boolean debugLogging;

    protected BaseApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authProvider = authProvider;
        this.executor = executor;
        this.debugLogging = debugLogging;
    }

    protected Request.Builder newRequest(String url) {
        Request.Builder builder = new Request.Builder().url(url);
        if (authProvider != null && authProvider.getAuthHeader() != null) {
            builder.addHeader(authProvider.getAuthHeaderName(), authProvider.getAuthHeader());
        }
        return builder;
    }

    /**
     * Headers whose values never go to the log: the plugin's service key and any bearer token or
     * cookie. OkHttp's {@code Headers.toString()} redacts nothing custom, so logging
     * {@code request.headers()} used to write the X-API-Key into latest.log.
     */
    static final Set<String> SENSITIVE_HEADERS = Set.of(
        "x-api-key", "authorization", "proxy-authorization", "cookie", "set-cookie");

    static final String REDACTED = "<redacted>";

    /** {@code headers} one per line for the log, with sensitive values (and this client's own auth header) replaced. */
    protected String loggableHeaders(Headers headers) {
        String authHeader = authProvider != null ? authProvider.getAuthHeaderName() : null;
        return redact(headers, authHeader);
    }

    static String redact(Headers headers, String extraSensitiveHeader) {
        if (headers == null || headers.size() == 0) {
            return "<none>";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.name(i);
            boolean sensitive = SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                || (extraSensitiveHeader != null && name.equalsIgnoreCase(extraSensitiveHeader));
            out.append(name).append(": ").append(sensitive ? REDACTED : headers.value(i)).append('\n');
        }
        return out.toString();
    }

    protected String snippet(String body) {
        if (body == null) return "";
        int max = MAX_RESPONSE_SNIPPET_LENGTH;
        return body.length() > max ? body.substring(0, max) + "..." : body;
    }

    protected String execute(Request request, String url) throws ApiException, IOException {
        long startTime = System.currentTimeMillis();
        try (Response response = httpClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - startTime;
            String responseBody = response.body() != null ? response.body().string() : "";

            // Always log extensive details when the response is an error
            if (!response.isSuccessful()) {
                LOGGER.warning(String.format("API Error: %s %s -> [%d] %s in %dms",
                    request.method(), url, response.code(), response.message(), latency));
                LOGGER.warning("  Request headers:\n" + loggableHeaders(request.headers()));
                LOGGER.warning("  Response headers:\n" + loggableHeaders(response.headers()));
                LOGGER.warning("  Response body: " + snippet(responseBody));
            } else if (debugLogging) {
                LOGGER.info(String.format("API Response: %s %s [%d] in %dms",
                    request.method(), url, response.code(), latency));
                LOGGER.info("  Request headers:\n" + loggableHeaders(request.headers()));
                LOGGER.info("  Response headers:\n" + loggableHeaders(response.headers()));
                LOGGER.info("  Response body: " + snippet(responseBody));
            }

            if (!response.isSuccessful()) {
                throw new ApiException(url, response.code(), "Request failed", snippet(responseBody));
            }
            // 204 No Content is a success with no body - return empty string
            if (response.code() == 204) {
                return "";
            }
            if (responseBody.isEmpty()) {
                throw new ApiException(url, response.code(), "Empty response body", "");
            }
            return responseBody;
        }
    }

    protected String get(String url) throws ApiException, IOException {
        Request request = newRequest(url).get().build();
        if (debugLogging) LOGGER.info("API Request: GET " + url);
        return execute(request, url);
    }

    protected String postJson(String url, String json) throws ApiException, IOException {
        Request request = newRequest(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(RequestBody.create(json, MediaType.get("application/json")))
            .build();
        if (debugLogging) {
            LOGGER.info("API Request: POST " + url);
            LOGGER.info("  Body: " + snippet(json));
        }
        return execute(request, url);
    }

    protected String delete(String url) throws ApiException, IOException {
        Request request = newRequest(url).delete().build();
        if (debugLogging) LOGGER.info("API Request: DELETE " + url);
        return execute(request, url);
    }

    protected String putJson(String url, String json) throws ApiException, IOException {
        Request request = newRequest(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .put(RequestBody.create(json, MediaType.get("application/json")))
            .build();
        if (debugLogging) {
            LOGGER.info("API Request: PUT " + url);
            LOGGER.info("  Body: " + snippet(json));
        }
        return execute(request, url);
    }

    protected <T> T parse(String json, Class<T> type, String url) throws ApiException {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new ApiException(url, 200, "Failed to parse response: " + ex.getMessage(), snippet(json));
        }
    }

    protected <T> T parse(String json, TypeReference<T> typeRef, String url) throws ApiException {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception ex) {
            throw new ApiException(url, 200, "Failed to parse response: " + ex.getMessage(), snippet(json));
        }
    }
}
