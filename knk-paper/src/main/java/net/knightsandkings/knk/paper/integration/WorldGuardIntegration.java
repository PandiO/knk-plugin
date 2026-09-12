package net.knightsandkings.knk.paper.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Integrates WorldGuard region synchronization with gate animation state.
 * Enables/disables regions based on whether gate is open or closed.
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
     * Synchronize WorldGuard regions based on gate animation state.
     * Called when animation finishes to update region access.
     * 
     * @param gate The gate being animated
     * @param newState The new animation state (OPEN or CLOSED)
     */
    public void syncRegions(CachedGateDoor gate, AnimationState newState, World world) {
        if (regionContainer == null || world == null) {
            LOGGER.fine("Region container not initialized, skipping sync");
            return;
        }

        try {
            RegionManager regionManager = regionContainer.get(BukkitAdapter.adapt(world));
            if (regionManager == null) {
                LOGGER.fine("RegionManager not available for world " + world.getName() + ", skipping sync");
                return;
            }

            if (newState == AnimationState.OPEN) {
                // Gate is open: disable closed region, enable open region
                setEntryFlag(regionManager, gate.getRegionClosedId(), StateFlag.State.ALLOW);
                setEntryFlag(regionManager, gate.getRegionOpenedId(), StateFlag.State.ALLOW);
                LOGGER.info("Synced regions for gate '" + gate.getName() + "': CLOSED disabled, OPEN enabled");
            } else if (newState == AnimationState.CLOSED) {
                // Gate is closed: enable closed region, disable open region
                setEntryFlag(regionManager, gate.getRegionClosedId(), StateFlag.State.DENY);
                setEntryFlag(regionManager, gate.getRegionOpenedId(), StateFlag.State.DENY);
                LOGGER.info("Synced regions for gate '" + gate.getName() + "': CLOSED enabled, OPEN disabled");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to sync regions for gate '" + gate.getName() + "': " + e.getMessage());
        }
    }

    /**
     * Apply ENTRY flag to a WorldGuard region if it exists.
     * 
     * @param regionManager The WorldGuard region manager
     * @param regionId The region ID
     * @param state The entry flag state to apply
     */
    private void setEntryFlag(RegionManager regionManager, String regionId, StateFlag.State state) {
        if (regionManager == null || regionId == null || regionId.isBlank()) {
            return;
        }

        ProtectedRegion region = regionManager.getRegion(regionId);
        if (region == null) {
            LOGGER.fine("Region not found: " + regionId);
            return;
        }

        region.setFlag(Flags.ENTRY, state);
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
