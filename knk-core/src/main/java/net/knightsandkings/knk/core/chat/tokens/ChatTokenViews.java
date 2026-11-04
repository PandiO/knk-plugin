package net.knightsandkings.knk.core.chat.tokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.statistics.StatisticsLines;

/**
 * What a statistics token shows (PandiO/knk-workspace#233): the chat label and its hover lines ({@code &} colour
 * codes), built from the player's statistics as any signed-in player sees them - the same data {@code /stats <player>}
 * shows another player, so nothing private reaches chat. A metric that view does not contain is
 * {@link Private}: the plugin then asks the sender whether to make it public (their own token) or shows it as private
 * (someone else's). Pure.
 */
public final class ChatTokenViews {

    /** The statistics visibility setting of {@code discoveries} (knk-web-api {@code SettingDiscoveryCounts}). */
    public static final String DISCOVERIES_SETTING = "discoveries.counts";

    public sealed interface View permits Shown, Private {
    }

    /** {@code label} includes its brackets. */
    public record Shown(String label, List<String> hover) implements View {
        public Shown {
            hover = List.copyOf(hover);
        }
    }

    /** The visibility settings that keep the value out of the public view (setting keys, for "make public"). */
    public record Private(List<String> settingKeys) implements View {
        public Private {
            settingKeys = List.copyOf(settingKeys);
        }
    }

    private static final String BRACKET = "&e";
    private static final String VALUE = "&f";
    private static final String HEADER = "&6";

    private ChatTokenViews() {
    }

    /**
     * @param stats   the public view ({@code audience=public}); never null
     * @param ofOther true for {@code #kills@Steve}: the label names the player
     */
    public static View view(ChatToken token, PlayerStatistics stats, boolean ofOther) {
        String name = stats.username() == null ? "?" : stats.username();
        PlayerStatistics.Profile profile = stats.profile();
        return switch (token) {
            case STATS -> new Shown(BRACKET + "[" + VALUE + possessive(name) + " stats" + BRACKET + "]", overview(stats, name));
            case KILLS -> single(stats, "pvp_kills", name, ofOther, "kill", "kills", List.of("pve_kills"));
            case DEATHS -> single(stats, "deaths", name, ofOther, "death", "deaths", List.of());
            case STREAK -> metricValue(stats, "highest_killstreak")
                .<View>map(v -> new Shown(label(name, ofOther, "best streak " + count(v)),
                    hover(name, "killstreak", List.of(StatisticsLines.metricLine(stats.metric("highest_killstreak").orElseThrow())))))
                .orElse(new Private(List.of("highest_killstreak")));
            case WINS -> single(stats, "wins", name, ofOther, "win", "wins", List.of("losses", "draws"));
            case KD -> kd(stats, name, ofOther);
            case PLAYTIME -> new Shown(label(name, ofOther, StatisticsLines.duration(profile == null ? 0 : profile.activePlaytimeSeconds()) + " played"),
                hover(name, "playtime", StatisticsLines.activityLines(profile)));
            case BALANCE -> new Shown(label(name, ofOther, count(profile == null ? 0 : profile.coins()) + " coins"),
                hover(name, "balance", List.of("&7Coins: &6" + count(profile == null ? 0 : profile.coins()),
                    "&7Gems: &b" + count(profile == null ? 0 : profile.gems()))));
            case TITLE -> {
                String title = profile == null || profile.titleName() == null || profile.titleName().isBlank() ? null : profile.titleName();
                yield new Shown(label(name, ofOther, title == null ? "no title" : title),
                    hover(name, "title", List.of("&7Title: &f" + (title == null ? "-" : title))));
            }
            case XP -> new Shown(label(name, ofOther, count(profile == null ? 0 : profile.experience()) + " XP"),
                hover(name, "experience", List.of("&7Experience: &f" + count(profile == null ? 0 : profile.experience()))));
            case DISCOVERIES -> {
                PlayerStatistics.Discoveries d = stats.discoveries();
                if (d == null) {
                    yield new Private(List.of(DISCOVERIES_SETTING));
                }
                yield new Shown(label(name, ofOther, plural(d.total(), "discovery", "discoveries")),
                    hover(name, "discoveries", List.of("&7Towns: &f" + d.towns(), "&7Districts: &f" + d.districts(),
                        "&7Structures: &f" + d.structures())));
            }
            case HAND, LOCATION, TOKENS -> throw new IllegalArgumentException(token + " is not a statistics token");
        };
    }

