package net.knightsandkings.knk.core.statistics;

import java.util.Locale;
import java.util.Optional;

/**
 * The plugin-recorded player statistics (KNG-34, knk-workspace docs/specs/player-statistics
 * DESIGN.md §F.1 / IMPLEMENTATION_PLAN.md §5.2). The keys are knk-web-api's
 * {@code StatisticsCatalog} keys; {@link #input()} says which batch list may carry the metric and
 * {@link #maxPerEntry()} is the catalogue's {@code MaxPerEntry} (a larger accumulation is split
 * over several entries). Metrics the API derives or projects itself ({@code logins}, wins, economy,
 * XP, discoveries) are not listed: the plugin never sends them.
 */
public enum StatisticsMetric {
    ACTIVE_PLAYTIME("active_playtime", Input.DURATION, false, 86_400),
    AFK_TIME("afk_time", Input.DURATION, false, 86_400),

    PVP_KILLS("pvp_kills", Input.PVP_KILL, true, 10_000),
    PVE_KILLS("pve_kills", Input.COUNTER, true, 10_000),
    DEATHS("deaths", Input.COUNTER, true, 10_000),
    DEATHS_BY_CAUSE_PLAYER("deaths_by_cause.player", Input.COUNTER, true, 10_000),
    DEATHS_BY_CAUSE_MOB("deaths_by_cause.mob", Input.COUNTER, true, 10_000),
    DEATHS_BY_CAUSE_ENVIRONMENT("deaths_by_cause.environment", Input.COUNTER, true, 10_000),
    DAMAGE_DEALT_PLAYER("damage_dealt.player", Input.COUNTER, true, 100_000),
    DAMAGE_DEALT_MOB("damage_dealt.mob", Input.COUNTER, true, 100_000),
    DAMAGE_RECEIVED_PLAYER("damage_received.player", Input.COUNTER, true, 100_000),
    DAMAGE_RECEIVED_MOB("damage_received.mob", Input.COUNTER, true, 100_000),
    ARROWS_FIRED("arrows_fired", Input.COUNTER, true, 10_000),
    HEADSHOTS("headshots", Input.COUNTER, true, 10_000),
    HIGHEST_KILLSTREAK("highest_killstreak", Input.RECORD, true, 10_000),
    GATE_DAMAGE("gate_damage", Input.COUNTER, true, 100_000),

    DISTANCE_FOOT("distance.foot", Input.COUNTER, false, 100_000),
    DISTANCE_FLYING("distance.flying", Input.COUNTER, false, 100_000),
    DISTANCE_VEHICLE("distance.vehicle", Input.COUNTER, false, 100_000),
    DISTANCE_SWIM("distance.swim", Input.COUNTER, false, 100_000),
    HIGHEST_FALL("highest_fall", Input.RECORD, false, 10_000);

    /** The batch list a metric goes in (the API's {@code StatisticPluginInput}). */
    public enum Input {
        /** {@code durations}: {@code [from, to)} intervals of a session. */
        DURATION,
        /** {@code counters}: summed values, each entry &gt; 0. */
        COUNTER,
        /** {@code records}: the highest value, each entry &ge; 0. */
        RECORD,
        /** {@code pvpKills}: killer/victim pairs. */
        PVP_KILL
    }

    private final String key;
    private final Input input;
    private final boolean contextual;
    private final double maxPerEntry;

    StatisticsMetric(String key, Input input, boolean contextual, double maxPerEntry) {
        this.key = key;
        this.input = input;
        this.contextual = contextual;
        this.maxPerEntry = maxPerEntry;
    }

    /** The API catalogue key, e.g. {@code distance.foot}. */
    public String key() {
        return key;
    }

    public Input input() {
        return input;
    }

    /** Stored per game context; a non-contextual metric is sent with context {@code ""}. */
    public boolean contextual() {
        return contextual;
    }

    public double maxPerEntry() {
        return maxPerEntry;
    }

    public static Optional<StatisticsMetric> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (StatisticsMetric metric : values()) {
            if (metric.key.equals(wanted)) {
                return Optional.of(metric);
            }
        }
        return Optional.empty();
    }
}
