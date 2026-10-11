package net.knightsandkings.knk.paper.navigation.walk;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.paper.navigation.NavigationAccess;
import net.knightsandkings.knk.paper.navigation.WorldGuardRegionShapes;
import net.knightsandkings.knk.paper.regions.RegionIds;

/**
 * The WorldGuard side of {@link WalkAccessFactory} (KNG-51 {@code LAST_MILE_PATHFINDING.md} §6), main
 * thread only: the regions overlapping a walk box, and whether a player may open a door block.
 */
public final class WorldGuardWalkAccess {

    private WorldGuardWalkAccess() {
    }

    /** The server's factory: navigation's access adapters and WorldGuard. */
    public static WalkAccessFactory factory(NavigationAccess access) {
        Objects.requireNonNull(access, "access");
        return new WalkAccessFactory(access::gateAvailability, access::regionsAt, access::bypasses,
            WorldGuardWalkAccess::regions, WorldGuardWalkAccess::mayUseDoor, access::domainByRegionId,
            access.evaluator());
    }

    /** Every region but {@code __global__} overlapping the box, by id, as Bukkit-free shapes. */
    static Map<String, RegionShape> regions(World world, WalkBox box) {
        Map<String, RegionShape> shapes = new TreeMap<>();
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) {
            return shapes;
        }
        ProtectedCuboidRegion query = new ProtectedCuboidRegion("__knk_walk_box__",
            BlockVector3.at(box.minX(), box.minY(), box.minZ()), BlockVector3.at(box.maxX(), box.maxY(), box.maxZ()));
        ApplicableRegionSet set = manager.getApplicableRegions(query);
        for (ProtectedRegion region : set) {
            if (!RegionIds.GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
                shapes.put(region.getId(), shapeOf(region));
            }
        }
        return shapes;
    }

    /** The region's shape; a degenerate polygon (fewer than three points) as its bounding cuboid, never dropped. */
    static RegionShape shapeOf(ProtectedRegion region) {
        try {
            return WorldGuardRegionShapes.toShape(region);
        } catch (IllegalArgumentException e) {
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return RegionShape.cuboid(min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(),
                max.getBlockY(), max.getBlockZ());
        }
    }

    /**
     * WorldGuard's answer to the player clicking the door: its region bypass, else {@code testBuild}
     * with {@code INTERACT} and {@code USE} (membership or both flags allowing — at least as strict as
     * the region protection listener's own door check).
     */
    static boolean mayUseDoor(Player player, World world, int x, int y, int z) {
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, BukkitAdapter.adapt(world))) {
            return true;
        }
        Location location = new Location(world, x + 0.5, y, z + 0.5);
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
            .testBuild(BukkitAdapter.adapt(location), local, Flags.INTERACT, Flags.USE);
    }
}
