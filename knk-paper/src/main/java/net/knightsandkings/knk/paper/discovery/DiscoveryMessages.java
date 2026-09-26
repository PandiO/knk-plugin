package net.knightsandkings.knk.paper.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.paper.chat.RewardMessageFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The chat side of a discovery (docs/specs/domain-discovery DESIGN.md §3.6): v1's colours for the
 * "You discovered …" line (AQUA text, GREEN names, {@code v1:Towns/TownEvents.java:120}), then the
 * rewards in KNG-16's {@link RewardMessageFormat} shape - base, each multiplier and why, total:
 * <pre>
 *   You discovered the district Market in Rivia!
 *     Reward: 650 ×1.2 Royal = +780 coins
 *     Reward: +12 XP
 * </pre>
 * Currencies that paid nothing are left out. Free of Bukkit types so it is unit testable.
 */
public final class DiscoveryMessages {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private DiscoveryMessages() {}

    /** "the town", "the district", "the structure", "the gate"; "the place" for anything else. */
    public static String typePhrase(String domainType) {
        String type = domainType == null ? "" : domainType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "town" -> "the town";
            case "district" -> "the district";
            case "structure" -> "the structure";
            case "gatestructure" -> "the gate";
            default -> "the place";
        };
    }

    /** The "You discovered …" line from the configured template ({type}, {name}, {parent}). */
    public static Component discovered(String template, DiscoveryGrant grant) {
        String parent = grant.parentName() == null || grant.parentName().isBlank() ? "" : " in &a" + grant.parentName();
        String text = template
            .replace("{type}", typePhrase(grant.domainType()))
            .replace("{name}", grant.name() == null ? "?" : grant.name())
            .replace("{parent}", parent);
        return AMPERSAND.deserialize(text);
    }

    /** One place: its line, then its reward lines. */
    public static List<Component> grantLines(String template, DiscoveryGrant grant, DiscoveryGrantResult result) {
        List<Component> lines = new ArrayList<>();
        lines.add(discovered(template, grant));
        lines.addAll(RewardMessageFormat.discovery(grant, result));
        return lines;
    }

    /** After a replay: one line with the number of places ({count}), then the totals. */
    public static List<Component> replaySummary(String template, DiscoveryGrantResult result) {
        List<Component> lines = new ArrayList<>();
        lines.add(AMPERSAND.deserialize(template.replace("{count}", String.valueOf(result.granted().size()))));
        lines.addAll(RewardMessageFormat.discoveryTotals(result));
        return lines;
    }
}
