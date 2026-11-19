package net.knightsandkings.knk.paper.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.map.RegionOutlinePort;

/**
 * {@link RegionOutlinePort} over WorldGuard (OVERVIEW_MAP_DESIGN § 8): a region's points as WorldGuard gives them (a
 * cuboid's four corners, a polygon's points, block coordinates) and its minimum and maximum Y. A region of a named world
 * is looked up in that world's manager only (KNG-112); without a world, in each loaded world in turn. Global regions have
 * no outline. Main thread.
 */
public final class RegionOutlineAdapter implements RegionOutlinePort {

    private final Supplier<List<RegionManager>> managers;

    public RegionOutlineAdapter(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public Optional<RegionOutline> outline(String worldName, String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        for (RegionManager manager : managers.get()) {
            if (worldName != null && !worldName.equalsIgnoreCase(manager.getName())) {
                continue;
            }
            ProtectedRegion region = manager.getRegion(regionId.trim());
            if (region != null) {
                return outlineOf(region, manager.getName());
            }
        }
        return Optional.empty();
    }

    /** {@code region}'s outline in {@code worldName}; empty for a global region or one without three points. */
    public static Optional<RegionOutline> outlineOf(ProtectedRegion region, String worldName) {
        if (region instanceof GlobalProtectedRegion) {
            return Optional.empty();
        }
        List<BlockVector2> raw = region.getPoints();
        if (raw == null || raw.size() < 3) {
            return Optional.empty();
        }
        List<OutlinePoint> points = new ArrayList<>(raw.size());
        for (BlockVector2 point : raw) {
            points.add(new OutlinePoint(point.x(), point.z()));
        }
        return Optional.of(new RegionOutline(region.getId(), worldName, points,
                region.getMinimumPoint().y(), region.getMaximumPoint().y()));
    }
}
