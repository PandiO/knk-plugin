package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * A match's one result (the end-once guard in {@link DuelStateMachine} makes sure there is only one).
 *
 * @param reason the end reason
 * @param winner the winning side for a decisive result, otherwise null
 */
public record DuelResult(DuelEndReason reason, DuelSide winner) {
    public DuelResult {
        Objects.requireNonNull(reason, "reason");
        if (reason.isDecisive() != (winner != null)) {
            throw new IllegalArgumentException(reason + " needs " + (reason.isDecisive() ? "a" : "no") + " winner");
        }
    }

    public static DuelResult win(DuelSide winner, DuelEndReason reason) {
        return new DuelResult(reason, Objects.requireNonNull(winner, "winner"));
    }

    public static DuelResult noWinner(DuelEndReason reason) {
        return new DuelResult(reason, null);
    }

    public DuelSide loser() {
        return winner == null ? null : winner.other();
    }

    public boolean isDecisive() {
        return winner != null;
    }
}
