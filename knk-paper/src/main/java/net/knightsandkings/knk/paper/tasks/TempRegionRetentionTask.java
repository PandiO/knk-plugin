package net.knightsandkings.knk.paper.tasks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Decision;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Background task for enforcing retention policies on temporary WorldGuard regions.
 *
 * Policy ({@link TempRegionCleanupPolicy}): a region named {@code tempregion_worldtask_*} whose creation timestamp is
 * older than the retention period is deleted only when no domain uses it - neither according to the last managed-region
 * repair nor according to a fresh API lookup made right before deleting. If the API can't be asked, nothing is deleted.
 *
 * Runs daily by default; checks region creation metadata to determine age.
 */
public class TempRegionRetentionTask {
    private static final Logger LOGGER = Logger.getLogger(TempRegionRetentionTask.class.getName());
    private static final long LOOKUP_TIMEOUT_SECONDS = 15;

    // Custom flag to read creation timestamp (must match the one in WgRegionIdTaskHandler)
    private static final StringFlag CREATION_TIMESTAMP = new StringFlag("knk-creation-timestamp");

    private final Plugin plugin;
    private final long retentionMillis;
    private final Predicate<String> knownDomainRegion;
    private final Function<String, Usage> domainUsage;
    private BukkitTask task;

    /**
     * @param knownDomainRegion regions the last managed-region repair saw a domain use (true for everything until it has
     *                          run): kept without asking the API
     * @param domainUsage       asks the API, right before a delete, whether a domain uses the region now; see
     *                          {@link #domainUsageVia(DomainsQueryApi)}
     */
    public TempRegionRetentionTask(Plugin plugin, long retentionDays, Predicate<String> knownDomainRegion,
                                   Function<String, Usage> domainUsage) {
        this.plugin = plugin;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000;
        this.knownDomainRegion = knownDomainRegion != null ? knownDomainRegion : regionId -> false;
        this.domainUsage = domainUsage != null ? domainUsage : regionId -> Usage.UNKNOWN;
    }

    /**
     * Fresh domain lookup by region id ({@code GET /Domains/by-region/{id}}): found = in use, 404 = unused, anything else
     * (API down, timeout, other error) = unknown, which keeps the region. Blocks; call off the main thread.
     */
    public static Function<String, Usage> domainUsageVia(DomainsQueryApi domainsQueryApi) {
        return regionId -> {
            if (domainsQueryApi == null) {
                return Usage.UNKNOWN;
            }
            try {
                return domainsQueryApi.getByWorldGuardRegionId(regionId).get(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) != null
                        ? Usage.IN_USE : Usage.UNKNOWN;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ApiException apiException && apiException.getStatusCode() == 404) {
                    return Usage.UNUSED;
                }
                LOGGER.warning("Could not check whether a domain uses temp region " + regionId + "; keeping it: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                return Usage.UNKNOWN;
            } catch (TimeoutException e) {
                LOGGER.warning("Timed out checking whether a domain uses temp region " + regionId + "; keeping it");
                return Usage.UNKNOWN;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Usage.UNKNOWN;
            }
        };
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
        LOGGER.info("TempRegionRetentionTask started. Will clean up unused temp regions older than " + (retentionMillis / (24 * 60 * 60 * 1000)) + " days");
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
     * Run the cleanup operation: scan all worlds for old, unused temp regions and delete them.
     */
    private void runCleanup() {
        try {
            long cutoffTime = System.currentTimeMillis() - retentionMillis;
            int deletedCount = 0;
            int keptInUse = 0;
            int keptUnknown = 0;

            for (World world : plugin.getServer().getWorlds()) {
                RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));

                if (regionManager == null) {
                    continue;
                }

                List<String> regionsToDelete = new ArrayList<>();

                for (ProtectedRegion region : new ArrayList<>(regionManager.getRegions().values())) {
                    if (!TempRegionCleanupPolicy.isTemporary(region.getId())) {
                        continue;
                    }
                    Decision decision = TempRegionCleanupPolicy.decide(region.getId(), region.getFlag(CREATION_TIMESTAMP),
                            cutoffTime, knownDomainRegion, domainUsage);
                    switch (decision) {
                        case DELETE -> regionsToDelete.add(region.getId());
                        case KEEP_KNOWN_DOMAIN_REGION, KEEP_IN_USE -> {
                            keptInUse++;
                            LOGGER.fine("Keeping temp-named region backing a domain: " + region.getId());
                        }
                        case KEEP_USAGE_UNKNOWN -> keptUnknown++;
                        case KEEP_INVALID_TIMESTAMP -> LOGGER.warning("Invalid creation timestamp for region " + region.getId()
                                + ": " + region.getFlag(CREATION_TIMESTAMP));
                        default -> LOGGER.fine("Keeping temp region " + region.getId() + ": " + decision);
                    }
                }

                // Delete old, unused temp regions
                for (String regionId : regionsToDelete) {
                    try {
                        regionManager.removeRegion(regionId);
                        deletedCount++;
                        LOGGER.info("Deleted temp region (retention policy, no domain uses it): " + regionId + " from world " + world.getName());
                    } catch (Exception e) {
                        LOGGER.warning("Failed to delete temp region " + regionId + ": " + e.getMessage());
                    }
                }
            }

            if (deletedCount > 0 || keptInUse > 0 || keptUnknown > 0) {
                LOGGER.info("Temp region retention cleanup completed. Deleted " + deletedCount + ", kept " + keptInUse
                        + " used by a domain" + (keptUnknown > 0 ? ", kept " + keptUnknown + " because the API could not be asked" : ""));
            }
            if (keptInUse > 0) {
                LOGGER.info("Temp-named regions used by a domain can be renamed to domain_<id> with POST /api/Regions/finalize-temp-names");
            }
        } catch (Exception e) {
            LOGGER.warning("Error running temp region retention cleanup: " + e.getMessage());
        }
    }
}
