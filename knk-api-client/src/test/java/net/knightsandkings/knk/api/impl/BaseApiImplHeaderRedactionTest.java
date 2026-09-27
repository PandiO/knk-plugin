package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.api.auth.BearerAuthProvider;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service key must never reach latest.log: BaseApiImpl logs request/response headers on every
 * API error and, with api.debug-logging on, on every response.
 */
class BaseApiImplHeaderRedactionTest {

    private static final String KEY = "super-secret-service-key";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Logger logger = Logger.getLogger(BaseApiImpl.class.getName());
    private final List<String> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void attach() {
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void detach() {
        logger.removeHandler(capture);
        executor.shutdownNow();
    }

    private KitsCommandApiImpl api(int status, boolean debugLogging) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .header("Set-Cookie", "session=abc")
                .body(ResponseBody.create("{\"kitId\":7,\"contents\":[]}", MediaType.get("application/json"))).build())
                .build();
        return new KitsCommandApiImpl("http://api.test/api", client, new ObjectMapper(),
                new ApiKeyAuthProvider(KEY), executor, debugLogging);
    }

    private String allLogged() {
        return String.join("\n", logged);
    }

    @Test
    void anErrorResponseLogsHeadersWithoutTheKey() {
        assertThrows(RuntimeException.class, () -> api(500, false).giveAsync(42, 9, 7).join());

        String log = allLogged();
        assertTrue(log.contains("X-API-Key: <redacted>"), log);
        assertTrue(log.contains("X-Acting-User-Id: 42"), log);
        assertTrue(log.contains("Set-Cookie: <redacted>"), log);
        assertFalse(log.contains(KEY), log);
    }

    @Test
    void debugLoggingOfASuccessLogsHeadersWithoutTheKey() {
        api(200, true).giveAsync(42, 9, 7).join();

        String log = allLogged();
        assertTrue(log.contains("X-API-Key: <redacted>"), log);
        assertFalse(log.contains(KEY), log);
    }

    @Test
    void redactCoversAuthorizationAndACustomAuthHeaderName() {
        Headers headers = new Headers.Builder()
                .add("Authorization", new BearerAuthProvider("jwt-token").getAuthHeader())
                .add("X-Custom-Key", "custom-secret")
                .add("Accept", "application/json")
                .build();

        String out = BaseApiImpl.redact(headers, "X-Custom-Key");

        assertEquals("Authorization: <redacted>\nX-Custom-Key: <redacted>\nAccept: application/json\n", out);
    }

    @Test
    void redactOfNoHeaders() {
        assertEquals("<none>", BaseApiImpl.redact(null, null));
        assertEquals("<none>", BaseApiImpl.redact(new Headers.Builder().build(), null));
    }
}
