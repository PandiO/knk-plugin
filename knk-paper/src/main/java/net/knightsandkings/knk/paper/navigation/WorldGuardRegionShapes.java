package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.World;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.roads.route.RegionShape;

/**
 * {@link RegionShapes} over WorldGuard (DESIGN §6.3): a polygonal region's points and Y band, a
 * cuboid's corners. Main thread (WorldGuard's region managers are not thread-safe to read while
 * they change).
 */
public final class WorldGuardRegionShapes implements RegionShapes {

    @Override
    public Optional<RegionShape> shape(String world, String regionId) {
        return region(world, regionId).map(WorldGuardRegionShapes::toShape);
    }

    /** WorldGuard's own block containment (the feet block), like the region tracker; the shape when WorldGuard has no answer. */
    @Override
    public boolean containsFeet(String world, String regionId, RegionShape shape, double x, double y, double z) {
        Optional<ProtectedRegion> region = region(world, regionId);
        if (region.isEmpty()) {
            return RegionShapes.super.containsFeet(world, regionId, shape, x, y, z);
        }
        return region.get().contains(BlockVector3.at((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    }

    private static Optional<ProtectedRegion> region(String world, String regionId) {
        if (world == null || regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        World bukkitWorld = Bukkit.getWorld(world);
        if (bukkitWorld == null) {
            return Optional.empty();
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(bukkitWorld));
        if (manager == null) {
            return Optional.empty();
        }
        ProtectedRegion region = manager.getRegion(regionId);
        if (region == null || net.knightsandkings.knk.paper.regions.RegionIds.GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
            return Optional.empty();
        }
        return Optional.of(region);
    }

    static RegionShape toShape(ProtectedRegion region) {
        int minY = region.getMinimumPoint().getBlockY();
        int maxY = region.getMaximumPoint().getBlockY();
        if (region instanceof ProtectedPolygonalRegion polygon) {
            List<double[]> points = new ArrayList<>();
            for (BlockVector2 p : polygon.getPoints()) {
                points.add(new double[] {p.getBlockX(), p.getBlockZ()});
            }
            return RegionShape.polygon(points, minY, maxY);
        }
        return RegionShape.cuboid(region.getMinimumPoint().getBlockX(), minY, region.getMinimumPoint().getBlockZ(),
            region.getMaximumPoint().getBlockX(), maxY, region.getMaximumPoint().getBlockZ());
    }
}
