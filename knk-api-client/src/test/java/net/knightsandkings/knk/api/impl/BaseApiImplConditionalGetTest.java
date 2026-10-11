package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Plan R17: a conditional GET treats 304 as "not modified", not as an error; every other non-2xx still throws. */
class BaseApiImplConditionalGetTest {

    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";
    private String responseEtag = "\"3\"";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Response.Builder builder = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
            .message("x");
        if (responseEtag != null) {
            builder.header("ETag", responseEtag);
        }
        if (status == 304) {
            builder.body(ResponseBody.create("", MediaType.get("application/json")));
        } else {
            builder.body(ResponseBody.create(responseJson, MediaType.get("application/json")));
        }
        return builder.build();
    }).build();

    /** Exposes the protected method. */
    private static final class Probe extends BaseApiImpl {
        Probe(OkHttpClient client, ExecutorService executor) {
            super("http://api.test/api", client, new ObjectMapper(), new NoAuthProvider(), executor, false);
        }

        ConditionalResponse call(String etag) throws Exception {
            return getConditional(baseUrl + "/road-tiles/world/0/0/graph", etag);
        }
    }

    private final Probe api = new Probe(client, executor);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void freshDownloadReturnsBodyAndEtag() throws Exception {
        responseJson = "{\"tile\":{\"version\":3}}";

        BaseApiImpl.ConditionalResponse result = api.call(null);

        assertFalse(result.notModified());
        assertEquals("{\"tile\":{\"version\":3}}", result.body());
        assertEquals("\"3\"", result.etag());
        assertEquals("GET", seen.get(0).method());
        assertNull(seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER), "no etag, no If-None-Match");
    }

    @Test
    void etagIsSentBackVerbatimAnd304IsNotAnError() throws Exception {
        status = 304;

        BaseApiImpl.ConditionalResponse result = api.call("\"3\"");

        assertTrue(result.notModified());
        assertNull(result.body());
        assertEquals("\"3\"", result.etag());
        assertEquals("\"3\"", seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER));
    }

    @Test
    void weakEtagGoesThroughUntouchedAndAMissingResponseEtagFallsBackToTheSentOne() throws Exception {
        status = 304;
        responseEtag = null;

        BaseApiImpl.ConditionalResponse result = api.call("W/\"3\"");

        assertTrue(result.notModified());
        assertEquals("W/\"3\"", seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER));
        assertEquals("W/\"3\"", result.etag());
    }

    @Test
    void changedTileAnswers200WithTheNewEtag() throws Exception {
        responseJson = "{\"tile\":{\"version\":4}}";
        responseEtag = "\"4\"";

        BaseApiImpl.ConditionalResponse result = api.call("\"3\"");

        assertFalse(result.notModified());
        assertEquals("\"4\"", result.etag());
        assertEquals("{\"tile\":{\"version\":4}}", result.body());
    }

    @Test
    void notFoundStillThrowsApiExceptionWithTheBody() {
        status = 404;
        responseEtag = null;
        responseJson = "{\"error\":\"NotFound\",\"message\":\"Tile (0, 0) of world 'world' has not been built.\"}";

        ApiException error = assertThrows(ApiException.class, () -> api.call("\"3\""));

        assertEquals(404, error.getStatusCode());
        assertTrue(error.getResponseBody().contains("NotFound"));
    }

    @Test
    void blankEtagIsTreatedAsNone() throws Exception {
        api.call("  ");
        assertNull(seen.get(0).header(BaseApiImpl.IF_NONE_MATCH_HEADER));
    }
}
