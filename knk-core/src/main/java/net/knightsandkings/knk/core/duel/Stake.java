package net.knightsandkings.knk.core.duel;

import java.util.List;

/** One side's stake: coins and item references (DESIGN §4.4). */
public record Stake(long coins, List<StakeItem> items) {
    public static final Stake NONE = new Stake(0, List.of());

    public Stake {
        items = List.copyOf(items);
    }

    public static Stake coins(long coins) {
        return new Stake(coins, List.of());
    }

    public boolean isEmpty() {
        return coins == 0 && items.isEmpty();
    }
}
