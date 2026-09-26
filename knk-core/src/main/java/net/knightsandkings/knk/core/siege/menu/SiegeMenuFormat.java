package net.knightsandkings.knk.core.siege.menu;

import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import net.knightsandkings.knk.core.siege.SiegePhase;

import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/** Siege Phase 8b: shared text helpers for the menu views (inline {@code &}-colours, E7). */
public final class SiegeMenuFormat {
    private SiegeMenuFormat() {}

    private static final Map<String, Character> COLOR_CODES = Map.ofEntries(
            Map.entry("BLACK", '0'), Map.entry("DARK_BLUE", '1'), Map.entry("DARK_GREEN", '2'),
            Map.entry("DARK_AQUA", '3'), Map.entry("DARK_RED", '4'), Map.entry("DARK_PURPLE", '5'),
            Map.entry("GOLD", '6'), Map.entry("GRAY", '7'), Map.entry("DARK_GRAY", '8'),
            Map.entry("BLUE", '9'), Map.entry("GREEN", 'a'), Map.entry("AQUA", 'b'),
            Map.entry("RED", 'c'), Map.entry("LIGHT_PURPLE", 'd'), Map.entry("YELLOW", 'e'),
            Map.entry("WHITE", 'f'));

    /** {@code &}-code for a Bukkit ChatColor name ({@code "RED"} → {@code "&c"}); white when unknown. */
    public static String color(String chatColorName) {
        if (chatColorName == null) return "&f";
        Character code = COLOR_CODES.get(chatColorName.trim().toUpperCase(Locale.ROOT));
        return "&" + (code == null ? 'f' : code);
    }

    /** {@code m:ss}, or {@code h:mm:ss} from an hour on. */
    public static String duration(int seconds) {
        int s = Math.max(0, seconds);
        int hours = s / 3600;
        int minutes = (s % 3600) / 60;
        int secs = s % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
                : String.format(Locale.ROOT, "%d:%02d", minutes, secs);
    }

    public static String phaseLabel(SiegePhase phase) {
        return switch (phase) {
            case DISABLED -> "Disabled";
            case MATCHMAKING -> "Matchmaking";
            case HUB -> "Preparing";
            case IN_PROGRESS -> "In progress";
            case ENDING -> "Ending";
            case COOLDOWN -> "Cooldown";
        };
    }

    /** The E6 BannerPatterns string of a banner design ({@code BASE|pattern:COLOR,…}), or null. */
    public static String bannerPatterns(KnkBannerDesign design) {
        if (design == null) return null;
        StringJoiner layers = new StringJoiner(",");
        for (KnkBannerLayer layer : design.layers()) {
            if (layer.patternKey() == null || layer.color() == null) continue;
            layers.add(layer.patternKey().trim() + ":" + layer.color().trim().toUpperCase(Locale.ROOT));
        }
        String base = design.baseColor() == null ? "" : design.baseColor().trim().toUpperCase(Locale.ROOT);
        return base + "|" + layers;
    }

    /** The E6 BannerPatterns string of a parsed/built spec, or null. */
    public static String bannerPatterns(BannerPatternSpec spec) {
        if (spec == null) return null;
        StringJoiner layers = new StringJoiner(",");
        for (BannerPatternSpec.Layer layer : spec.layers()) {
            layers.add(layer.patternKey() + ":" + layer.color());
        }
        return (spec.baseColor() == null ? "" : spec.baseColor()) + "|" + layers;
    }
}
