package net.knightsandkings.knk.paper.tasks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.regions.RegionRenamePlan;
import net.knightsandkings.knk.core.regions.SubRegionMirror;
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
 *
 * KNG-258: also deletes orphaned domain sub-regions ({@code domain_<d>_sub_<s>} no domain lists, marked
 * {@code knk-orphaned-since} by the access sync) once they have been orphaned longer than the same retention period,
 * with the same fresh API lookup ({@link TempRegionCleanupPolicy#decideOrphanedSubRegion}).
 */
public class TempRegionRetentionTask {
    private static final Logger LOGGER = Logger.getLogger(TempRegionRetentionTask.class.getName());
    private static final long LOOKUP_TIMEOUT_SECONDS = 15;

    // Custom flag to read creation timestamp (must match the one in WgRegionIdTaskHandler)
    private static final StringFlag CREATION_TIMESTAMP = new StringFlag("knk-creation-timestamp");
    private static final StringFlag ORPHANED_SINCE = net.knightsandkings.knk.paper.regions.WorldGuardOrphanedSubRegionStore.ORPHANED_SINCE;

    private final Plugin plugin;
    private final long retentionMillis;
    private final Predicate<String> knownDomainRegion;
    private final Function<String, Usage> domainUsage;
    private final Predicate<String> listedSubRegion;
    private BukkitTask task;

    /**
     * @param knownDomainRegion regions the last managed-region repair saw a domain use (true for everything until it has
     *                          run): kept without asking the API
     * @param domainUsage       asks the API, right before a delete, whether a domain uses the region now; see
     *                          {@link #domainUsageVia(DomainsQueryApi)}
     */
    public TempRegionRetentionTask(Plugin plugin, long retentionDays, Predicate<String> knownDomainRegion,
                                   Function<String, Usage> domainUsage) {
        this(plugin, retentionDays, knownDomainRegion, domainUsage, null);
    }

    /**
     * @param listedSubRegion KNG-258: sub-regions the last access sync listed for a domain (true for everything until it
     *                        has run); null keeps every orphaned sub-region
     */
    public TempRegionRetentionTask(Plugin plugin, long retentionDays, Predicate<String> knownDomainRegion,
                                   Function<String, Usage> domainUsage, Predicate<String> listedSubRegion) {
        this.listedSubRegion = listedSubRegion != null ? listedSubRegion : regionId -> true;
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
     * What one pass over the temp-named regions found (and, when deleting, removed). Names are {@code world/region}.
     *
     * @param orphanedFinalNames {@code domain_<id>} regions no domain uses according to a fresh API lookup: reported
     *                           only, never deleted (a v2 region can carry the same name, managed-worldguard-regions.md
     *                           §8 item 9; check {@code /rg info} first). Only filled when asked for.
     * @param orphanedSubRegions KNG-258: domain sub-regions no domain lists, deleted (or deletable) together with the temp
     *                           regions above; {@code orphanedSubRegionsWaiting} are orphaned but not long enough (or
     *                           not marked yet, or the API couldn't be asked)
     */
    public record Sweep(List<String> deleted, List<String> deletable, List<String> inUse, List<String> tooRecent,
                        List<String> noTimestamp, List<String> usageUnknown, List<String> orphanedFinalNames,
                        List<String> deleteFailed, List<String> orphanedSubRegions, List<String> orphanedSubRegionsWaiting) {
        public int temporaryTotal() {
            return deleted.size() + deletable.size() + inUse.size() + tooRecent.size() + noTimestamp.size()
                    + usageUnknown.size() + deleteFailed.size();
        }
    }

    /**
     * Run the cleanup operation: scan all worlds for old, unused temp regions and delete them.
     */
    private void runCleanup() {
        try {
            Sweep sweep = sweep(System.currentTimeMillis() - retentionMillis, true, false);
            int deletedCount = sweep.deleted().size() + sweep.orphanedSubRegions().size();
            int keptInUse = sweep.inUse().size();
            int keptUnknown = sweep.usageUnknown().size();
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

    /**
     * One pass over every loaded world's temp-named regions with the cleanup policy, for regions created before
     * {@code cutoffMillis}. Used by the daily cleanup and by {@code /knk regions temp} (KNG-103), which reports first and
     * purges with a shorter age on request. Asks the API per region; call off the main thread.
     *
     * @param delete             remove the regions the policy allows; false only reports them as deletable
     * @param findOrphanedFinals also look up every {@code domain_<id>} region and report the ones no domain uses
     */
    public Sweep sweep(long cutoffMillis, boolean delete, boolean findOrphanedFinals) {
        Sweep sweep = new Sweep(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        for (World world : plugin.getServer().getWorlds()) {
            RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));

            if (regionManager == null) {
                continue;
            }

            List<String> regionsToDelete = new ArrayList<>();
            List<String> orphanedSubRegionsToDelete = new ArrayList<>();

            for (ProtectedRegion region : new ArrayList<>(regionManager.getRegions().values())) {
                String name = world.getName() + "/" + region.getId();
                if (SubRegionMirror.isSubRegionName(region.getId())) {
                    // KNG-258: an orphaned domain sub-region, counted from when it was orphaned
                    Decision orphan = TempRegionCleanupPolicy.decideOrphanedSubRegion(region.getId(),
                            region.getFlag(ORPHANED_SINCE), cutoffMillis, listedSubRegion, domainUsage);
                    if (orphan == Decision.DELETE) {
                        orphanedSubRegionsToDelete.add(region.getId());
                    } else if (orphan != Decision.KEEP_KNOWN_DOMAIN_REGION && orphan != Decision.KEEP_IN_USE) {
                        sweep.orphanedSubRegionsWaiting().add(name);
                    }
                    continue;
                }
                if (!TempRegionCleanupPolicy.isTemporary(region.getId())) {
                    if (findOrphanedFinals && RegionRenamePlan.isFinalName(region.getId())
                            && !knownDomainRegion.test(region.getId())
                            && domainUsage.apply(region.getId()) == Usage.UNUSED) {
                        sweep.orphanedFinalNames().add(name);
                    }
                    continue;
                }
                Decision decision = TempRegionCleanupPolicy.decide(region.getId(), region.getFlag(CREATION_TIMESTAMP),
                        cutoffMillis, knownDomainRegion, domainUsage);
                switch (decision) {
                    case DELETE -> regionsToDelete.add(region.getId());
                    case KEEP_KNOWN_DOMAIN_REGION, KEEP_IN_USE -> {
                        sweep.inUse().add(name);
                        LOGGER.fine("Keeping temp-named region backing a domain: " + region.getId());
                    }
                    case KEEP_USAGE_UNKNOWN -> sweep.usageUnknown().add(name);
                    case KEEP_TOO_RECENT -> sweep.tooRecent().add(name);
                    case KEEP_INVALID_TIMESTAMP -> {
                        sweep.noTimestamp().add(name);
                        LOGGER.warning("Invalid creation timestamp for region " + region.getId()
                                + ": " + region.getFlag(CREATION_TIMESTAMP));
                    }
                    default -> sweep.noTimestamp().add(name);
                }
            }

            for (String regionId : orphanedSubRegionsToDelete) {
                String name = world.getName() + "/" + regionId;
                if (!delete) {
                    sweep.orphanedSubRegions().add(name);
                    continue;
                }
                try {
                    regionManager.removeRegion(regionId);
                    sweep.orphanedSubRegions().add(name);
                    LOGGER.info("Deleted orphaned sub-region (no domain lists it, orphaned past the retention period): "
                        + regionId + " from world " + world.getName());
                } catch (Exception e) {
                    sweep.deleteFailed().add(name);
                    LOGGER.warning("Failed to delete orphaned sub-region " + regionId + ": " + e.getMessage());
                }
            }

            // Delete old, unused temp regions
            for (String regionId : regionsToDelete) {
                String name = world.getName() + "/" + regionId;
                if (!delete) {
                    sweep.deletable().add(name);
                    continue;
                }
                try {
                    regionManager.removeRegion(regionId);
                    sweep.deleted().add(name);
                    LOGGER.info("Deleted temp region (retention policy, no domain uses it): " + regionId + " from world " + world.getName());
                } catch (Exception e) {
                    sweep.deleteFailed().add(name);
                    LOGGER.warning("Failed to delete temp region " + regionId + ": " + e.getMessage());
                }
            }
        }
        return sweep;
    }
}
