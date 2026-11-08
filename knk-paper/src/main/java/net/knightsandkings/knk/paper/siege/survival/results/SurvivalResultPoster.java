package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.exception.ApiException;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts a survival result to the API off the main thread (link S8b, DESIGN §9 "settle once", S8a contract): the same
 * body is retried with exponential backoff on transport errors and 5xx answers (the POST is idempotent: 201 the first
 * time, 200 on the same body again); a 409 {@code SettlementMismatch} is logged SEVERE with the API's message and
 * stops (the numbers are never adjusted to make it pass); 409 {@code ResultAlreadyStored} / {@code MatchAborted}
 * fetch the stored row with {@code GET …/result} and use it; every other 4xx is final. Never throws into the caller.
 */
public final class SurvivalResultPoster {

    /** Where a post stands. */
    public enum State { PENDING, POSTED, FAILED }

    /** The code of a post that gave up without a conflict code. */
    public static final String CODE_TRANSPORT = "Transport";
    public static final String CODE_UNKNOWN_MATCH = "UnknownMatch";
    public static final String CODE_REJECTED = "Rejected";

    /**
     * @param stored     the stored result (POSTED), from the POST answer or, when {@code fromStore}, from the GET after a
     *                   {@code ResultAlreadyStored} / {@code MatchAborted}
     * @param code       the 409 code or one of the {@code CODE_*} constants (FAILED)
     * @param message    the API's message when it sent one
     */
    public record Outcome(State state, MatchResultDto stored, String code, String message, int attempts, boolean fromStore) {
        public boolean posted() {
            return state == State.POSTED;
        }
    }

    /** Runs a task after a delay (replaced by a synchronous executor in tests). */
    public interface Delayer {
        Executor after(long millis);

