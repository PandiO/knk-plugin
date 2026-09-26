package net.knightsandkings.knk.core.domain.currency;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/** A player's balances as the API holds them right now (currency ledger, KNG-21 Phase 3). */
public record Balances(int userId, long coins, long gems, long experiencePoints) {

    public long of(BalanceCurrency currency) {
        return switch (currency) {
            case COINS -> coins;
            case GEMS -> gems;
            case EXPERIENCE -> experiencePoints;
        };
    }
}
