package net.knightsandkings.knk.api.impl;

import java.time.Instant;
import java.time.LocalDate;
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
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-34 link 5: request/response JSON matches knk-web-api's Dtos/LeaderboardDtos.cs. */
class LeaderboardsApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "[]";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final LeaderboardsApiImpl api = new LeaderboardsApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(),
            executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void boardsAreListed() {
        responseJson = "[{\"boardKey\":\"active_playtime\",\"metric\":\"active_playtime\",\"context\":null,\"label\":\"Active playtime\","
                + "\"unit\":\"Seconds\",\"periods\":[\"weekly\",\"monthly\",\"lifetime\"],\"alwaysPublic\":true},"
                + "{\"boardKey\":\"pvp_kills@siege\",\"metric\":\"pvp_kills\",\"context\":\"siege\",\"label\":\"Player kills — Siege\","
                + "\"unit\":\"Count\",\"periods\":[\"weekly\"],\"alwaysPublic\":false}]";

        List<LeaderboardBoard> boards = api.listBoards().join();

        assertEquals("http://api.test/api/leaderboards", seen.get(0).url().toString());
        assertEquals(2, boards.size());
        assertTrue(boards.get(0).alwaysPublic());
        assertEquals(List.of("weekly", "monthly", "lifetime"), boards.get(0).periods());
        assertEquals("siege", boards.get(1).context());
    }

    @Test
    void aBoardIsReadActingAsTheViewer() {
        responseJson = "{\"boardKey\":\"pvp_kills@siege\",\"label\":\"Player kills — Siege\",\"unit\":\"Count\",\"period\":\"monthly\","
                + "\"periodStart\":\"2026-10-01\",\"generatedAt\":\"2026-10-03T10:00:00Z\",\"totalRanked\":12,"
                + "\"entries\":[{\"rank\":1,\"userId\":4,\"username\":\"dave\",\"value\":15,\"rawValue\":15},"
                + "{\"rank\":1,\"userId\":9,\"username\":\"alice\",\"value\":15,\"rawValue\":15}],"
                + "\"viewer\":{\"rank\":1,\"value\":15,\"rawValue\":15}}";

        LeaderboardView view = api.getBoard("pvp_kills@siege", "monthly", 10, 9).join();

        assertEquals("http://api.test/api/leaderboards/pvp_kills%40siege?period=monthly&top=10", seen.get(0).url().toString());
        assertEquals("9", seen.get(0).header("X-Acting-User-Id"));
        assertEquals(LocalDate.of(2026, 10, 1), view.periodStart());
        assertEquals(Instant.parse("2026-10-03T10:00:00Z"), view.generatedAt());
        assertEquals(12, view.totalRanked());
        assertEquals(List.of(1, 1), view.entries().stream().map(LeaderboardView.Entry::rank).toList());
        assertEquals(1, view.viewer().rank());
    }

    @Test
    void anAnonymousReadSendsNoActingUser_AndTopIsClamped_AndRefusalsKeepTheStatus() {
        responseJson = "{\"boardKey\":\"active_playtime\",\"period\":\"weekly\",\"generatedAt\":null,\"totalRanked\":0,\"entries\":[],\"viewer\":null}";

        LeaderboardView view = api.getBoard("active_playtime", null, 500, null).join();

        assertEquals("http://api.test/api/leaderboards/active_playtime?period=lifetime&top=50", seen.get(0).url().toString());
        assertNull(seen.get(0).header("X-Acting-User-Id"));
        assertNull(view.generatedAt());
        assertNull(view.viewer());

        status = 401;
        responseJson = "{\"error\":\"SignInRequired\"}";
        CompletionException error = assertThrows(CompletionException.class, () -> api.getBoard("gate_damage", "weekly", 10, null).join());
        assertEquals(401, assertInstanceOf(ApiException.class, error.getCause().getCause()).getStatusCode());
    }
}
