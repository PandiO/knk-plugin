package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.domain.admission.AdmissionResult;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class AdmissionApiImplTest {
    private static final UUID UUID_1 = UUID.fromString("0f3c5a5e-1b7c-4d2a-9f1e-2a4b6c8d0e1f");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Request> sent = new AtomicReference<>();
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

    private AdmissionApiImpl api(int status, String body) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            sent.set(chain.request());
            return new Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        return new AdmissionApiImpl("http://api.test/api", client, new ObjectMapper().registerModule(new JavaTimeModule()),
            new ApiKeyAuthProvider("key"), executor, true);
    }

    @Test
    void sendsUuidAndNameWithThePluginKey() {
        api(200, "{\"admitted\":true,\"reason\":null,\"code\":null,\"expiresAt\":null}").check(UUID_1, "Steve").join();

        Request request = sent.get();
        assertEquals("GET", request.method());
        assertEquals("/api/Admission/" + UUID_1, request.url().encodedPath());
        assertEquals("Steve", request.url().queryParameter("name"));
        assertEquals("key", request.header("X-API-Key"));
    }

    @Test
    void readsAnAdmittedAnswer() {
        AdmissionResult result = api(200, "{\"admitted\":true,\"reason\":null,\"code\":null,\"expiresAt\":null}")
            .check(UUID_1, "Steve").join();

        assertTrue(result.admitted());
        assertNull(result.code());
    }

    @Test
    void readsAClaimCodeWithoutLoggingIt() {
        AdmissionResult result = api(200,
            "{\"admitted\":false,\"reason\":\"NotAdmitted\",\"code\":\"K7QH-M2XA\",\"expiresAt\":\"2026-10-21T12:15:00Z\"}")
            .check(UUID_1, "Steve").join();

        assertFalse(result.admitted());
        assertEquals(AdmissionResult.REASON_NOT_ADMITTED, result.reason());
        assertEquals("K7QH-M2XA", result.code());
        assertEquals(Instant.parse("2026-10-21T12:15:00Z"), result.expiresAt());
        String log = String.join("\n", logged);
        assertTrue(log.contains("/Admission/"), log);
        assertFalse(log.contains("K7QH"), log);
    }

    @Test
    void readsDeactivated() {
        AdmissionResult result = api(200, "{\"admitted\":false,\"reason\":\"Deactivated\",\"code\":null,\"expiresAt\":null}")
            .check(UUID_1, "Steve").join();

        assertFalse(result.admitted());
        assertEquals(AdmissionResult.REASON_DEACTIVATED, result.reason());
    }

    @Test
    void anErrorAnswerFailsTheFuture() {
        assertThrows(RuntimeException.class,
            () -> api(400, "{\"error\":\"InvalidRequest\",\"message\":\"bad name\"}").check(UUID_1, "bad name!").join());
        assertEquals("bad name!", sent.get().url().queryParameter("name"));
    }

    @Test
    void anAccountLinkedNotificationCarriesTheMaskedAddress() throws Exception {
        var dto = new ObjectMapper().readValue("{\"id\":5,\"userId\":7,\"uuid\":\"" + UUID_1 + "\",\"username\":\"Steve\","
            + "\"type\":\"AccountLinked\",\"accountLinked\":{\"maskedEmail\":\"st***@example.com\"}}",
            net.knightsandkings.knk.api.dto.PlayerNotificationDto.class);

        var notification = net.knightsandkings.knk.api.mapper.UsersMapper.mapPlayerNotification(dto);

        assertEquals(net.knightsandkings.knk.core.domain.users.PlayerNotification.TYPE_ACCOUNT_LINKED, notification.type());
        assertEquals("st***@example.com", notification.accountLinked().maskedEmail());
    }

    @Test
    void admissionCallsCountAsCarryingACode() {
        assertTrue(BaseApiImpl.carriesLinkCode("http://x/api/Admission/" + UUID_1 + "?name=Steve"));
    }
}
