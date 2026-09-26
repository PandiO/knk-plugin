package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.currency.CurrencyDtos;
import net.knightsandkings.knk.api.mapper.CurrencyMapper;
import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlert;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertPage;
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.ReversalOutcome;
import net.knightsandkings.knk.core.domain.currency.TransferLock;
import net.knightsandkings.knk.core.domain.currency.TransferLimits;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.CurrencyApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * {@link CurrencyApi} over knk-web-api's {@code api/currency} routes (currency DESIGN.md §3.6,
 * IMPLEMENTATION_PLAN.md Phase 3) and the staff {@code api/currency/admin} routes (Phases 4-5).
 * <p>
 * Writes carry {@code X-Acting-User-Id} = the sending player or staff member (the API refuses a
 * payment for anyone else) and
 * one {@code Idempotency-Key} generated per call before the first attempt. A write that fails
 * with an I/O error or a 5xx is retried with that same key after 250 ms, 1 s and 3 s (configurable),
 * so a request the API did process is answered from its stored result instead of paying twice.
 * If every attempt fails the call completes with {@link CurrencyError#UNKNOWN_OUTCOME}. 4xx answers
 * are never retried: they become a {@link CurrencyException} with the API's code and details.
 */
public class CurrencyApiImpl extends BaseApiImpl implements CurrencyApi {
    private static final Logger LOGGER = Logger.getLogger(CurrencyApiImpl.class.getName());

    private static final String ENDPOINT = "/currency";
    public static final String ACTING_USER_HEADER = UsersCommandApiImpl.ACTING_USER_HEADER;

    /** Pauses before the 2nd, 3rd, 4th … attempt of a write; its length + 1 is the number of attempts. */
    static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(3));

    /** Sleeps between retries; replaceable so tests don't wait. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final List<Duration> retryDelays;
    private final Sleeper sleeper;

    public CurrencyApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                           ExecutorService executor, boolean debugLogging) {
        this(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging, DEFAULT_RETRY_DELAYS,
            duration -> Thread.sleep(duration.toMillis()));
    }

    CurrencyApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                    ExecutorService executor, boolean debugLogging, List<Duration> retryDelays, Sleeper sleeper) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
        this.retryDelays = List.copyOf(retryDelays);
        this.sleeper = sleeper;
    }

    /** Number of attempts a write makes: {@code 1 + retryDelays}. */
    public int maxAttempts() {
        return retryDelays.size() + 1;
    }

    // ===== Reads =====

    @Override
    public CompletableFuture<Balances> getBalances(int userId) {
        return read(baseUrl + ENDPOINT + "/balances/" + userId,
            json -> CurrencyMapper.mapBalances(objectMapper.readValue(json, CurrencyDtos.BalancesDto.class)));
    }

    @Override
    public CompletableFuture<LeaderboardPage> getLeaderboard(BalanceCurrency currency, int page, int pageSize) {
        String url = baseUrl + ENDPOINT + "/leaderboard?currency=" + currency.property() + "&page=" + Math.max(1, page)
            + "&pageSize=" + Math.max(1, pageSize);
        return read(url, json -> CurrencyMapper.mapLeaderboard(objectMapper.readValue(json, CurrencyDtos.LeaderboardDto.class)));
    }

    @Override
    public CompletableFuture<TransferLimits> getLimits(int userId, BalanceCurrency currency) {
        return read(baseUrl + ENDPOINT + "/limits/" + userId + "?currency=" + currency.property(),
            json -> CurrencyMapper.mapLimits(objectMapper.readValue(json, CurrencyDtos.TransferLimitsDto.class)));
    }

    @Override
    public CompletableFuture<LedgerPage> getTransactions(int userId, BalanceCurrency currency, int page, int pageSize) {
        String url = baseUrl + ENDPOINT + "/users/" + userId + "/transactions?page=" + Math.max(1, page)
            + "&pageSize=" + Math.max(1, pageSize) + (currency != null ? "&currency=" + currency.property() : "");
        return read(url, json -> CurrencyMapper.mapLedger(objectMapper.readValue(json, CurrencyDtos.LedgerPageDto.class)));
    }

    // ===== Writes =====

    @Override
    public CompletableFuture<TransferOutcome> transfer(int senderUserId, int recipientUserId, BalanceCurrency currency, long amount,
                                                       boolean bypassLimits) {
        // One key per /pay, created before the first attempt so every retry of it carries the same key.
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = objectMapper.writeValueAsString(new CurrencyDtos.CreateTransferDto(
                    senderUserId, recipientUserId, currency.wireValue(), amount, bypassLimits));
                String json = postWithRetries(baseUrl + ENDPOINT + "/transfers", body, senderUserId, idempotencyKey);
                return CurrencyMapper.mapTransfer(objectMapper.readValue(json, CurrencyDtos.TransferResultDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the transfer result", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TransferOutcome> confirmTransfer(int senderUserId, String pendingPublicId, boolean bypassLimits) {
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = baseUrl + ENDPOINT + "/transfers/pending/" + encode(pendingPublicId) + "/confirm"
                    + (bypassLimits ? "?bypassLimits=true" : "");
                String json = postWithRetries(url, "{}", senderUserId, idempotencyKey);
                return CurrencyMapper.mapTransfer(objectMapper.readValue(json, CurrencyDtos.TransferResultDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the confirmation result", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<PendingTransfer> cancelTransfer(int senderUserId, String pendingPublicId) {
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = baseUrl + ENDPOINT + "/transfers/pending/" + encode(pendingPublicId) + "/cancel";
                String json = postWithRetries(url, "{}", senderUserId, idempotencyKey);
                return CurrencyMapper.mapPending(objectMapper.readValue(json, CurrencyDtos.PendingTransferDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the cancellation result", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ReversalOutcome> reverseTransaction(int actingUserId, String publicId, String note, boolean allowPartial) {
        // Retries are safe without a client key: the server keys a reversal by the transaction
        // (reverse:{id}), so a repeat returns the same reversal.
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = objectMapper.writeValueAsString(new CurrencyDtos.ReverseTransactionDto(note, allowPartial));
                String url = baseUrl + ENDPOINT + "/admin/transactions/" + encode(publicId) + "/reverse";
                String json = sendWithRetries("POST", url, body, actingUserId, idempotencyKey);
                return CurrencyMapper.mapReversal(objectMapper.readValue(json, CurrencyDtos.ReversalResultDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the reversal result", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TransferLock> lockTransfers(int actingUserId, int userId, String reason) {
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = objectMapper.writeValueAsString(new CurrencyDtos.SetTransferLockDto(reason));
                String json = sendWithRetries("PUT", lockUrl(userId), body, actingUserId, idempotencyKey);
                return CurrencyMapper.mapLock(objectMapper.readValue(json, CurrencyDtos.TransferLockDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the transfer lock", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TransferLock> unlockTransfers(int actingUserId, int userId) {
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = sendWithRetries("DELETE", lockUrl(userId), null, actingUserId, idempotencyKey);
                return CurrencyMapper.mapLock(objectMapper.readValue(json, CurrencyDtos.TransferLockDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the transfer lock", e);
            }
        }, executor);
    }

    // ===== Currency alerts (Phase 5) =====

    @Override
    public CompletableFuture<CurrencyAlertPage> getAlerts(boolean includeAcknowledged, int page, int pageSize) {
        String url = baseUrl + ENDPOINT + "/admin/alerts?status=" + (includeAcknowledged ? "all" : "open")
            + "&page=" + Math.max(1, page) + "&pageSize=" + Math.max(1, pageSize);
        return read(url, json -> CurrencyMapper.mapAlertPage(objectMapper.readValue(json, CurrencyDtos.CurrencyAlertPageDto.class)));
    }

    @Override
    public CompletableFuture<CurrencyAlert> acknowledgeAlert(int actingUserId, long alertId) {
        // Acknowledging is repeatable (an acknowledged alert is returned unchanged), so a retry is safe.
        String idempotencyKey = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = sendWithRetries("POST", baseUrl + ENDPOINT + "/admin/alerts/" + alertId + "/ack", "{}",
                    actingUserId, idempotencyKey);
                return CurrencyMapper.mapAlert(objectMapper.readValue(json, CurrencyDtos.CurrencyAlertDto.class));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the acknowledged alert", e);
            }
        }, executor);
    }

    private String lockUrl(int userId) {
        return baseUrl + ENDPOINT + "/admin/users/" + userId + "/transfer-lock";
    }

    // ===== Plumbing =====

    @FunctionalInterface
    private interface JsonReader<T> {
        T read(String json) throws IOException;
    }

    private <T> CompletableFuture<T> read(String url, JsonReader<T> reader) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.read(get(url));
            } catch (ApiException e) {
                throw toCurrencyException(e);
            } catch (IOException e) {
                throw new RuntimeException("Currency request failed: " + url, e);
            }
        }, executor);
    }

    /**
     * POSTs {@code body} with the acting player and {@code idempotencyKey}, retrying I/O errors and
     * 5xx with the same key. Returns the response body; throws a {@link CurrencyException}.
     */
    String postWithRetries(String url, String body, int actingUserId, String idempotencyKey) {
        return sendWithRetries("POST", url, body, actingUserId, idempotencyKey);
    }

    /**
     * {@link #postWithRetries} for any method; a null {@code body} sends none (DELETE). Only for
     * requests that are safe to repeat: keyed writes, or PUT/DELETE that set a state.
     */
    String sendWithRetries(String method, String url, String body, int actingUserId, String idempotencyKey) {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
            if (attempt > 1) {
                try {
                    sleeper.sleep(retryDelays.get(attempt - 2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Request.Builder builder = newRequest(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .method(method, body == null ? null : RequestBody.create(body, MediaType.get("application/json")));
            if (actingUserId > 0) {
                // 0 = the console: no acting user, the API records the game server itself.
                builder.header(ACTING_USER_HEADER, String.valueOf(actingUserId));
            }
            Request request = builder.build();
            if (debugLogging) {
                LOGGER.info("API Request: " + method + " " + url + " (attempt " + attempt + ", Idempotency-Key " + idempotencyKey + ")");
                if (body != null) {
                    LOGGER.info("  Body: " + snippet(body));
                }
            }
            try {
                return execute(request, url);
            } catch (ApiException e) {
                if (e.getStatusCode() < 500) {
                    throw toCurrencyException(e);
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            LOGGER.warning("Currency write " + method + " " + url + " failed (attempt " + attempt + "/" + maxAttempts() + "): "
                + (last != null ? last.getMessage() : "interrupted"));
        }
        throw new CurrencyException(new CurrencyError(CurrencyError.UNKNOWN_OUTCOME,
            "The API didn't answer; the request may or may not have gone through.", Map.of("idempotencyKey", idempotencyKey)), -1, last);
    }

    /** A 4xx body {@code {code, message, details}} as a CurrencyException (anything unparseable keeps the status as its code). */
    CurrencyException toCurrencyException(ApiException e) {
        String code = "Http" + e.getStatusCode();
        String message = e.getMessage();
        Map<String, Object> details = null;
        String body = e.getResponseBody();
        if (body != null && !body.isBlank()) {
            try {
                CurrencyDtos.CurrencyErrorDto dto = objectMapper.readValue(body, CurrencyDtos.CurrencyErrorDto.class);
                if (dto.code() != null) {
                    code = dto.code();
                } else if (dto.error() != null) {
                    code = dto.error();
                }
                if (dto.message() != null) {
                    message = dto.message();
                }
                details = dto.details();
            } catch (IOException | RuntimeException ignored) {
                // not JSON (e.g. a proxy error page): keep the status code
            }
        }
        return new CurrencyException(new CurrencyError(code, message, details), e.getStatusCode(), e);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
