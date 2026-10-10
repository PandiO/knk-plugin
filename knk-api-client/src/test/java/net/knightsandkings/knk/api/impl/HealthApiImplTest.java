package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** KNG-115: the probe hits knk-web-api's /health/ready at the API root and reads its status values. */
class HealthApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private HealthApiImpl api(String baseUrl) {
        return new HealthApiImpl(baseUrl, client, mapper, new NoAuthProvider(), executor, false);
    }

    @Test
    void derivesTheHealthRootFromBaseUrl() {
        assertEquals("http://localhost:5294", HealthApiImpl.deriveHealthRootUrl("http://localhost:5294/api"));
        assertEquals("http://localhost:5294", HealthApiImpl.deriveHealthRootUrl("http://localhost:5294/api/"));
        assertEquals("http://localhost:5294", HealthApiImpl.deriveHealthRootUrl("http://localhost:5294/API"));
        assertEquals("https://knk.example/knk", HealthApiImpl.deriveHealthRootUrl("https://knk.example/knk/api"));
        assertEquals("http://localhost:5294", HealthApiImpl.deriveHealthRootUrl("http://localhost:5294"));
        assertEquals("http://host/rapid", HealthApiImpl.deriveHealthRootUrl("http://host/rapid"));
    }

    @Test
    void probesReadinessAtTheApiRoot() {
        responseJson = "{\"status\":\"healthy\",\"timestamp\":\"2026-10-10T12:00:00Z\",\"checks\":{\"self\":\"healthy\",\"database\":\"healthy\"},\"version\":null}";

        HealthStatus health = api("http://api.test/api").getHealth().join();

        assertEquals("http://api.test/health/ready", seen.get(0).url().toString());
        assertEquals("GET", seen.get(0).method());
        assertTrue(health.isHealthy());
        assertEquals("healthy", health.status());
    }

    @Test
    void explicitHealthRootIsUsedAsIs() {
        HealthApiImpl api = new HealthApiImpl("http://other.test:8080/", Duration.ofSeconds(2), client, mapper,
                new NoAuthProvider(), executor, false);
        api.getHealth().join();
        assertEquals("http://other.test:8080/health/ready", seen.get(0).url().toString());
    }

    @Test
    void degradedCountsAsUp() {
        responseJson = "{\"status\":\"degraded\"}";
        HealthStatus health = api("http://api.test/api").getHealth().join();
        assertTrue(health.isHealthy());
        assertTrue(health.isDegraded());
    }

    @Test
    void serviceUnavailableIsUnhealthyNotAnException() {
        status = 503;
        responseJson = "{\"status\":\"unhealthy\",\"checks\":{\"self\":\"healthy\",\"database\":\"unhealthy\"}}";
        HealthStatus health = api("http://api.test/api").getHealth().join();
        assertFalse(health.isHealthy());
        assertEquals("unhealthy", health.status());
    }

    @Test
    void serviceUnavailableWithoutBodyIsStillUnhealthy() {
        status = 503;
        responseJson = "";
        assertFalse(api("http://api.test/api").getHealth().join().isHealthy());
    }

    @Test
    void okWithEmptyOrForeignBodyIsHealthy() {
        responseJson = "";
        assertTrue(api("http://api.test/api").getHealth().join().isHealthy());
        responseJson = "not json";
        assertTrue(api("http://api.test/api").getHealth().join().isHealthy());
    }

    @Test
    void otherErrorStatusesThrowWithTheStatusCode() {
        status = 404;
        responseJson = "{\"title\":\"Not Found\"}";
        CompletionException ex = assertThrows(CompletionException.class, () -> api("http://api.test/api").getHealth().join());
        ApiException apiEx = assertInstanceOf(ApiException.class, ex.getCause());
        assertEquals(404, apiEx.getStatusCode());
        assertEquals("http://api.test/health/ready", apiEx.getRequestUrl());
    }
}
