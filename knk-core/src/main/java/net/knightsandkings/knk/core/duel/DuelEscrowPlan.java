package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * Who gets the escrowed items (DESIGN §7.3–7.5, §8.5): each side's staked stacks and the drop-mode
 * spoils taken from the loser. Review-agnostic: the API decides paid / held / released / voided, this
 * turns its answer into deliveries. Coins are the API's (§7.1).
 *
 * @param challengerStakeTo who gets the challenger's staked items, or null = they stay in escrow
 * @param opponentStakeTo   who gets the opponent's staked items, or null = they stay in escrow
 * @param spoilsTo          who gets the spoils (they were taken from {@code spoilsFrom}), or null = stay
 * @param next              the escrow state to write
 */
public record DuelEscrowPlan(DuelSide challengerStakeTo, DuelSide opponentStakeTo, DuelSide spoilsTo, DuelEscrowState next) {

    /** The API's answer to a result or a staff review (§7.4). */
    public enum Decision {
        /** {@code complete} answered {@code paid}, or staff released a held result. */
        PAID,
        /** {@code complete} answered {@code held} (tier 2). */
        HELD,
        /** Staff voided a held result: refund both sides, spoils back to the loser. */
        VOIDED
    }

    public DuelEscrowPlan {
        Objects.requireNonNull(next, "next");
    }

    /** Each side gets its own stake back; spoils (if any) go back to {@code spoilsFrom}. */
    public static DuelEscrowPlan refund(DuelSide spoilsFrom) {
        return new DuelEscrowPlan(DuelSide.CHALLENGER, DuelSide.OPPONENT, spoilsFrom, DuelEscrowState.REFUNDED);
    }

    /** Both stakes and the spoils go to the winner. */
    public static DuelEscrowPlan payWinner(DuelSide winner) {
        Objects.requireNonNull(winner, "winner");
        return new DuelEscrowPlan(winner, winner, winner, DuelEscrowState.SETTLED);
    }

    /** Nothing moves: the result is under review. */
    public static DuelEscrowPlan hold() {
        return new DuelEscrowPlan(null, null, null, DuelEscrowState.HELD);
    }

    /**
     * The plan for a result.
     *
     * @param decision the API's answer; only read for a decisive result (every other result refunds)
     * @param spoilsFrom the side whose inventory was captured as spoils, or null when none was
     */
    public static DuelEscrowPlan forResult(DuelResult result, Decision decision, DuelSide spoilsFrom) {
        Objects.requireNonNull(result, "result");
        if (!result.isDecisive()) return refund(spoilsFrom);
        Objects.requireNonNull(decision, "decision");
        return switch (decision) {
            case PAID -> payWinner(result.winner());
            case HELD -> hold();
            case VOIDED -> refund(spoilsFrom);
        };
    }

    /**
     * Startup recovery (§7.3) from the API's view of the match.
     *
     * @param apiStatus the match status ({@code Completed}, {@code Cancelled}, …), or null for a 404
     * @param winner    the winner for a completed match, else null
     * @param held      {@code Review=Held}
     */
    public static DuelEscrowPlan forRecovery(String apiStatus, DuelSide winner, boolean held, DuelSide spoilsFrom) {
        if (held) return hold();
        if ("Completed".equals(apiStatus) && winner != null) return payWinner(winner);
        return refund(spoilsFrom);
    }

    /** True when the escrow keeps everything. */
    public boolean keepsEverything() {
        return challengerStakeTo == null && opponentStakeTo == null && spoilsTo == null;
    }
}
