package net.knightsandkings.knk.core.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;

/**
 * Text lines (with {@code &} colour codes) for a player's statistics as the API returned them to one
 * viewer (KNG-34, DESIGN.md §F.1, §F.10): used by {@code /stats [player]} and the
 * {@code statistics.main} menu. Renders only what the response contains - visibility is decided by
 * the API. Labels and the group of each metric mirror the API catalogue ({@code StatisticsCatalog.cs});
 * an unknown future metric is listed under "Other" with its key. Pure.
 */
public final class StatisticsLines {

    public static final String ACTIVITY = "activity";
    public static final String COMBAT = "combat";
    public static final String MINIGAMES = "minigames";
    public static final String EXPLORATION = "exploration";
    public static final String PROGRESSION = "progression";
    public static final String OTHER = "other";

    /** Display order of the groups (the API catalogue's menu groups). */
    public static final List<String> GROUPS = List.of(ACTIVITY, COMBAT, MINIGAMES, EXPLORATION, PROGRESSION);

    /** The API's period names, in the menu's cycle order. */
    public static final List<String> PERIODS = List.of("lifetime", "day", "week", "month");

    private record Known(String group, String label) {
    }

    private static final Map<String, Known> METRICS = new LinkedHashMap<>();

    static {
        known(ACTIVITY, "active_playtime", "Active playtime");
        known(ACTIVITY, "afk_time", "AFK time");
        known(ACTIVITY, "logins", "Logins");
        known(COMBAT, "pvp_kills", "Player kills");
        known(COMBAT, "pve_kills", "Creature kills");
        known(COMBAT, "deaths", "Deaths");
        known(COMBAT, "damage_dealt.player", "Damage dealt to players");
        known(COMBAT, "damage_dealt.mob", "Damage dealt to creatures");
        known(COMBAT, "damage_received.player", "Damage received from players");
        known(COMBAT, "damage_received.mob", "Damage received from creatures");
        known(COMBAT, "arrows_fired", "Arrows fired");
        known(COMBAT, "headshots", "Headshots");
        known(COMBAT, "highest_killstreak", "Highest killstreak");
        known(MINIGAMES, "wins", "Wins");
        known(MINIGAMES, "losses", "Losses");
        known(MINIGAMES, "draws", "Draws");
        known(MINIGAMES, "objectives_captured", "Objectives captured");
        known(MINIGAMES, "gate_damage", "Gate-door damage");
        known(EXPLORATION, "distance.foot", "Distance on foot");
        known(EXPLORATION, "distance.flying", "Distance flying");
        known(EXPLORATION, "distance.vehicle", "Distance in a vehicle");
        known(EXPLORATION, "highest_fall", "Highest survived fall");
        known(PROGRESSION, "xp_gained", "XP gained");
    }

    private static final Set<String> GROUP_SET = Set.copyOf(GROUPS);
    /** Shown to every viewer (DESIGN.md §F.1 "always public"); the rest depends on the player's settings. */
    private static final Set<String> ALWAYS_PUBLIC = Set.of("active_playtime", "afk_time", "xp_gained");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private StatisticsLines() {
    }

    private static void known(String group, String key, String label) {
        METRICS.put(key, new Known(group, label));
    }

    // ------------------------------------------------------------------ labels

    public static String label(String metricKey) {
        Known known = METRICS.get(metricKey);
        return known != null ? known.label() : metricKey;
    }

    /** The menu group of a metric; {@link #OTHER} for keys this plugin version doesn't know. */
    public static String group(String metricKey) {
        Known known = METRICS.get(metricKey);
        return known != null ? known.group() : OTHER;
    }

