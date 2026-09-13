package net.knightsandkings.knk.paper.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Small WorldGuard region-lookup helper. Used to expose {@link #regionExists} for general
 * WG-region checks against a live world.
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
}
