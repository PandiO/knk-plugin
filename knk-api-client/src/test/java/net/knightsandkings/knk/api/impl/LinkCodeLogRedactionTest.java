package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Account link codes are credentials: they never reach latest.log, not even with api.debug-logging on. */
class LinkCodeLogRedactionTest {
    private static final String CODE = "QX7K2P";

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

    private UserAccountApiImpl api(int status, String body) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build())
                .build();
        return new UserAccountApiImpl("http://api.test/api", client, new ObjectMapper(),
                new ApiKeyAuthProvider("key"), executor, true);
    }

    private String allLogged() {
        return String.join("\n", logged);
    }

    @Test
    void aGeneratedCodeIsNotLoggedWithDebugLoggingOn() {
        api(200, "{\"code\":\"" + CODE + "\",\"formattedCode\":\"QX7-K2P\",\"expiresAt\":null}").generateLinkCode(7).join();

        String log = allLogged();
        assertTrue(log.contains("generate-link-code"), log);
        assertFalse(log.contains(CODE) || log.contains("QX7-K2P"), log);
    }

    @Test
    void aValidatedCodeIsNotLoggedOnSuccessOrError() {
        api(200, "{\"isValid\":true,\"username\":\"Steve\"}").validateLinkCode(CODE).join();
        assertThrows(RuntimeException.class,
            () -> api(429, "{\"error\":\"TooManyRequests\"}").validateLinkCode(CODE).join());

        String log = allLogged();
        assertTrue(log.contains("validate-link-code/<redacted>"), log);
        assertFalse(log.contains(CODE), log);
    }

    @Test
    void otherUrlsAreLoggedUnchanged() {
        assertEquals("http://api.test/api/Users/7", BaseApiImpl.loggableUrl("http://api.test/api/Users/7"));
        assertEquals("http://x/api/Users/validate-link-code/<redacted>?a=1",
            BaseApiImpl.loggableUrl("http://x/api/Users/validate-link-code/ABC123?a=1"));
        assertTrue(BaseApiImpl.carriesLinkCode("http://x/api/Users/generate-link-code"));
        assertFalse(BaseApiImpl.carriesLinkCode("http://x/api/WorldTasks/by-link-code/ABC"));
    }
}
