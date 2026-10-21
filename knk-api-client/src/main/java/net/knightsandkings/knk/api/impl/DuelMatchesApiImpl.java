package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.DuelMatchDtos;
import net.knightsandkings.knk.api.mapper.DuelMatchMapper;
import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Completion;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.FightStart;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.LockRequest;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Telemetry;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * {@link DuelMatchesApi} over knk-web-api's {@code /api/DuelMatches} (arena duels KNG-213). Every write carries an
 * {@code Idempotency-Key} built from the match token or id ({@code duel-lock:<token>}, {@code duel-start:<token>},
 * {@code duel-complete:<token>}, {@code duel-cancel:<token>}, {@code duel-release:<id>}, ...), so a retry is the
 * same request; the API answers a repeat from the match's stored state. A 4xx with the API's {@code { code, message }}
 * becomes a {@link DuelRefusedException}; network errors and 5xx stay {@link ApiException}s for the caller's retry.
 * Staff actions name the staff member in {@code X-Acting-User-Id}.
 */
public class DuelMatchesApiImpl extends BaseApiImpl implements DuelMatchesApi {

    static final String ENDPOINT = "/DuelMatches";
    public static final String ACTING_USER_HEADER = UsersCommandApiImpl.ACTING_USER_HEADER;

    public DuelMatchesApiImpl(
            String baseUrl,
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            AuthProvider authProvider,
            ExecutorService executor,
            boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** The lock-in's key; the API refuses any other key on {@code POST /api/DuelMatches}. */
    public static String lockKey(UUID token) {
        return "duel-lock:" + token;
    }

    @Override
    public CompletableFuture<CheckResult> check(LockRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/check";
            return DuelMatchMapper.toCore(parse(post(url, DuelMatchMapper.toCreateRequest(request), null, null),
                    DuelMatchDtos.CheckResult.class, url));
        }, executor);
    }

    @Override
    public CompletableFuture<MatchRecord> lock(LockRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            return match(post(url, DuelMatchMapper.toCreateRequest(request), lockKey(request.token()), null), url);
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<MatchRecord>> findByToken(UUID token) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/by-token/" + token;
            try {
                return Optional.ofNullable(match(get(url), url));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return Optional.empty();
                throw e;
            } catch (IOException e) {
                throw new ApiException(url, "IO error reading the duel match", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<MatchRecord> startFight(int matchId, UUID token, FightStart start) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + matchId + "/start-fight";
            return match(post(url, DuelMatchMapper.toStartRequest(start), "duel-start:" + token, null), url);
        }, executor);
    }

    @Override
    public CompletableFuture<Result> complete(int matchId, UUID token, Completion completion) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + matchId + "/complete";
            return result(post(url, DuelMatchMapper.toCompleteRequest(completion), "duel-complete:" + token, null), url);
        }, executor);
    }

    @Override
    public CompletableFuture<Result> cancel(int matchId, UUID token, DuelEndReason reason, Telemetry telemetry) {
        DuelMatchDtos.CancelRequest body = DuelMatchMapper.toCancelRequest(reason, telemetry);
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + matchId + "/cancel";
            return result(post(url, body, "duel-cancel:" + token, null), url);
        }, executor);
    }

    @Override
    public CompletableFuture<List<Integer>> abortUnfinished(DuelEndReason reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/abort-unfinished";
            DuelMatchDtos.AbortUnfinishedResult dto = parse(post(url, new DuelMatchDtos.AbortUnfinishedRequest(reason.apiName()), null, null),
                    DuelMatchDtos.AbortUnfinishedResult.class, url);
            return dto == null || dto.abortedMatchIds() == null ? List.<Integer>of() : List.copyOf(dto.abortedMatchIds());
        }, executor);
    }

    @Override
    public CompletableFuture<Result> release(int matchId, int actingUserId, String reason) {
        return staffAction(matchId, "release", actingUserId, reason);
    }

    @Override
    public CompletableFuture<Result> voidHeld(int matchId, int actingUserId, String reason) {
        return staffAction(matchId, "void", actingUserId, reason);
    }

    private CompletableFuture<Result> staffAction(int matchId, String action, int actingUserId, String reason) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + matchId + "/" + action;
            return result(post(url, new DuelMatchDtos.StaffActionRequest(reason), "duel-" + action + ":" + matchId, actingUserId), url);
        }, executor);
    }

    private MatchRecord match(String json, String url) {
        return DuelMatchMapper.toCore(parse(json, DuelMatchDtos.Match.class, url));
    }

    private Result result(String json, String url) {
        Result result = DuelMatchMapper.toCore(parse(json, DuelMatchDtos.Result.class, url));
        if (result == null || result.match() == null) {
            throw new ApiException(url, 200, "Duel result answered without a match", "");
        }
        return result;
    }

    /** POSTs the body as JSON with the key (and the acting staff member); a 4xx refusal becomes a DuelRefusedException. */
    private String post(String url, Object body, String idempotencyKey, Integer actingUserId) {
        try {
            String json = objectMapper.writeValueAsString(body);
            Request.Builder builder = newRequest(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(RequestBody.create(json, MediaType.get("application/json")));
            if (idempotencyKey != null) builder.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
            if (actingUserId != null && actingUserId > 0) builder.header(ACTING_USER_HEADER, String.valueOf(actingUserId));
            if (debugLogging) {
                LOGGER.info("API Request: POST " + url + (idempotencyKey == null ? "" : " (Idempotency-Key " + idempotencyKey + ")"));
                LOGGER.info("  Body: " + snippet(json));
            }
            return execute(builder.build(), url);
        } catch (ApiException e) {
            throw refusal(e, url);
        } catch (IOException e) {
            throw new ApiException(url, "IO error calling the duel match API", e);
        }
    }

    private ApiException refusal(ApiException e, String url) {
        int status = e.getStatusCode();
        if (status < 400 || status >= 500) return e;
        String code = "HTTP_" + status;
        String message = e.getMessage();
        try {
            DuelMatchDtos.Error error = e.getResponseBody() == null || e.getResponseBody().isBlank() ? null
                    : objectMapper.readValue(e.getResponseBody(), DuelMatchDtos.Error.class);
            if (error != null && error.code() != null) code = error.code();
            if (error != null && error.message() != null) message = error.message();
        } catch (IOException ignored) {
            // Not the API's error shape (a proxy page): keep the HTTP status as the code.
        }
        return new DuelRefusedException(url, status, code, message, e.getResponseBody());
    }
}
