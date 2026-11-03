package net.knightsandkings.knk.core.survival.economy;

/** The outcome of a transfer or charge on the {@link MatchLedger}. */
public sealed interface LedgerResult {

    boolean accepted();

    /**
     * @param retryId          the request's retry id
     * @param balanceAfter     the payer's balance after the operation
     * @param replayed         true when this answers a retry of an already applied request (nothing paid twice)
     */
    record Accepted(String retryId, long balanceAfter, boolean replayed) implements LedgerResult {
        @Override
        public boolean accepted() {
            return true;
        }
    }

    record Rejected(String retryId, Reason reason) implements LedgerResult {
        @Override
        public boolean accepted() {
            return false;
        }
    }

    enum Reason {
        AMOUNT_NOT_POSITIVE,
        WRONG_MATCH,
        PAYER_NOT_IN_MATCH,
        RECIPIENT_NOT_IN_MATCH,
        SELF_TRANSFER,
        PAYER_IS_GHOST,
        RECIPIENT_IS_GHOST,
        INSUFFICIENT_BALANCE,
        /** The retry id was used before by a different request. */
        RETRY_ID_CONFLICT,
        MATCH_ENDED
    }
}
