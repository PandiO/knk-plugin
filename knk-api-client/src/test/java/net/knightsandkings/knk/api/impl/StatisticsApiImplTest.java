package net.knightsandkings.knk.api.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.domain.statistics.StatisticsBatchResult;
import net.knightsandkings.knk.core.domain.statistics.StatisticsCatalog;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilityConflictException;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-34: request/response JSON matches knk-web-api's Dtos/StatisticsDtos.cs. */
class StatisticsApiImplTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        } else {
            bodies.add(null);
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final StatisticsApiImpl api = new StatisticsApiImpl("http://api.test/api", client, mapper,
            new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static StatisticsBatch batch(UUID id) {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000005");
        return new StatisticsBatch(id, T0.plusSeconds(60),
                List.of(StatisticsBatch.SessionEntry.start(session, 7, T0),
                        StatisticsBatch.SessionEntry.end(session, 7, T0.plusSeconds(50), StatisticsBatch.EndReason.ServerStop)),
                List.of(new StatisticsBatch.DurationEntry(session, 7, "active_playtime", T0, T0.plusSeconds(50))),
                List.of(new StatisticsBatch.ValueEntry(7, "distance.foot", "", 12.123456, T0.plusSeconds(20))),
                List.of(new StatisticsBatch.ValueEntry(7, "highest_fall", "", 23.5, T0.plusSeconds(21))),
                List.of(new StatisticsBatch.PvpKillEntry(7, 8, "open_world", T0.plusSeconds(22))));
    }

    @Test
    void postBatchSendsTheApiShapeAndMapsTheResult() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        responseJson = "{\"batchId\":\"" + id + "\",\"duplicate\":false,\"accepted\":5,"
                + "\"rejected\":[{\"section\":\"pvpKills\",\"index\":0,\"code\":\"UnknownUser\"}]}";
        api.setSource("survival", "1.2.3");

        StatisticsBatchResult result = api.postBatch(batch(id)).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/statistics/batches", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(id.toString(), body.get("batchId").asText());
        assertEquals("survival", body.get("serverName").asText());
        assertEquals("1.2.3", body.get("pluginVersion").asText());
        assertEquals("2026-10-03T10:01:00Z", body.get("sentAt").asText());
        JsonNode start = body.get("sessions").get(0);
        assertEquals("start", start.get("type").asText());
        assertEquals("00000000-0000-0000-0000-000000000005", start.get("sessionKey").asText());
        assertEquals(7, start.get("userId").asInt());
        assertEquals("2026-10-03T10:00:00Z", start.get("at").asText());
        assertFalse(start.has("endReason"), "no endReason on a start");
        assertEquals("end", body.get("sessions").get(1).get("type").asText());
        assertEquals("ServerStop", body.get("sessions").get(1).get("endReason").asText());
        JsonNode duration = body.get("durations").get(0);
        assertEquals("active_playtime", duration.get("metric").asText());
        assertEquals("2026-10-03T10:00:50Z", duration.get("to").asText());
        JsonNode counter = body.get("counters").get(0);
        assertEquals("distance.foot", counter.get("metric").asText());
        assertEquals("", counter.get("context").asText());
        assertEquals(12.1235, counter.get("value").asDouble(), 1e-9);
        assertEquals("highest_fall", body.get("records").get(0).get("metric").asText());
        JsonNode kill = body.get("pvpKills").get(0);
        assertEquals(7, kill.get("killerUserId").asInt());
        assertEquals(8, kill.get("victimUserId").asInt());
        assertEquals("open_world", kill.get("context").asText());

        assertEquals(id, result.batchId());
        assertFalse(result.duplicate());
        assertEquals(5, result.accepted());
        assertEquals(new StatisticsBatchResult.Rejection("pvpKills", 0, "UnknownUser"), result.rejected().get(0));
    }

    @Test
    void aDuplicateAnswerIsMapped() {
        UUID id = UUID.randomUUID();
        responseJson = "{\"batchId\":\"" + id + "\",\"duplicate\":true,\"accepted\":0,\"rejected\":[]}";
        assertTrue(api.postBatch(batch(id)).join().duplicate());
    }

    @Test
    void aDisabledIngestionKeepsTheStatusInTheCauseChain() {
        status = 503;
        responseJson = "{\"error\":\"StatisticsDisabled\",\"message\":\"off\"}";

        CompletionException error = assertThrows(CompletionException.class, () -> api.postBatch(batch(UUID.randomUUID())).join());

        ApiException apiError = assertInstanceOf(ApiException.class, error.getCause().getCause());
        assertEquals(503, apiError.getStatusCode());
    }

