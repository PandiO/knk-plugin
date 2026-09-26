package net.knightsandkings.knk.paper.regions;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.paper.regions.WorldGuardCombatSafezones.RegionsAt;

/**
 * The WorldGuard regions at a spot and WorldGuard's own resolution of the {@code pvp} flag there
 * (priority and parent inheritance, as {@code ApplicableRegionSet.queryValue} does it) - KNG-11.
 */
class WorldGuardRegionLookup implements WorldGuardCombatSafezones.RegionLookup {

    @Override
    public RegionsAt at(Location location) {
        ApplicableRegionSet set = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .createQuery()
                .getApplicableRegions(BukkitAdapter.adapt(location));
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
