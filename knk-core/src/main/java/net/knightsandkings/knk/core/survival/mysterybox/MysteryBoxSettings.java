package net.knightsandkings.knk.core.survival.mysterybox;

/**
 * Per-box-point settings (S34, S24, §8a): the roll price (default 1000 match currency, charged once per roll id
 * before the roll through the match ledger) and the claim timeout (default 10 s, after which the shown item is
 * forfeited and the box is free again).
 */
public record MysteryBoxSettings(long rollPrice, long claimTimeoutMillis) {

    public static final long DEFAULT_ROLL_PRICE = 1000;
    public static final long DEFAULT_CLAIM_TIMEOUT_MILLIS = 10_000L;

    public MysteryBoxSettings {
        if (rollPrice < 0) throw new IllegalArgumentException("rollPrice must be >= 0");
        if (claimTimeoutMillis < 0) throw new IllegalArgumentException("claimTimeoutMillis must be >= 0");
    }

    public static MysteryBoxSettings defaults() {
        return new MysteryBoxSettings(DEFAULT_ROLL_PRICE, DEFAULT_CLAIM_TIMEOUT_MILLIS);
    }
}
