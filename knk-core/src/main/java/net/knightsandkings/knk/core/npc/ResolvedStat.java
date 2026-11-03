package net.knightsandkings.knk.core.npc;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of resolving one stat: the base that went in, the combined value before bounds, the
 * final value, and every bound that acted.
 *
 * @param stat      the stat
 * @param base      the base value (profile value, or the live engine value the adapter supplied)
 * @param unclamped {@code (base + Σ additions) × Π multipliers}, rounded for tick stats, before bounds
 * @param value     the value after bounds
 * @param reports   bounds that acted, in the order they were checked; empty when nothing was clamped
 */
public record ResolvedStat(NpcStat stat, double base, double unclamped, double value, List<ClampReport> reports) {

    public ResolvedStat {
        Objects.requireNonNull(stat, "stat");
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
    }

    /** The value as whole ticks (tick stats are already integral after resolution). */
    public int ticks() {
        return (int) Math.round(value);
    }

    public boolean wasClamped() {
        return reports.stream().anyMatch(ClampReport::applied);
    }

    public boolean hasAdvisory() {
        return reports.stream().anyMatch(r -> !r.applied());
    }
}
