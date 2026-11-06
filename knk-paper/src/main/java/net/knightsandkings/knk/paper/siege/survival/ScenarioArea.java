package net.knightsandkings.knk.paper.siege.survival;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The scenario area a ghost is confined to (DESIGN S60): the town's WorldGuard region plus the scenario's district
 * regions. {@link #EVERYWHERE} (no WorldGuard, no region ids) confines nobody.
 */
@FunctionalInterface
public interface ScenarioArea {

    ScenarioArea EVERYWHERE = location -> true;

    boolean contains(Location location);

    /** The area from the scenario's region ids, or {@link #EVERYWHERE} when none can be resolved. */
    static ScenarioArea of(KnkSiegeScenario scenario, WorldGuardIntegration worldGuard) {
        if (scenario == null || worldGuard == null) return EVERYWHERE;
        List<String> ids = new ArrayList<>();
        if (scenario.townWgRegionId() != null && !scenario.townWgRegionId().isBlank()) ids.add(scenario.townWgRegionId());
        for (KnkSiegeDistrict district : scenario.districts()) {
            if (district.wgRegionId() != null && !district.wgRegionId().isBlank()) ids.add(district.wgRegionId());
        }
        if (ids.isEmpty()) return EVERYWHERE;
        return ofRegions(ids, world -> worldGuard.regionManager(world));
    }

    /** The area made of the named regions in the location's world; a world without them confines nobody. */
    static ScenarioArea ofRegions(List<String> regionIds, Function<org.bukkit.World, RegionManager> managers) {
        List<String> ids = List.copyOf(regionIds);
        return location -> {
            if (location == null || location.getWorld() == null) return true;
            RegionManager manager = managers.apply(location.getWorld());
            if (manager == null) return true;
            BlockVector3 at = BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            boolean any = false;
            for (String id : ids) {
                ProtectedRegion region = manager.getRegion(id);
                if (region == null) continue;
                any = true;
                if (region.contains(at)) return true;
            }
            return !any; // none of the regions exists in this world: nothing to confine to
        };
    }
}
