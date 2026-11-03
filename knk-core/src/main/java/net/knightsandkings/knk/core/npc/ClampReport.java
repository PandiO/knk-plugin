package net.knightsandkings.knk.core.npc;

import java.util.Objects;

/**
 * One bound that acted during resolution. Clamping is reported, not silent (§5b): the adapter can
 * log or count it once per modifier change instead of the pipeline throwing or hiding it.
 *
 * @param stat    the stat concerned
 * @param from    the value before the bound (after rounding for tick stats)
 * @param to      the value after the bound; equals {@code from} for an advisory
 * @param bound   the rule that acted, with its limit
 * @param applied {@code true} when the value was changed (floor, ceiling, cross-stat rule);
 *                {@code false} for an advisory such as the speed comfort limit
 */
public record ClampReport(NpcStat stat, double from, double to, Bound bound, boolean applied) {

    public ClampReport {
        Objects.requireNonNull(stat, "stat");
        Objects.requireNonNull(bound, "bound");
    }

    public static ClampReport clamped(NpcStat stat, double from, Bound bound) {
        return new ClampReport(stat, from, bound.limit(), bound, true);
    }

    public static ClampReport advisory(NpcStat stat, double value, Bound bound) {
        return new ClampReport(stat, value, value, bound, false);
    }

    /** One-line summary for a log line. */
    public String describe() {
        return applied
            ? stat.id() + " clamped " + from + " -> " + to + " by " + bound.name() + " (" + bound.limit() + ")"
            : stat.id() + " = " + from + " above " + bound.name() + " (" + bound.limit() + ")";
    }
}