        static Delayer common() {
            return millis -> CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS);
        }
    }

    public static final int DEFAULT_MAX_ATTEMPTS = 8;
    public static final long DEFAULT_BASE_BACKOFF_MILLIS = 2_000L;
    public static final long DEFAULT_MAX_BACKOFF_MILLIS = 60_000L;

    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final SiegeSurvivalMatchApiImpl api;
    private final Delayer delayer;
    private final int maxAttempts;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;
    private final Logger logger;

    public SurvivalResultPoster(SiegeSurvivalMatchApiImpl api, Logger logger) {
        this(api, Delayer.common(), DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MILLIS, DEFAULT_MAX_BACKOFF_MILLIS, logger);
    }

    public SurvivalResultPoster(SiegeSurvivalMatchApiImpl api, Delayer delayer, int maxAttempts, long baseBackoffMillis,
                                long maxBackoffMillis, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.delayer = delayer == null ? Delayer.common() : delayer;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffMillis = Math.max(0, baseBackoffMillis);
        this.maxBackoffMillis = Math.max(this.baseBackoffMillis, maxBackoffMillis);
        this.logger = logger;
    }

    /** Posts {@code body} for {@code matchId}; the future never completes exceptionally. {@code context} names the log lines. */
    public CompletableFuture<Outcome> post(long matchId, MatchResultDto body, String context) {
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        attempt(matchId, body, context, 1, done);
        return done;
    }

    private void attempt(long matchId, MatchResultDto body, String context, int attempt, CompletableFuture<Outcome> done) {
        CompletableFuture<MatchResultDto> call;
        try {
            call = api.postResult(matchId, body);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        if (call == null) call = CompletableFuture.completedFuture(null);
        call.whenComplete((stored, error) -> {
            try {
                if (error == null) {
                    if (stored == null) {
                        severe(context, "the API knows no match " + matchId + " (404); the result is not stored", null);
                        done.complete(new Outcome(State.FAILED, null, CODE_UNKNOWN_MATCH, null, attempt, false));
                    } else {
                        info(context, "result of match " + matchId + " posted" + describeSettlement(stored));
                        done.complete(new Outcome(State.POSTED, stored, null, null, attempt, false));
                    }
                    return;
                }
                handleError(matchId, body, context, attempt, done, unwrap(error));
            } catch (RuntimeException e) {
                severe(context, "handling the result answer of match " + matchId + " failed", e);
                done.complete(new Outcome(State.FAILED, null, CODE_REJECTED, e.getMessage(), attempt, false));
            }
        });
    }

    private void handleError(long matchId, MatchResultDto body, String context, int attempt, CompletableFuture<Outcome> done, Throwable error) {
        ApiException apiError = error instanceof ApiException a ? a : null;
        int status = apiError == null ? 0 : apiError.getStatusCode();
        if (status == 409) {
            String code = api.conflictCode(apiError);
            String message = messageOf(apiError.getResponseBody());
            if (SiegeSurvivalMatchApiImpl.SETTLEMENT_MISMATCH.equals(code)) {
                severe(context, "the API rejected the settlement of match " + matchId + " (SettlementMismatch): " + message
                    + " - nothing stored; the plugin's numbers are kept as they are", null);
                done.complete(new Outcome(State.FAILED, null, code, message, attempt, false));
                return;
            }
            if (SiegeSurvivalMatchApiImpl.RESULT_ALREADY_STORED.equals(code) || SiegeSurvivalMatchApiImpl.MATCH_ABORTED.equals(code)) {
                info(context, "match " + matchId + " already has a result row (" + code + "); using the stored one");
                fetchStored(matchId, context, attempt, code, message, done);
                return;
            }
            severe(context, "the API refused the result of match " + matchId + " (" + code + "): " + message, null);
            done.complete(new Outcome(State.FAILED, null, code == null ? CODE_REJECTED : code, message, attempt, false));
            return;
        }
        if (status >= 400 && status < 500) {
            severe(context, "the API refused the result of match " + matchId + " (HTTP " + status + "): "
                + messageOf(apiError.getResponseBody()), null);
            done.complete(new Outcome(State.FAILED, null, CODE_REJECTED, messageOf(apiError.getResponseBody()), attempt, false));
            return;
        }
        // Transport error or 5xx: the same body again, with backoff (idempotent on the API).
        if (attempt >= maxAttempts) {
            severe(context, "posting the result of match " + matchId + " failed " + attempt + " times; giving up for now"
                + " (kept for the next start)", error);
            done.complete(new Outcome(State.FAILED, null, CODE_TRANSPORT, error.getMessage(), attempt, false));
            return;
        }
        long delay = Math.min(maxBackoffMillis, baseBackoffMillis * (1L << Math.min(20, attempt - 1)));
        warn(context, "posting the result of match " + matchId + " failed (attempt " + attempt + "/" + maxAttempts + "): "
            + describe(error) + "; retrying in " + (delay / 1000) + " s");
        delayer.after(delay).execute(() -> attempt(matchId, body, context, attempt + 1, done));
    }

    private void fetchStored(long matchId, String context, int attempt, String code, String message, CompletableFuture<Outcome> done) {
        CompletableFuture<MatchResultDto> get;
        try {
            get = api.getResult(matchId);
        } catch (RuntimeException e) {
            get = CompletableFuture.failedFuture(e);
        }
        if (get == null) get = CompletableFuture.completedFuture(null);
        get.whenComplete((stored, error) -> {
            if (error == null && stored != null) {
                info(context, "stored result of match " + matchId + " fetched" + describeSettlement(stored));
                done.complete(new Outcome(State.POSTED, stored, code, message, attempt, true));
            } else {
                warn(context, "match " + matchId + " has a result row (" + code + ") but it could not be fetched: "
                    + (error == null ? "404" : describe(unwrap(error))));
                done.complete(new Outcome(State.FAILED, null, code, message, attempt, true));
            }
        });
    }

    /** The one-line settlement summary for the log: totals, players settled and the ledger posting id. */
    public static String describeSettlement(MatchResultDto stored) {
        if (stored == null || stored.settlement() == null) return "";
        var s = stored.settlement();
        if (!Boolean.TRUE.equals(s.settlesRewards())) return "; no settlement (" + stored.endReason() + ")";
        return "; settled " + (s.totalCoins() == null ? 0 : s.totalCoins()) + " coins / " + (s.totalXp() == null ? 0 : s.totalXp())
            + " xp for " + (s.playersSettled() == null ? "?" : s.playersSettled()) + " player(s), ledger posting "
            + (s.ledgerPostingId() == null ? "-" : s.ledgerPostingId()) + (s.settledAt() == null ? "" : " at " + s.settledAt());
    }

    static String messageOf(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        Matcher m = MESSAGE.matcher(responseBody);
        if (!m.find()) return responseBody.length() > 400 ? responseBody.substring(0, 400) : responseBody;
        return m.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }

    static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown error";
        if (error instanceof ApiException a && a.getStatusCode() > 0) return "HTTP " + a.getStatusCode();
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private void info(String context, String message) {
        if (logger != null) logger.info("[Survival] " + context + ": " + message);
    }

    private void warn(String context, String message) {
        if (logger != null) logger.warning("[Survival] " + context + ": " + message);
    }

    private void severe(String context, String message, Throwable error) {
        if (logger == null) return;
        if (error == null) logger.severe("[Survival] " + context + ": " + message);
        else logger.log(Level.SEVERE, "[Survival] " + context + ": " + message, error);
    }
}
