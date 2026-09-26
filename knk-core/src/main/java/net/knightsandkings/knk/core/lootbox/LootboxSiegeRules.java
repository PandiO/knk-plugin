package net.knightsandkings.knk.core.lootbox;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where lootboxes meet the siege minigame (Bukkit-free):
 * <ul>
 * <li>A player in an active siege (hub or match) can't claim a world box or open a token item. Their inventory is
 * the siege one then and is replaced by the saved one afterwards, so the item would be lost. The listeners refuse
 * before any API call.</li>
 * <li>No box spawns inside the area of a siege being fought: the scenario's district regions, or its town's region
 * when it names no districts (the scenario area of siege DESIGN §3.9/§8.5). {@link #arenaRegionIds} collects them,
 * {@link #insideAny} tests a spot's regions against them.</li>
 * </ul>
 * WorldGuard region ids are case-insensitive, so both sides are compared in lower case.
 */
public final class LootboxSiegeRules {

    private LootboxSiegeRules() {
    }

    /** The region ids of the areas of the given (drawn, active) scenarios, lower case. Nulls are skipped. */
    public static Set<String> arenaRegionIds(Collection<KnkSiegeScenario> scenarios) {
        Set<String> ids = new HashSet<>();
        if (scenarios == null) {
            return ids;
        }
        for (KnkSiegeScenario scenario : scenarios) {
            if (scenario == null) {
                continue;
            }
            boolean anyDistrict = false;
            for (KnkSiegeDistrict district : scenario.districts()) {
                if (district != null && add(ids, district.wgRegionId())) {
                    anyDistrict = true;
                }
            }
            if (!anyDistrict) {
                add(ids, scenario.townWgRegionId());
            }
        }
        return ids;
    }

    /** Whether any of the regions at a spot is one of {@code arenaIds} (as returned by {@link #arenaRegionIds}). */
    public static boolean insideAny(Collection<String> regionsHere, Set<String> arenaIds) {
        if (regionsHere == null || arenaIds == null || arenaIds.isEmpty()) {
            return false;
        }
        for (String id : regionsHere) {
            if (id != null && arenaIds.contains(id.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean add(Set<String> ids, String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return false;
        }
        ids.add(regionId.trim().toLowerCase(Locale.ROOT));
        return true;
    }
}