    @Test
    void catalogIsMapped() {
        responseJson = "{\"timeZone\":\"Europe/Amsterdam\",\"contexts\":[\"open_world\",\"siege\"],\"metrics\":[],"
                + "\"settings\":[{\"settingKey\":\"pvp_kills\",\"group\":\"combat\",\"label\":\"Player kills\",\"contextual\":true}],"
                + "\"groups\":[{\"key\":\"activity\",\"label\":\"Activity\",\"settingKeys\":[\"logins\"]},"
                + "{\"key\":\"combat\",\"label\":\"Combat\",\"settingKeys\":[\"pvp_kills\",\"deaths\"]}]}";

        StatisticsCatalog catalog = api.getCatalog().join();

        assertEquals("http://api.test/api/statistics/catalog", seen.get(0).url().toString());
        assertEquals("Europe/Amsterdam", catalog.timeZone());
        assertEquals(List.of("open_world", "siege"), catalog.contexts());
        assertTrue(catalog.settings().get(0).contextual());
        assertEquals(List.of("pvp_kills", "deaths"), catalog.group("combat").orElseThrow().settingKeys());
    }

    private static final String VISIBILITY_JSON = "{\"userId\":7,\"friendsAvailable\":false,\"settings\":["
            + "{\"settingKey\":\"pvp_kills\",\"group\":\"combat\",\"label\":\"Player kills\",\"contextual\":true,"
            + "\"visibility\":\"Everyone\",\"contexts\":[{\"context\":\"siege\",\"visibility\":\"Nobody\",\"isOverride\":true},"
            + "{\"context\":\"open_world\",\"visibility\":\"Everyone\",\"isOverride\":false}]},"
            + "{\"settingKey\":\"logins\",\"group\":\"activity\",\"label\":\"Logins\",\"contextual\":false,\"visibility\":\"Friends\",\"contexts\":[]}]}";

    @Test
    void visibilityIsReadActingAsThePlayer() {
        responseJson = VISIBILITY_JSON;

        StatisticsVisibilitySettings settings = api.getVisibility(7, 7).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/statistics/users/7/visibility", seen.get(0).url().toString());
        assertEquals("7", seen.get(0).header("X-Acting-User-Id"));
        assertEquals(7, settings.userId());
        assertFalse(settings.friendsAvailable());
        StatisticsVisibilitySettings.Setting kills = settings.setting("pvp_kills").orElseThrow();
        assertEquals(StatisticVisibility.EVERYONE, kills.visibility());
        assertEquals(new StatisticsVisibilitySettings.ContextValue("siege", StatisticVisibility.NOBODY, true), kills.contexts().get(0));
        assertEquals(StatisticVisibility.FRIENDS, settings.setting("logins").orElseThrow().visibility());
    }

    @Test
    void visibilityUpdateSendsTheChangesAndMapsTheAnswer() throws Exception {
        responseJson = VISIBILITY_JSON;

        StatisticsVisibilitySettings settings = api.updateVisibility(7, 7, List.of(
                new StatisticsVisibilitySettings.Change("pvp_kills", "", StatisticVisibility.NOBODY, StatisticVisibility.EVERYONE),
                new StatisticsVisibilitySettings.Change("pvp_kills", "siege", StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY))).join();

        assertEquals("PUT", seen.get(0).method());
        assertEquals("7", seen.get(0).header("X-Acting-User-Id"));
        JsonNode body = mapper.readTree(bodies.get(0));
        JsonNode first = body.get("changes").get(0);
        assertEquals("pvp_kills", first.get("settingKey").asText());
        assertEquals("", first.get("context").asText());
        assertEquals("Nobody", first.get("expected").asText());
        assertEquals("Everyone", first.get("visibility").asText());
        assertEquals("siege", body.get("changes").get(1).get("context").asText());
        assertNotNull(settings.setting("logins").orElse(null));
    }

    @Test
    void aConflictCarriesTheCurrentSettings() {
        status = 409;
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longMessage.append("padding ");
        }
        responseJson = "{\"error\":\"VisibilityConflict\",\"message\":\"" + longMessage + "\",\"current\":" + VISIBILITY_JSON + "}";

        CompletionException error = assertThrows(CompletionException.class, () -> api.updateVisibility(7, 7, List.of(
                new StatisticsVisibilitySettings.Change("logins", "", StatisticVisibility.NOBODY, StatisticVisibility.EVERYONE))).join());

