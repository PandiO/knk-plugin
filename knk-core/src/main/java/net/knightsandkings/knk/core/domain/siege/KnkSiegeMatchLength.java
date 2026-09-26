package net.knightsandkings.knk.core.domain.siege;

/**
 * A scenario's match-length rule (DESIGN §3.3, §6.5):
 * {@code clamp(members × perPlayerSeconds, minSeconds, maxSeconds)}; see
 * {@code core.siege.MatchDurationCalculator}.
 */
public record KnkSiegeMatchLength(int minSeconds, int perPlayerSeconds, int maxSeconds) {
    /** The scenario defaults (300 s, 75 s per player, 1800 s). */
    public static final KnkSiegeMatchLength DEFAULT = new KnkSiegeMatchLength(300, 75, 1800);
}
