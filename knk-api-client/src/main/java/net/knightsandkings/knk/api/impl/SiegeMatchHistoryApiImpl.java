package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Alpha run 1 (PandiO/knk-workspace#174): {@code GET /api/siege-matches/public/users/{userId}?limit=n}, asked as the
 * player themself ({@code X-Acting-User-Id}, trusted only with the plugin key), so none of their numbers is hidden.
 * A 404 (unknown or inactive player) is an empty list.
 */
public class SiegeMatchHistoryApiImpl extends BaseApiImpl implements SiegeMatchHistoryApi {

    private static final String BASE_ENDPOINT = "/siege-matches/public/users/";

    public SiegeMatchHistoryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                                    ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** The wire rows (only what the command shows). */
    record TeamDto(@JsonProperty("name") String name, @JsonProperty("chatColor") String chatColor) {
    }

    record ParticipantDto(
            @JsonProperty("outcome") String outcome,
            @JsonProperty("kills") Integer kills,
            @JsonProperty("deaths") Integer deaths,
            @JsonProperty("coinsAwarded") Integer coinsAwarded,
            @JsonProperty("expAwarded") Integer expAwarded) {
    }

    record SummaryDto(
            @JsonProperty("id") Long id,
            @JsonProperty("siegeLobbyName") String siegeLobbyName,
            @JsonProperty("siegeScenarioName") String siegeScenarioName,
            @JsonProperty("endedAt") String endedAt,
            @JsonProperty("endReason") String endReason,
            @JsonProperty("team") TeamDto team,
            @JsonProperty("participant") ParticipantDto participant) {
    }

    @Override
    public CompletableFuture<List<PlayerMatch>> playerMatches(int userId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + userId + "?limit=" + Math.max(1, Math.min(50, limit));
            try {
                Request request = newRequest(url)
                        .header(UsersCommandApiImpl.ACTING_USER_HEADER, String.valueOf(userId))
                        .get()
                        .build();
                if (debugLogging) LOGGER.info("API Request: GET " + url);
                List<SummaryDto> rows = parse(execute(request, url), new TypeReference<List<SummaryDto>>() { }, url);
                return toCore(rows);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return List.<PlayerMatch>of();
                throw new RuntimeException("Failed to load the siege matches of user " + userId, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load the siege matches of user " + userId, e);
            }
        }, executor);
    }

    static List<PlayerMatch> toCore(List<SummaryDto> rows) {
        List<PlayerMatch> out = new ArrayList<>();
        if (rows == null) return out;
        for (SummaryDto row : rows) {
            if (row == null || row.id() == null) continue;
            ParticipantDto p = row.participant();
            out.add(new PlayerMatch(
                    row.id(),
                    row.siegeLobbyName(),
                    row.siegeScenarioName(),
                    instant(row.endedAt()),
                    row.endReason(),
                    row.team() == null ? null : row.team().name(),
                    row.team() == null ? null : row.team().chatColor(),
                    p == null ? null : p.outcome(),
                    p == null ? null : p.kills(),
                    p == null ? null : p.deaths(),
                    p == null ? null : p.coinsAwarded(),
                    p == null || p.expAwarded() == null ? 0 : p.expAwarded()));
        }
        return out;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(value.endsWith("Z") ? value : value + "Z");
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