        StatisticsVisibilityConflictException conflict = assertInstanceOf(StatisticsVisibilityConflictException.class, error.getCause());
        assertEquals(StatisticVisibility.FRIENDS, conflict.current().setting("logins").orElseThrow().visibility());
    }

    @Test
    void aForbiddenUpdateKeepsTheStatus() {
        status = 403;
        responseJson = "{\"error\":\"Forbidden\",\"message\":\"no\"}";

        CompletionException error = assertThrows(CompletionException.class, () -> api.updateVisibility(7, 8, List.of()).join());

        assertEquals(403, assertInstanceOf(ApiException.class, error.getCause().getCause()).getStatusCode());
    }

    // ---- link 5: reads ----

    private static final String STATISTICS_JSON = "{\"userId\":7,\"username\":\"Bob\",\"period\":\"week\","
            + "\"periodStart\":\"2026-09-28\",\"periodEndExclusive\":\"2026-10-05\",\"timeZone\":\"Europe/Amsterdam\","
            + "\"viewer\":\"signedIn\",\"profile\":{\"titleName\":\"Squire\",\"titleBracketId\":2,\"experience\":150,"
            + "\"coins\":3,\"gems\":1,\"firstJoinedAt\":\"2026-09-01T10:00:00Z\",\"activePlaytimeSeconds\":3600,\"afkSeconds\":60},"
            + "\"metrics\":[{\"key\":\"pvp_kills\",\"settingKey\":\"pvp_kills\",\"value\":null,\"rawValue\":null,"
            + "\"unit\":\"Count\",\"aggregation\":\"Sum\",\"contexts\":[{\"context\":\"open_world\",\"value\":5,\"rawValue\":5}]},"
            + "{\"key\":\"highest_fall\",\"settingKey\":\"highest_fall\",\"value\":23.5,\"unit\":\"Blocks\","
            + "\"aggregation\":\"Max\",\"contexts\":null}],"
            + "\"economy\":{\"coinsEarned\":100,\"coinsSpent\":5,\"gemsEarned\":0,\"gemsSpent\":0},\"discoveries\":null}";

    @Test
    void userStatisticsAreReadActingAsTheViewer_WithThePeriodQuery() {
        responseJson = STATISTICS_JSON;

        var stats = api.getUserStatistics(7, 9, "week", java.time.LocalDate.of(2026, 10, 1)).join();

        assertEquals("http://api.test/api/statistics/users/7?period=week&date=2026-10-01", seen.get(0).url().toString());
        assertEquals("9", seen.get(0).header("X-Acting-User-Id"));
        assertEquals(("Bob"), stats.username());
        assertEquals(java.time.LocalDate.of(2026, 9, 28), stats.periodStart());
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), stats.profile().firstJoinedAt());
        assertEquals(3600L, stats.profile().activePlaytimeSeconds());
        var pvp = stats.metric("pvp_kills").orElseThrow();
        assertEquals(null, pvp.value()); // hidden total (L2-5)
        assertEquals(5d, pvp.contexts().get(0).value());
        assertEquals(23.5d, stats.metric("highest_fall").orElseThrow().value());
        assertTrue(stats.metric("highest_fall").orElseThrow().contexts().isEmpty());
        assertEquals(100L, stats.economy().coinsEarned());
        assertEquals(null, stats.discoveries());
    }

    @Test
    void aConsoleReadSendsNoActingUser() {
        responseJson = STATISTICS_JSON;

        api.getUserStatistics(7, null, null, null).join();

        assertEquals("http://api.test/api/statistics/users/7?period=lifetime", seen.get(0).url().toString());
        assertEquals(null, seen.get(0).header("X-Acting-User-Id"));
    }

    @Test
    void titleHistoryIsMapped_AndAPrivateHistoryKeeps403() {
        responseJson = "{\"items\":[{\"changedAt\":\"2026-10-02T10:00:00Z\",\"fromTitleName\":\"Serf\",\"toTitleName\":\"Squire\","
                + "\"direction\":\"Promotion\"},{\"changedAt\":\"2026-10-01T10:00:00\",\"fromTitleName\":null,\"toTitleName\":\"Serf\","
                + "\"direction\":\"Demotion\"}],\"totalCount\":2,\"pageNumber\":1,\"pageSize\":20}";

        var page = api.getTitleHistory(7, 9, 1, 20).join();

        assertEquals("http://api.test/api/statistics/users/7/title-history?page=1&pageSize=20", seen.get(0).url().toString());
        assertEquals("9", seen.get(0).header("X-Acting-User-Id"));
        assertEquals(2, page.totalCount());
        assertTrue(page.items().get(0).isPromotion());
        assertFalse(page.items().get(1).isPromotion());
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), page.items().get(1).changedAt());

        status = 403;
        responseJson = "{\"error\":\"Forbidden\",\"message\":\"hidden\"}";
        CompletionException error = assertThrows(CompletionException.class, () -> api.getTitleHistory(7, 9, 1, 20).join());
        assertEquals(403, assertInstanceOf(ApiException.class, error.getCause().getCause()).getStatusCode());
    }
}
