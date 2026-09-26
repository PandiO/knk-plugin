package net.knightsandkings.knk.core.domain.currency;

import java.util.List;
import java.util.Map;

/**
 * A staff reversal of a ledger transaction ({@code /knk currency reverse}, currency-payments
 * IMPLEMENTATION_PLAN.md Phase 4): the REVERSAL posting the server made (or, with
 * {@link #replayed()}, had already made for this request), what it changed per player and their
 * balances now. {@link #partial()}: a player had spent some of it, so less than the original came
 * back (only when partial reversal was asked for).
 */
public record ReversalOutcome(
    String reversedPublicId,
    String reversalPublicId,
    boolean replayed,
    boolean partial,
    List<Leg> legs,
    Map<Integer, Balances> balances
) {
    public ReversalOutcome {
        legs = legs == null ? List.of() : List.copyOf(legs);
        balances = balances == null ? Map.of() : Map.copyOf(balances);
    }

    /** One player's change: signed amount and the balance after it. */
    public record Leg(int userId, net.knightsandkings.knk.core.domain.users.BalanceCurrency currency, long amount, long balanceAfter) {
    }
}
