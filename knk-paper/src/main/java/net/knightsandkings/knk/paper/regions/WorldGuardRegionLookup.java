package net.knightsandkings.knk.paper.regions;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;

import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.paper.regions.WorldGuardCombatSafezones.RegionsAt;

/**
 * The WorldGuard regions at a spot and WorldGuard's own resolution of the {@code pvp} flag there
 * (priority and parent inheritance, as {@code ApplicableRegionSet.queryValue} does it) - KNG-11.
 * The region set comes from the shared {@link RegionIds} query (R8).
 */
class WorldGuardRegionLookup implements WorldGuardCombatSafezones.RegionLookup {
    private final RegionIds regions = new RegionIds();

    @Override
    public RegionsAt at(Location location) {
        ApplicableRegionSet set = regions.applicable(location);
        if (set == null) {
            return RegionsAt.NONE;
        }
        Set<String> regionIds = new HashSet<>();
        for (ProtectedRegion region : set) {
            regionIds.add(region.getId());
        }
        if (regionIds.isEmpty()) {
            return RegionsAt.NONE;
        }
        StateFlag.State pvp = set.queryValue(null, Flags.PVP);
        return new RegionsAt(regionIds, pvp == null ? null : pvp == StateFlag.State.ALLOW);
    }
}
