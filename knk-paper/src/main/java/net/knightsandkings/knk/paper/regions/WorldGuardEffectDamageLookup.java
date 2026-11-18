package net.knightsandkings.knk.paper.regions;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;

import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * The WorldGuard side of {@link DomainEffectDamageListener}: the regions at a spot (without {@code __global__}) and
 * WorldGuard's resolution of {@code knk-effect-damage} and {@code pvp} there (priority and parent inheritance).
 */
final class WorldGuardEffectDamageLookup implements DomainEffectDamageListener.RegionLookup {
    private final RegionIds regions = new RegionIds();

    @Override
    public DomainEffectDamageListener.RegionsAt at(Location location) {
        ApplicableRegionSet set = regions.applicable(location);
        if (set == null) {
            return DomainEffectDamageListener.RegionsAt.NONE;
        }
        Set<String> regionIds = new HashSet<>();
        for (ProtectedRegion region : set) {
            if (!RegionIds.GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
                regionIds.add(region.getId());
            }
        }
        if (regionIds.isEmpty()) {
            return DomainEffectDamageListener.RegionsAt.NONE;
        }
        StateFlag effectFlag = EffectDamageFlag.flag();
        StateFlag.State effect = effectFlag == null ? null : set.queryValue(null, effectFlag);
        StateFlag.State pvp = set.queryValue(null, Flags.PVP);
        return new DomainEffectDamageListener.RegionsAt(regionIds,
            effect == null ? null : effect == StateFlag.State.ALLOW,
            pvp == null ? null : pvp == StateFlag.State.ALLOW);
    }
}
