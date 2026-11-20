package net.knightsandkings.knk.paper.production;

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

import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;

/**
 * A {@code Region} production area's volume from WorldGuard (PRODUCTION_KINDS § 3.1, PandiO/knk-workspace#679): a cuboid
 * region's corners or a polygonal region's points and y band, copied into an immutable {@link AreaShape}. The global
 * region and other region types have no volume here. Main thread.
 */
public final class SurveyRegionShapes implements AreaSpec.RegionShapes {

    @Override
    public Optional<AreaShape> shapeOf(String world, String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        List<World> worlds = new ArrayList<>();
        World named = world == null ? null : Bukkit.getWorld(world);
        if (named != null) {
            worlds.add(named);
        } else if (world == null) {
            worlds.addAll(Bukkit.getWorlds());
        }
        for (World candidate : worlds) {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(candidate));
            ProtectedRegion region = manager == null ? null : manager.getRegion(regionId);
            if (region != null) {
                return toShape(region);
            }
        }
        return Optional.empty();
    }

    public static Optional<AreaShape> toShape(ProtectedRegion region) {
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        if (region instanceof ProtectedPolygonalRegion polygon) {
            List<int[]> points = new ArrayList<>();
            for (BlockVector2 p : polygon.getPoints()) {
                points.add(new int[] {p.x(), p.z()});
            }
            return points.size() < 3 ? Optional.empty() : Optional.of(AreaShape.Polygon.of(points, minY, maxY));
        }
        if (region instanceof ProtectedCuboidRegion) {
            return Optional.of(new AreaShape.Cuboid(region.getMinimumPoint().x(), minY,
                    region.getMinimumPoint().z(), region.getMaximumPoint().x(), maxY,
                    region.getMaximumPoint().z()));
        }
        return Optional.empty();
    }
}
