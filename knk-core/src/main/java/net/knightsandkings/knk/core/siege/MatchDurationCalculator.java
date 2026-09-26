package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;

/**
 * Match length (DESIGN §6.5): {@code clamp(members × perPlayerSeconds, minSeconds, maxSeconds)},
 * using the member count at match start. The maximum always wins if a scenario ever had
 * min &gt; max (readiness forbids it).
 * <p>
 * Not identical to v2: v2 rounded up to whole minutes ({@code ceil(members × 1.25)} minutes,
 * floor 5 minutes, no cap), so e.g. 5 players got 420 s in v2 and get 375 s here with the scenario
 * defaults (75 s per player). See the Phase 4 status in the implementation plan.
 */
public final class MatchDurationCalculator {
    private MatchDurationCalculator() {}

    public static int seconds(KnkSiegeMatchLength rule, int members) {
        long raw = (long) Math.max(0, members) * Math.max(0, rule.perPlayerSeconds());
        long clamped = Math.min(Math.max(raw, rule.minSeconds()), rule.maxSeconds());
        return (int) Math.max(0, clamped);
    }
}
