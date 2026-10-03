package net.knightsandkings.knk.core.statistics;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The game context a statistic happened in (DESIGN.md §F.1): {@code open_world} by default,
 * {@code siege} inside a running Siege match. Future contexts ({@code arena}, {@code dungeon}, …)
 * are new keys, no code change in the buffer. Non-contextual metrics use {@link #NONE} ({@code ""}).
 * Keys follow the API's pattern {@code ^[a-z][a-z0-9_]{0,31}$}.
 */
public record StatisticsContext(String key) {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    public static final StatisticsContext NONE = new StatisticsContext("");
    public static final StatisticsContext OPEN_WORLD = new StatisticsContext("open_world");
    public static final StatisticsContext SIEGE = new StatisticsContext("siege");

    public StatisticsContext {
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty() && !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid statistics context key '" + key + "'");
        }
    }

    public static StatisticsContext of(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "" -> NONE;
            case "open_world" -> OPEN_WORLD;
            case "siege" -> SIEGE;
            default -> new StatisticsContext(normalized);
        };
    }

    /** The context a fact of {@code metric} is stored under: {@link #NONE} for a non-contextual metric. */
    public StatisticsContext forMetric(StatisticsMetric metric) {
        Objects.requireNonNull(metric, "metric");
        return metric.contextual() ? (isNone() ? OPEN_WORLD : this) : NONE;
    }

    public boolean isNone() {
        return key.isEmpty();
    }

    @Override
    public String toString() {
        return key;
    }
}