    /** The visible total of {@code key}; empty when the public view hides it (no metric, or a hidden total). */
    static Optional<Double> metricValue(PlayerStatistics stats, String key) {
        return stats.metric(key).map(PlayerStatistics.Metric::value);
    }

    private static View single(PlayerStatistics stats, String key, String name, boolean ofOther, String one, String many,
                               List<String> extraKeys) {
        Optional<Double> value = metricValue(stats, key);
        if (value.isEmpty()) {
            return new Private(List.of(key));
        }
        List<String> lines = new ArrayList<>();
        lines.add(StatisticsLines.metricLine(stats.metric(key).orElseThrow()));
        for (String extra : extraKeys) {
            stats.metric(extra).ifPresent(m -> lines.add(StatisticsLines.metricLine(m)));
        }
        return new Shown(label(name, ofOther, plural(Math.round(value.get()), one, many)), hover(name, many, lines));
    }

    private static View kd(PlayerStatistics stats, String name, boolean ofOther) {
        Optional<Double> kills = metricValue(stats, "pvp_kills");
        Optional<Double> deaths = metricValue(stats, "deaths");
        List<String> hidden = new ArrayList<>();
        if (kills.isEmpty()) {
            hidden.add("pvp_kills");
        }
        if (deaths.isEmpty()) {
            hidden.add("deaths");
        }
        if (!hidden.isEmpty()) {
            return new Private(hidden);
        }
        String ratio = ratio(kills.get(), deaths.get());
        return new Shown(label(name, ofOther, "K/D " + ratio), hover(name, "K/D", List.of(
            "&7K/D: &f" + ratio + " &8(player kills per death)",
            StatisticsLines.metricLine(stats.metric("pvp_kills").orElseThrow()),
            StatisticsLines.metricLine(stats.metric("deaths").orElseThrow()))));
    }

    /** Kills per death with two decimals; no deaths = the kill count itself. */
    public static String ratio(double kills, double deaths) {
        return String.format(Locale.ENGLISH, "%.2f", deaths <= 0 ? kills : kills / deaths);
    }

    /** The {@code #stats} hover: the public profile and the headline combat/minigame values the view contains. */
    static List<String> overview(PlayerStatistics stats, String name) {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER + possessive(name) + " statistics");
        PlayerStatistics.Profile profile = stats.profile();
        if (profile != null) {
            if (profile.titleName() != null && !profile.titleName().isBlank()) {
                lines.add("&7Title: &f" + profile.titleName());
            }
            lines.add("&7Experience: &f" + count(profile.experience()));
            lines.add("&7Coins: &6" + count(profile.coins()) + " &8| &7Gems: &b" + count(profile.gems()));
            lines.add("&7Active playtime: &f" + StatisticsLines.duration(profile.activePlaytimeSeconds()));
        }
        for (String key : List.of("pvp_kills", "deaths")) {
            stats.metric(key).filter(m -> m.value() != null)
                .ifPresent(m -> lines.add("&7" + StatisticsLines.label(key) + ": &f" + StatisticsLines.format(key, m.unit(), m.value())));
        }
        Optional<Double> kills = metricValue(stats, "pvp_kills");
        Optional<Double> deaths = metricValue(stats, "deaths");
        if (kills.isPresent() && deaths.isPresent()) {
            lines.add("&7K/D: &f" + ratio(kills.get(), deaths.get()));
        }
        for (String key : List.of("highest_killstreak", "wins")) {
            stats.metric(key).filter(m -> m.value() != null)
                .ifPresent(m -> lines.add("&7" + StatisticsLines.label(key) + ": &f" + StatisticsLines.format(key, m.unit(), m.value())));
        }
        if (stats.discoveries() != null) {
            lines.add("&7Discoveries: &f" + stats.discoveries().total());
        }
        lines.add("&8/stats " + name + " for more");
        return lines;
    }

    private static String label(String name, boolean ofOther, String text) {
        return BRACKET + "[" + VALUE + (ofOther ? name + ": " : "") + text + BRACKET + "]";
    }

    private static List<String> hover(String name, String what, List<String> lines) {
        List<String> all = new ArrayList<>();
        all.add(HEADER + possessive(name) + " " + what);
        all.addAll(lines);
        return all;
    }

    static String possessive(String name) {
        return name.endsWith("s") || name.endsWith("S") ? name + "'" : name + "'s";
    }

    private static String plural(long n, String one, String many) {
        return count(n) + " " + (n == 1 ? one : many);
    }

    private static String count(double n) {
        return String.format(Locale.ENGLISH, "%,d", Math.round(n));
    }
}
