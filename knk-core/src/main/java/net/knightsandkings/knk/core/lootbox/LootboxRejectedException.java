package net.knightsandkings.knk.core.lootbox;

import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The API refused a lootbox action on purpose (docs/specs/lootboxes/DESIGN.md §3.3): 409 {@code {code, message}} or
 * 429 {@code {code: DailyLimit, scope, limit, resetsAt}}. Anything else (API down, 500) stays an ordinary failure,
 * which the plugin shows as "try again in a moment".
 */
public class LootboxRejectedException extends RuntimeException {

    // 409 codes (LootboxConflictException on the API side).
    public static final String ALREADY_CLAIMED = "AlreadyClaimed";
    public static final String EXPIRED = "Expired";
    public static final String REMOVED = "Removed";
    public static final String TOKEN_MISMATCH = "TokenMismatch";
    public static final String DISABLED = "Disabled";
    public static final String FROZEN = "Frozen";
    public static final String USER_INACTIVE = "UserInactive";
    public static final String EMPTY_POOL = "EmptyPool";
    public static final String IDEMPOTENCY_KEY_REUSED = "IdempotencyKeyReused";
    public static final String NAME_TAKEN = "NameTaken";
    public static final String REGION_IN_USE = "RegionInUse";
    // 429
    public static final String DAILY_LIMIT = "DailyLimit";
    public static final String SCOPE_TYPE = "Type";

    private final int statusCode;
    private final String code;
    private final String scope;
    private final Integer limit;
    private final Instant resetsAt;

    public LootboxRejectedException(int statusCode, String code, String message, String scope, Integer limit, Instant resetsAt) {
        super(message != null && !message.isBlank() ? message : code);
        this.statusCode = statusCode;
        this.code = code;
        this.scope = scope;
        this.limit = limit;
        this.resetsAt = resetsAt;
    }

    public int statusCode() {
        return statusCode;
    }

    /** The API's machine-readable reason, e.g. {@link #ALREADY_CLAIMED}; may be null for a bare 409. */
    public String code() {
        return code;
    }

    /** 429 only: Global or Type. */
    public String scope() {
        return scope;
    }

    /** 429 only: the limit that was reached. */
    public Integer limit() {
        return limit;
    }

    /** 429 only: the next 00:00 UTC. */
    public Instant resetsAt() {
        return resetsAt;
    }

    public boolean is(String expectedCode) {
        return expectedCode != null && expectedCode.equalsIgnoreCase(code);
    }

    public boolean isDailyLimit() {
        return statusCode == 429 || is(DAILY_LIMIT);
    }

    /** The rejection somewhere in a future's failure chain, or null when the failure is something else. */
    public static LootboxRejectedException find(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof LootboxRejectedException rejected) {
                return rejected;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    /** Unwraps {@link CompletionException}/{@link ExecutionException} for logging. */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
