package net.knightsandkings.knk.core.domain.siege;

/**
 * A scenario's reward amounts (DESIGN §3.3, §7.6). Rewards are computed and granted server-side
 * (Phase 6); the plugin keeps these for display only.
 */
public record KnkSiegeRewards(
        int coinWin,
        int expWin,
        int gemWin,
        int coinHolding,
        int expHolding,
        int coinCapture,
        int expCapture
) {
    public static final KnkSiegeRewards NONE = new KnkSiegeRewards(0, 0, 0, 0, 0, 0, 0);
}
