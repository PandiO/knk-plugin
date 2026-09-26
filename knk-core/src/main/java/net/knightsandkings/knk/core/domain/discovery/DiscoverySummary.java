package net.knightsandkings.knk.core.domain.discovery;

import java.util.List;

/**
 * A player's discovery totals (knk-web-api's DiscoverySummaryDto): per type top-down, the latest
 * discovery (null when none), and the lifetime amounts discoveries credited.
 */
public record DiscoverySummary(
    List<DiscoveryTypeCount> byType,
    DiscoveryProgressRow latest,
    int totalDiscovered,
    int totalCoins,
    int totalGems,
    int totalExp
) {
    public DiscoverySummary {
        byType = byType == null ? List.of() : List.copyOf(byType);
    }
}
