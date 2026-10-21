package net.knightsandkings.knk.core.domain.duels;

import net.knightsandkings.knk.core.exception.ApiException;

/**
 * The API refused a duel request with a 4xx and a body of {@code { code, message }} (KNG-213): a rule the duel
 * breaks (422, e.g. {@code PairCooldown}, {@code ReviewHoldOpen}, {@code InsufficientFunds}), a state conflict
 * (409 {@code WrongState}, {@code TokenReused}), an unknown match (404) or a malformed request (400). Never
 * retried. Network errors and 5xx stay plain {@link ApiException}s (status -1 or 5xx), which the caller retries
 * with the same Idempotency-Key.
 */
public class DuelRefusedException extends ApiException {
    private final String code;
    private final String playerMessage;

    public DuelRefusedException(String requestUrl, int statusCode, String code, String playerMessage, String responseBody) {
        super(requestUrl, statusCode, code + ": " + playerMessage, responseBody);
        this.code = code;
        this.playerMessage = playerMessage;
    }

    /** The API's DuelErrorCode name, e.g. {@code DuelRestricted}. */
    public String code() {
        return code;
    }

    /** Written for players; safe to show in chat. */
    public String playerMessage() {
        return playerMessage;
    }

    public boolean is(String expected) {
        return expected.equals(code);
    }

    /** The DuelRefusedException somewhere in {@code throwable}'s cause chain (CompletionException etc.), or null. */
    public static DuelRefusedException find(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof DuelRefusedException refused) {
                return refused;
            }
            cause = cause.getCause();
        }
        return null;
    }
}
