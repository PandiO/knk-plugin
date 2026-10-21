package net.knightsandkings.knk.core.duel;

import java.util.EnumSet;
import java.util.Set;

/**
 * The state of a match's item escrow file {@code duel-escrow/<matchToken>.yml} (DESIGN §7.2–7.4). The
 * spool (plan Phase 4) writes the new state before every change and checks {@link #canMoveTo}.
 *
 * <pre>
 * LOCKING -API created-> ESCROWED -paid-> SETTLED
 *    |                      |  -held-> HELD -release-> SETTLED
 *    |                      |            -void-------> REFUNDED
 *    +-refused/404--------> +--cancel, abort, draw---> REFUNDED
 * SETTLED / REFUNDED -an owed stack couldn't be delivered (inventory, ender chest full)-> CLAIM_PENDING
 * </pre>
 */
public enum DuelEscrowState {
    /** Items taken at lock-in; the API hasn't answered yet. */
    LOCKING,
    /** The match exists and holds the stakes (and, in drop mode, the loser's spoils once captured). */
    ESCROWED,
    /** A decisive result held for staff review (§10.3 tier 2): nothing is delivered. */
    HELD,
    /** Delivered to the winner. */
    SETTLED,
    /** Each side's items delivered back to their owner. */
    REFUNDED,
    /** Some stacks are still owed; delivered on join or with {@code /duel claim}. */
    CLAIM_PENDING;

    public boolean canMoveTo(DuelEscrowState next) {
        return allowedNext().contains(next);
    }

    public Set<DuelEscrowState> allowedNext() {
        return switch (this) {
            case LOCKING -> EnumSet.of(ESCROWED, REFUNDED);
            case ESCROWED -> EnumSet.of(HELD, SETTLED, REFUNDED);
            case HELD -> EnumSet.of(SETTLED, REFUNDED);
            case SETTLED, REFUNDED -> EnumSet.of(CLAIM_PENDING);
            case CLAIM_PENDING -> EnumSet.noneOf(DuelEscrowState.class);
        };
    }

    /** Everything is decided; the file only tracks what is still owed. */
    public boolean isFinal() {
        return this == SETTLED || this == REFUNDED || this == CLAIM_PENDING;
    }
}