    public static String groupLabel(String group) {
        if (group == null || group.isEmpty()) {
            return "";
        }
        return group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1);
    }

    /** {@code open_world} → "Open world", {@code siege} → "Siege", others by their words. */
    public static String contextLabel(String context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        if (context.equals("open_world")) {
            return "Open world";
        }
        String words = context.replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    /** "Lifetime", "Day 3 Oct 2026", "Week of 28 Sep 2026", "October 2026". */
    public static String periodLabel(String period, LocalDate periodStart) {
        String name = period == null ? "lifetime" : period.toLowerCase(Locale.ROOT);
        if (periodStart == null || name.equals("lifetime")) {
            return "Lifetime";
        }
        return switch (name) {
            case "day" -> "Day " + DAY.format(periodStart);
            case "week" -> "Week of " + DAY.format(periodStart);
            case "month" -> periodStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + periodStart.getYear();
            default -> groupLabel(name);
        };
    }

    /** The next period of the menu's cycle (lifetime → day → week → month → lifetime). */
    public static String nextPeriod(String period) {
        int index = PERIODS.indexOf(period == null ? "" : period.toLowerCase(Locale.ROOT));
        return PERIODS.get((index + 1) % PERIODS.size());
    }

    // ------------------------------------------------------------------ values

    /**
     * A value in its unit (DESIGN.md §F.10; the API already rounded it): durations as
     * "2d 3h"/"3h 25m"/"12m"/"45s", blocks as whole blocks (the highest fall with one decimal),
     * counts and points as whole numbers with thousands separators.
     */
    public static String format(String metricKey, String unit, double value) {
        String u = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        return switch (u) {
            case "seconds" -> duration(Math.round(value));
            case "blocks" -> "highest_fall".equals(metricKey)
                    ? String.format(Locale.ENGLISH, "%.1f blocks", value)
                    : String.format(Locale.ENGLISH, "%,d blocks", (long) Math.floor(value));
            default -> String.format(Locale.ENGLISH, "%,d", Math.round(value));
        };
    }

    public static String duration(long seconds) {
        long s = Math.max(0, seconds);
        long days = s / 86_400;
        long hours = (s % 86_400) / 3_600;
        long minutes = (s % 3_600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return s + "s";
    }

    // ------------------------------------------------------------------ lines

    /** Title, XP, balances, lifetime active/AFK time and first join (the always-public profile). */
    public static List<String> profileLines(PlayerStatistics.Profile profile) {
        List<String> lines = new ArrayList<>();
        if (profile == null) {
            return lines;
        }
        if (profile.titleName() != null) {
            lines.add("&7Title: &f" + profile.titleName());
        }
        lines.add("&7Experience: &f" + String.format(Locale.ENGLISH, "%,d", profile.experience()));
        lines.addAll(activityLines(profile));
        return lines;
    }

    /** Lifetime active/AFK time and first join - the part of the profile {@code /stats} doesn't show yet. */
    public static List<String> activityLines(PlayerStatistics.Profile profile) {
        List<String> lines = new ArrayList<>();
        if (profile == null) {
            return lines;
        }
        lines.add("&7Active playtime: &f" + duration(profile.activePlaytimeSeconds())
                + " &8| &7AFK: &f" + duration(profile.afkSeconds()));
        if (profile.firstJoinedAt() != null) {
            lines.add("&7First joined: &f" + DAY.format(profile.firstJoinedAt().atZone(ZoneOffset.UTC).toLocalDate()));
        }
        return lines;
    }

    /** One line per visible metric of {@code group} (catalogue order), plus economy/discoveries. */
    public static List<String> groupLines(PlayerStatistics statistics, String group) {
        List<String> lines = new ArrayList<>();
        if (statistics == null) {
            return lines;
        }
        if (OTHER.equals(group)) {
            for (PlayerStatistics.Metric metric : statistics.metrics()) {
                if (!METRICS.containsKey(metric.key())) {
                    lines.add(metricLine(metric));
                }
            }
            return lines;
        }
        for (Map.Entry<String, Known> known : METRICS.entrySet()) {
            if (known.getValue().group().equals(group)) {
                statistics.metric(known.getKey()).ifPresent(metric -> lines.add(metricLine(metric)));
            }
        }
        if (EXPLORATION.equals(group) && statistics.discoveries() != null) {
            PlayerStatistics.Discoveries d = statistics.discoveries();
            lines.add("&7Discoveries: &f" + d.total() + " &8(towns " + d.towns() + ", districts " + d.districts()
                    + ", structures " + d.structures() + ")");
        }
        if (PROGRESSION.equals(group) && statistics.economy() != null) {
            PlayerStatistics.Economy e = statistics.economy();
            lines.add("&7Coins earned/spent: &6" + grouped(e.coinsEarned()) + "&7/&6" + grouped(e.coinsSpent()));
            lines.add("&7Gems earned/spent: &b" + grouped(e.gemsEarned()) + "&7/&b" + grouped(e.gemsSpent()));
        }
        return lines;
    }

    /**
     * "Player kills: 12 (Open world 5, Siege 7)"; a hidden total shows only its visible contexts.
     */
    public static String metricLine(PlayerStatistics.Metric metric) {
        StringBuilder line = new StringBuilder("&7").append(label(metric.key())).append(": ");
        String contexts = metric.contexts().stream()
                .map(c -> contextLabel(c.context()) + " " + format(metric.key(), metric.unit(), c.value()))
                .collect(Collectors.joining(", "));
        if (metric.value() != null) {
            line.append("&f").append(format(metric.key(), metric.unit(), metric.value()));
            if (!contexts.isEmpty()) {
                line.append(" &8(").append(contexts).append(")");
            }
        } else {
            line.append("&f").append(contexts.isEmpty() ? "-" : contexts);
        }
        return line.toString();
    }

    /** {@link #lines(PlayerStatistics, boolean)} with the period and the whole profile. */
    public static List<String> lines(PlayerStatistics statistics) {
        return lines(statistics, true);
    }

    /**
     * The period and profile ({@code withProfile}; else only playtime and first join, for
     * {@code /stats} which prints title and balances itself), then each group with something visible;
     * a hint when the viewer sees no configurable statistics.
     */
    public static List<String> lines(PlayerStatistics statistics, boolean withProfile) {
        List<String> lines = new ArrayList<>();
        if (statistics == null) {
            return lines;
        }
        if (withProfile) {
            lines.add("&7Period: &f" + periodLabel(statistics.period(), statistics.periodStart()));
            lines.addAll(profileLines(statistics.profile()));
        } else {
            lines.addAll(activityLines(statistics.profile()));
        }
        for (String group : concat(GROUPS, OTHER)) {
            List<String> groupLines = groupLines(statistics, group);
            // Activity's always-public playtime lines repeat the profile for lifetime; keep them for periods.
            if (ACTIVITY.equals(group) && "lifetime".equalsIgnoreCase(statistics.period())) {
                groupLines.removeIf(l -> l.startsWith("&7Active playtime:") || l.startsWith("&7AFK time:"));
            }
            if (groupLines.isEmpty()) {
                continue;
            }
            lines.add("&6" + groupLabel(group));
            groupLines.forEach(l -> lines.add(" " + l));
        }
        boolean anyConfigurable = statistics.economy() != null || statistics.discoveries() != null
                || statistics.metrics().stream().anyMatch(m -> !ALWAYS_PUBLIC.contains(m.key()));
        if (!anyConfigurable && !"self".equalsIgnoreCase(statistics.viewer()) && !"staff".equalsIgnoreCase(statistics.viewer())) {
            lines.add("&8" + statistics.username() + " keeps their other statistics private.");
        }
        return lines;
    }

    private static List<String> concat(List<String> groups, String extra) {
        List<String> all = new ArrayList<>(groups);
        all.add(extra);
        return all;
    }

    private static String grouped(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    /** Whether {@code group} is one of the five catalogue groups. */
    public static boolean isGroup(String group) {
        return group != null && GROUP_SET.contains(group);
    }

    /** UTC instant → "3 Oct 2026" (title history, first join). */
    public static String day(Instant instant) {
        return instant == null ? "" : DAY.format(instant.atZone(ZoneOffset.UTC).toLocalDate());
    }
}
