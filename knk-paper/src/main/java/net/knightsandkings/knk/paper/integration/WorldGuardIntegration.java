package net.knightsandkings.knk.paper.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.logging.Logger;

/**
 * Small WorldGuard region-lookup helper. Used to expose {@link #regionExists} for general
 * WG-region checks against a live world, and (lootboxes Phase 3) builds, adds and removes regions from a WorldEdit
 * selection: {@link #createRegionFromSelection} (moved here from {@code WgRegionIdTaskHandler}, which calls it) and
 * {@link #createFullHeightRegion} for {@code /knk lootbox area create}.
 *
 * Previously also synced a gate door's own WorldGuard regions (enable/disable entry on open/
 * close) via a {@code syncRegions(CachedGateDoor, ...)} method - removed in item 6.2, since
 * {@code GateDoor}'s region-name fields (formerly {@code RegionClosedId}/{@code RegionOpenedId})
 * were repurposed to hold captured region vertex JSON instead, not WorldGuard region names. See
 * WORLDGUARD_REGION_FEASIBILITY.md §9. This class itself, and WorldGuard's other integrations
 * elsewhere in the plugin (Town/District/GateStructure), are unaffected by that change.
 */
public class WorldGuardIntegration {
    private static final Logger LOGGER = Logger.getLogger(WorldGuardIntegration.class.getName());

    private final RegionContainer regionContainer;

    /**
     * Create a new WorldGuard integration handler.
     *
     * @param plugin The plugin instance for scheduler access
     */
    public WorldGuardIntegration(JavaPlugin plugin) {
        this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    /**
     * Check if a region exists in WorldGuard.
     * 
     * @param regionId The region ID to check
     * @return true if region exists, false otherwise
     */
    public boolean regionExists(String regionId, World world) {
        if (regionId == null || regionId.isEmpty() || regionContainer == null || world == null) {
            return false;
        }

        try {
            RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
            if (regionManager == null) {
                return false;
            }

            return regionManager.hasRegion(regionId);
        } catch (Exception e) {
            LOGGER.fine("Error checking region existence for '" + regionId + "': " + e.getMessage());
            return false;
        }
    }

    /** The world's region manager, or null when WorldGuard has none for it. */
    public RegionManager regionManager(World world) {
        if (regionContainer == null || world == null) {
            return null;
        }
        return regionContainer.get(BukkitAdapter.adapt(world));
    }

    /** Adds {@code region} to {@code world}'s region manager; false when there is none. */
    public boolean addRegion(ProtectedRegion region, World world) {
        RegionManager regionManager = regionManager(world);
        if (region == null || regionManager == null) {
            return false;
        }
        regionManager.addRegion(region);
        return true;
    }

    /** Removes the region {@code regionId} from {@code world}; false when it didn't exist. */
    public boolean removeRegion(String regionId, World world) {
        RegionManager regionManager = regionManager(world);
        if (regionId == null || regionId.isEmpty() || regionManager == null || !regionManager.hasRegion(regionId)) {
            return false;
        }
        Set<ProtectedRegion> removed = regionManager.removeRegion(regionId);
        return removed != null && !removed.isEmpty();
    }

    /**
     * A {@link ProtectedRegion} from a WorldEdit selection: a polygonal region for a poly2d selection, else a cuboid
     * over the selection's bounds. Not added to any region manager.
     */
    public static ProtectedRegion createRegionFromSelection(Region selection, String id, int priority) {
        if (selection instanceof Polygonal2DRegion poly) {
            ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(
                id,
                poly.getPoints(),
                poly.getMinimumY(),
                poly.getMaximumY()
            );
            region.setPriority(priority);
            return region;
        }

        // For other types, create a cuboid region from bounds
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();

        ProtectedRegion region = new ProtectedCuboidRegion(id, min, max);
        region.setPriority(priority);
        return region;
    }

    /**
     * {@link #createRegionFromSelection} stretched from {@code minY} to {@code maxY} (the world's build height): lootbox
     * spawn areas hold boxes on the surface, so a flat selection would reject most points (DESIGN.md §3.4).
     */
    public static ProtectedRegion createFullHeightRegion(Region selection, String id, int priority, int minY, int maxY) {
        if (selection instanceof Polygonal2DRegion poly) {
            ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(id, poly.getPoints(), minY, maxY);
            region.setPriority(priority);
            return region;
        }

        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        ProtectedRegion region = new ProtectedCuboidRegion(
            id,
            BlockVector3.at(min.getBlockX(), minY, min.getBlockZ()),
            BlockVector3.at(max.getBlockX(), maxY, max.getBlockZ())
        );
        region.setPriority(priority);
        return region;
    }
}
