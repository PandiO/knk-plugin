package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code discoveries} root (domain-discovery DESIGN.md §3.7): the viewer's discovery summary
 * ({@code GET …/discoveries/summary}) for the {@code discoveries.main} header and the hub tile.
 * Built from the last summary {@link DiscoveriesMenuFeature} read; "not loaded" until one arrives.
 */
public final class DiscoveriesView {

    private final DiscoverySummary summary;

    DiscoveriesView(DiscoverySummary summary) {
        this.summary = summary;
    }

    static DiscoveriesView unavailable() {
        return new DiscoveriesView(null);
    }

    public boolean isLoaded() {
        return summary != null;
    }

    /** "&7Towns: &f3&7/&f5" per type that has places (Town, District, Structure, Gate order). */
    public List<String> getSummaryLines() {
        List<String> lines = new ArrayList<>();
        if (summary == null) {
            lines.add("&7Loading your discoveries...");
            return lines;
        }
        for (DiscoveryTypeCount count : summary.byType()) {
            if (count.total() > 0) {
                lines.add("&7" + plural(count.domainType()) + ": &f" + count.discovered() + "&7/&f" + count.total());
            }
        }
        if (lines.isEmpty()) {
            lines.add("&7Nothing to discover yet");
        }
        return lines;
    }

    /** The latest discovered place's name; "nothing yet" before the first, "…" while loading. */
    public String getLatestName() {
        if (summary == null) {
            return "...";
        }
        return summary.latest() != null && summary.latest().name() != null ? summary.latest().name() : "nothing yet";
    }

    /** "&7Earned: &6+1200 coins &b+10 gems &d+300 XP" over every discovery; null (line dropped) when nothing. */
    public String getRewardsLine() {
        if (summary == null) {
            return null;
        }
        String rewards = DiscoveryRow.rewards(summary.totalCoins(), summary.totalGems(), summary.totalExp());
        return rewards == null ? null : "&7Earned: " + rewards;
    }

    /** "&7Discovered &f22 &7of &f165 &7places" (hub tile); null (line dropped) while unknown. */
    public String getCountLine() {
        if (summary == null) {
            return null;
        }
        int discovered = 0;
        int total = 0;
        for (DiscoveryTypeCount count : summary.byType()) {
            discovered += count.discovered();
            total += count.total();
        }
        return "&7Discovered &f" + discovered + " &7of &f" + total + " &7places";
    }

    private static String plural(String domainType) {
        String type = domainType == null ? "" : domainType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "town" -> "Towns";
            case "district" -> "Districts";
            case "structure" -> "Structures";
            case "gatestructure" -> "Gates";
            default -> domainType;
        };
    }
}
