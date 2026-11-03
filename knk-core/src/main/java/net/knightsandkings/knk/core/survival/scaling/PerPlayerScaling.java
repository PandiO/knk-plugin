package net.knightsandkings.knk.core.survival.scaling;

/**
 * Per-player scaling (S55): {@code × (1 + p·(players − 1))}, players counted at round start, ghosts included.
 * Always applied to the count; to the stats only when {@code applyToStats} is on.
 *
 * @param factor       p, 0 = off
 * @param applyToStats whether the stats scale with the player count too (default off)
 */
public record PerPlayerScaling(double factor, boolean applyToStats) {

    public static final PerPlayerScaling OFF = new PerPlayerScaling(0, false);

    public PerPlayerScaling {
        if (!Double.isFinite(factor) || factor < 0) throw new IllegalArgumentException("factor must be >= 0 and finite");
    }

    /** @param playersAtRoundStart every player in the match at round start, ghosts included (at least 1) */
    public double multiplier(int playersAtRoundStart) {
        if (playersAtRoundStart < 1) throw new IllegalArgumentException("players must be >= 1: " + playersAtRoundStart);
        return 1 + factor * (playersAtRoundStart - 1);
    }
}
