package net.knightsandkings.knk.core.lootbox;

import java.util.List;

/**
 * A spawn area (docs/specs/lootboxes/DESIGN.md §3.2): a WorldGuard region boxes may appear in, with its limits.
 * Mirrors knk-web-api's {@code LootboxRuntimeAreaDto}; the runtime config lists disabled areas too.
 *
 * @param excludedRegionIds regions inside the area where no box may spawn (plots, structures)
 * @param allowedTypeIds    empty = every enabled type
 * @param activeCount       active boxes when the config was read (the API's count)
 */
public record KnkLootboxArea(
        int id,
        String name,
        String world,
        String wgRegionId,
        boolean enabled,
        int maxActive,
        int spawnIntervalSeconds,
        double spawnChancePercent,
        int minOnlinePlayers,
        int minDistanceFromPlayers,
        int lifetimeMinutes,
        List<String> excludedRegionIds,
        List<Integer> allowedTypeIds,
        int activeCount,
        Integer createdByUserId
) {
    public KnkLootboxArea {
        excludedRegionIds = excludedRegionIds == null ? List.of() : List.copyOf(excludedRegionIds);
        allowedTypeIds = allowedTypeIds == null ? List.of() : List.copyOf(allowedTypeIds);
    }
}
