package net.knightsandkings.knk.core.siege.unlocks;

import java.util.List;

/** A player's coins, title and every template's unlock state (PandiO/knk-workspace#869). */
public record SiegeUnlocksOverview(int userId, long coins, String titleName, List<SiegeUnlockOffer> offers) {
    public SiegeUnlocksOverview {
        offers = offers == null ? List.of() : List.copyOf(offers);
    }
}
