package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.query.RegionShape;
import net.knightsandkings.knk.core.regions.query.RegionShapeLookup;

/**
 * PandiO/knk-workspace#988: {@link RegionShapeLookup} on WorldGuard. A named world that is loaded is the only one
 * searched; otherwise every loaded world, the first that has the region. The global region has no shape. Main thread.
 */
public final class WorldGuardShapeLookup implements RegionShapeLookup {

    @Override
    public Optional<RegionShape> find(String regionId, String world) {
        if (regionId == null || regionId.isBlank() || ProtectedRegion.GLOBAL_REGION.equalsIgnoreCase(regionId.trim())) {
            return Optional.empty();
        }
        World named = world == null || world.isBlank() ? null : Bukkit.getWorld(world.trim());
        List<World> worlds = named != null ? List.of(named) : Bukkit.getWorlds();
        for (World w : worlds) {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(w));
            ProtectedRegion region = manager == null ? null : manager.getRegion(regionId.trim());
            if (region != null) {
                return Optional.of(shapeOf(w.getName(), region));
            }
        }
        return Optional.empty();
    }

    static RegionShape shapeOf(String world, ProtectedRegion region) {
        String kind = region instanceof ProtectedCuboidRegion ? "cuboid"
                : region instanceof ProtectedPolygonalRegion ? "polygon"
                : region.getType().getName();
        List<int[]> points = new ArrayList<>();
        if (region instanceof ProtectedPolygonalRegion) {
            for (BlockVector2 p : region.getPoints()) {
                points.add(new int[] {p.x(), p.z()});
            }
        }
        var min = region.getMinimumPoint();
        var max = region.getMaximumPoint();
        return new RegionShape(world, kind, min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), points);
    }
}
