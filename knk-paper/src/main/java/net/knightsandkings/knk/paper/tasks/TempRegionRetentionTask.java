package net.knightsandkings.knk.paper.tasks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Background task for enforcing retention policies on temporary WorldGuard regions.
 * 
 * Policy:
 * - Delete all regions matching the temp region prefix (e.g., "tempregion_worldtask_")
 *   that are older than the specified retention days.
 * 
 * Runs daily by default; checks region creation metadata to determine age.
 */
public class TempRegionRetentionTask {
    private static final Logger LOGGER = Logger.getLogger(TempRegionRetentionTask.class.getName());
    private static final String TEMP_REGION_PREFIX = "tempregion_worldtask_";
    private static final long RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000; // 14 days in milliseconds
    
    // Custom flag to read creation timestamp (must match the one in WgRegionIdTaskHandler)
    private static final StringFlag CREATION_TIMESTAMP = new StringFlag("knk-creation-timestamp");
    
    private final Plugin plugin;
    private final long retentionMillis;
    private final Predicate<String> protectedRegion;
    private BukkitTask task;

    public TempRegionRetentionTask(Plugin plugin, long retentionDays) {
        this(plugin, retentionDays, regionId -> false);
    }

    /**
     * @param protectedRegion regions that must never be deleted even while still under a temp name: Town and Structure
     *                        creation does not rename the region, so a domain can legitimately live in one for good
     */
    public TempRegionRetentionTask(Plugin plugin, long retentionDays, Predicate<String> protectedRegion) {
        this.plugin = plugin;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000;
        this.protectedRegion = protectedRegion != null ? protectedRegion : regionId -> false;
    }

    /**
     * Start the retention task, running once per day.
     */
    public void start() {
        // Run first check after 5 minutes, then every 24 hours
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::runCleanup,
            5 * 60 * 20, // 5 minutes (in ticks: 20 ticks/second)
            24 * 60 * 60 * 20 // 24 hours
        );
        LOGGER.info("TempRegionRetentionTask started. Will clean up temp regions older than " + (retentionMillis / (24 * 60 * 60 * 1000)) + " days");
    }

    /**
     * Stop the retention task.
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        LOGGER.info("TempRegionRetentionTask stopped");
    }

    /**
     * Run the cleanup operation: scan all worlds for old temp regions and delete them.
     */
    private void runCleanup() {
        try {
            long cutoffTime = System.currentTimeMillis() - retentionMillis;
            int deletedCount = 0;

            for (World world : plugin.getServer().getWorlds()) {
                RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));

                if (regionManager == null) {
                    continue;
                }

                List<String> regionsToDelete = new ArrayList<>();

                // Find all temp regions older than retention period
                for (ProtectedRegion region : regionManager.getRegions().values()) {
                    if (region.getId().startsWith(TEMP_REGION_PREFIX)) {
                        if (protectedRegion.test(region.getId())) {
                            LOGGER.fine("Keeping temp-named region backing a domain: " + region.getId());
                            continue;
                        }
                        // Check region age using creation timestamp flag
                        String timestampStr = region.getFlag(CREATION_TIMESTAMP);
                        
                        if (timestampStr != null) {
                            try {
                                long creationTime = Long.parseLong(timestampStr);
                                
                                // Only delete if creation time is BEFORE cutoff (i.e., older than retention period)
                                if (creationTime < cutoffTime) {
                                    regionsToDelete.add(region.getId());
                                    LOGGER.fine("Marking region for deletion: " + region.getId() + 
                                               " (age: " + ((System.currentTimeMillis() - creationTime) / (24 * 60 * 60 * 1000)) + " days)");
                                }
                            } catch (NumberFormatException e) {
                                LOGGER.warning("Invalid creation timestamp for region " + region.getId() + ": " + timestampStr);
                                // Don't delete if timestamp is invalid - be safe
                            }
                        } else {
                            LOGGER.fine("Skipping region without creation timestamp: " + region.getId());
                            // Don't delete regions without timestamp - they might be from before this feature
                        }
                    }
                }

                // Delete old temp regions
                for (String regionId : regionsToDelete) {
                    try {
                        regionManager.removeRegion(regionId);
                        deletedCount++;
                        LOGGER.info("Deleted temp region (retention policy): " + regionId + " from world " + world.getName());
                    } catch (Exception e) {
                        LOGGER.warning("Failed to delete temp region " + regionId + ": " + e.getMessage());
                    }
                }
            }

            if (deletedCount > 0) {
                LOGGER.info("Temp region retention cleanup completed. Deleted " + deletedCount + " regions.");
            }
        } catch (Exception e) {
            LOGGER.warning("Error running temp region retention cleanup: " + e.getMessage());
        }
    }
}
