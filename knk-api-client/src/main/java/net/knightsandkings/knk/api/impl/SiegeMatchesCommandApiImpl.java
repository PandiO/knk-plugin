package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.SiegeMatchDtos;
import net.knightsandkings.knk.api.mapper.SiegeMatchMapper;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Participant;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.RewardSummary;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeMatchesCommandApi;
import okhttp3.OkHttpClient;

/**
 * Siege Phase 6: the match lifecycle checkpoints over knk-web-api's {@code /api/siege-matches}
 * (DESIGN §3.10, §7.6, §11.2). Failures surface as {@link ApiException} (HTTP status, or -1 with the
 * IOException as the cause for network errors, which {@code RetryPolicy} retries). Retry, spooling and
 * recovery live in knk-core's {@code SiegeMatchRecorder}, which wraps this class.
 */
public class SiegeMatchesCommandApiImpl extends BaseApiImpl implements SiegeMatchesCommandApi {

    private static final String BASE_ENDPOINT = "/siege-matches";

    public SiegeMatchesCommandApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Long> createMatch(int siegeLobbyId, int siegeScenarioId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT;
            SiegeMatchDtos.MatchResponse dto = parse(post(url, new SiegeMatchDtos.CreateRequest(siegeLobbyId, siegeScenarioId)),
                    SiegeMatchDtos.MatchResponse.class, url);
            if (dto == null || dto.id() == null) {
                throw new ApiException(url, 200, "Create answered without a match id", "");
            }
            return dto.id().longValue();
        }, executor);
    }

    @Override
    public CompletableFuture<Void> startMatch(long matchId, List<Participant> participants) {
        return CompletableFuture.supplyAsync(() -> {
            post(baseUrl + BASE_ENDPOINT + "/" + matchId + "/start", SiegeMatchMapper.toStartRequest(participants));
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> participantLeft(long matchId, int userId, Instant leftAt) {
        return CompletableFuture.supplyAsync(() -> {
            post(baseUrl + BASE_ENDPOINT + "/" + matchId + "/participants/" + userId + "/left", SiegeMatchMapper.toLeftRequest(leftAt));
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<RewardSummary> completeMatch(long matchId, Completion completion) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/" + matchId + "/complete";
            String json = post(url, SiegeMatchMapper.toCompleteRequest(completion));
            return SiegeMatchMapper.toCore(parse(json, SiegeMatchDtos.ResultResponse.class, url), matchId);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> abortMatch(long matchId, SiegeEndReason reason) {
        return CompletableFuture.supplyAsync(() -> {
            post(baseUrl + BASE_ENDPOINT + "/" + matchId + "/abort", SiegeMatchMapper.toAbortRequest(reason));
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<List<Long>> abortUnfinished(SiegeEndReason reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + BASE_ENDPOINT + "/abort-unfinished";
            String json = post(url, SiegeMatchMapper.toAbortRequest(reason));
            return SiegeMatchMapper.toCore(parse(json, SiegeMatchDtos.AbortUnfinishedResponse.class, url));
        }, executor);
    }

    /** POSTs the body as JSON; IO errors become an ApiException that keeps the IOException as its cause. */
    private String post(String url, Object body) {
        try {
            return postJson(url, objectMapper.writeValueAsString(body));
        } catch (IOException e) {
            throw new ApiException(url, "IO error calling the siege match API", e);
        }
    }
}
