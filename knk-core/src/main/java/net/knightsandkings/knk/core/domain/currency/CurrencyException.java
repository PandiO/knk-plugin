package net.knightsandkings.knk.core.domain.currency;

/**
 * A refused (or unconfirmed) currency request, carrying the API's {@link CurrencyError}. When
 * {@link #outcomeUnknown()} is true the request was a write the API never answered after every
 * retry: it may or may not have gone through, so the player is told to check /transactions
 * rather than to try again blindly (currency DESIGN.md §3.6 pay-unknown).
 */
public class CurrencyException extends RuntimeException {
    private final CurrencyError error;
    private final int httpStatus;

    public CurrencyException(CurrencyError error, int httpStatus, Throwable cause) {
        super(error.code() + ": " + error.plainMessage(), cause);
        this.error = error;
        this.httpStatus = httpStatus;
    }

    public CurrencyError error() {
        return error;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean outcomeUnknown() {
        return error.is(CurrencyError.UNKNOWN_OUTCOME);
    }

    /** The CurrencyException somewhere in {@code throwable}'s cause chain (CompletionException etc.), or null. */
    public static CurrencyException find(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof CurrencyException currencyException) {
                return currencyException;
            }
            cause = cause.getCause();
        }
        return null;
    }
}
