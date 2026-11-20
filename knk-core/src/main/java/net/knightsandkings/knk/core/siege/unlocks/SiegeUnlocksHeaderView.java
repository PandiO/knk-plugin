package net.knightsandkings.knk.core.siege.unlocks;

import java.util.Objects;

/** Root {@code siegeUnlocks} of the Siege unlocks menu (PandiO/knk-workspace#869): coins, title, the match line. */
public final class SiegeUnlocksHeaderView {

    private final Long coins;
    private final String titleName;
    private final boolean inMatch;

    /** @param coins null while the viewer's overview isn't loaded */
    public SiegeUnlocksHeaderView(Long coins, String titleName, boolean inMatch) {
        this.coins = coins;
        this.titleName = titleName;
        this.inMatch = inMatch;
    }

    public String getCoinsLine() {
        return coins == null ? "&7Coins: &8loading..." : "&7Coins: &6" + coins;
    }

    public String getTitleLine() {
        return "&7Your title: &f" + (titleName == null || titleName.isBlank() ? "-" : titleName);
    }

    /** The A28 line while the viewer plays in a match, else null (the lore line is dropped, E8). */
    public String getMatchLine() {
        return inMatch ? "&c" + SiegeUnlockText.NOT_DURING_A_MATCH : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SiegeUnlocksHeaderView v && Objects.equals(v.coins, coins)
                && Objects.equals(v.titleName, titleName) && v.inMatch == inMatch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(coins, titleName, inMatch);
    }
}
