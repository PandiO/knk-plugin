package net.knightsandkings.knk.paper.regions.managed;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.ports.api.DistrictsQueryApi;
import net.knightsandkings.knk.core.ports.api.DomainCatalogQueryApi;
import net.knightsandkings.knk.core.ports.api.StructuresQueryApi;
import net.knightsandkings.knk.core.ports.api.TownsQueryApi;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionKind;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionPolicy;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionReconciler;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionRepairService;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionSpec;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionSpecSource;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionsConfig;
import net.knightsandkings.knk.core.regions.managed.RepairReport;

/**
 * Wires the managed-region rules into the server: the startup repair (after WorldGuard has loaded its regions, retried
 * while the API is still coming up) and the creation hook the region rename endpoint calls for a freshly finalized
 * region. Both end in the same {@link ManagedRegionReconciler}, so they cannot disagree.
 * See {@code knk-workspace/docs/architecture/managed-worldguard-regions.md}.
 */
public class ManagedRegionsBootstrap {

    private static final Logger LOGGER = Logger.getLogger(ManagedRegionsBootstrap.class.getName());

    private final JavaPlugin plugin;
    private final ManagedRegionsConfig config;
    private final WorldGuardManagedRegionStore store;
    private final ManagedRegionReconciler reconciler;
    private final ManagedRegionRepairService repairService;

    public ManagedRegionsBootstrap(JavaPlugin plugin, ManagedRegionsConfig config, TownsQueryApi towns,
                                   DistrictsQueryApi districts, StructuresQueryApi structures,
                                   DomainCatalogQueryApi domainCatalog) {
        this.plugin = plugin;
        this.config = config;
        this.store = new WorldGuardManagedRegionStore(ManagedRegionsBootstrap::loadedManagers);
        this.reconciler = new ManagedRegionReconciler(new ManagedRegionPolicy(config.options()), config.overrides());
        ManagedRegionSpecSource source = new ManagedRegionSpecSource(towns, districts, structures, domainCatalog, config.pageSize());
        this.repairService = new ManagedRegionRepairService(source, reconciler, store, config.extraRegions(),
                runnable -> Bukkit.getScheduler().runTask(plugin, runnable), LOGGER);
    }

    /** Reads {@code regions.managed} from the plugin's config. */
    public static ManagedRegionsConfig readConfig(JavaPlugin plugin) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("regions.managed");
        ManagedRegionsConfig config = ManagedRegionsConfig.fromMap(section != null ? toMap(section) : null);
        config.warnings().forEach(warning -> LOGGER.warning("[KnK Regions] config: " + warning));
        return config;
    }

    /** Schedules the startup repair (no-op when disabled in config). Call once from onEnable. */
    public void scheduleStartupRepair() {
        if (!config.repairEnabled()) {
            LOGGER.info("[KnK Regions] Startup repair disabled (regions.managed.startup-repair.enabled=false)");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(1), config.delayTicks());
    }

    /** Runs the same repair now (e.g. {@code /knk regions repair}); the future fails when the API or WorldGuard is not ready. */
    public CompletableFuture<RepairReport> repairNow() {
        if (loadedManagers().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("WorldGuard has no loaded region managers yet"));
        }
        return repairService.run();
    }

    /**
     * Creation path: a world task's region just became {@code regionId} for a domain of {@code domainType} whose parent
     * region is {@code parentRegionId}. Applies the category's parent, priority and flags immediately, so a new region is
     * correct from the start instead of waiting for the next startup. Main thread. An unknown type is left to the repair.
     */
    public void finalizeNewRegion(String regionId, String domainType, String parentRegionId) {
        ManagedRegionKind kind = ManagedRegionKind.fromDomainType(domainType).orElse(null);
        if (kind == null) {
            LOGGER.fine("[KnK Regions] " + regionId + " renamed without a domain type; the next repair will set it up");
            return;
        }
        RepairReport report = reconciler.reconcileOne(
                new ManagedRegionSpec(regionId, kind, parentRegionId, domainType + " (new)"), store);
        LOGGER.info("[KnK Regions] New region " + regionId + " as " + kind + ": " + report.summary());
        report.entries().stream().filter(entry -> entry.outcome() == RepairReport.Outcome.FAILED)
                .forEach(entry -> LOGGER.warning("[KnK Regions] " + entry.regionId() + ": " + entry.detail()));
        report.warnings().forEach(warning -> LOGGER.warning("[KnK Regions] " + warning));
    }

    /**
     * Whether the temp-region cleanup must leave {@code regionId} alone: it backs a domain, or the domain data has not been
     * read yet so nothing can be ruled out.
     */
    public boolean protectsFromCleanup(String regionId) {
        return !repairService.hasCompleted() || repairService.isManaged(regionId);
    }

    private void attempt(int attempt) {
        if (!plugin.isEnabled()) {
            return;
        }
        repairNow().whenComplete((report, error) -> {
            if (error == null) {
                return;
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (attempt < config.attempts() && plugin.isEnabled()) {
                LOGGER.warning("[KnK Regions] Managed-region repair attempt " + attempt + "/" + config.attempts()
                        + " could not run (" + cause.getMessage() + "); retrying in " + config.retryDelaySeconds() + "s");
                Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(attempt + 1), config.retryDelaySeconds() * 20L);
            } else {
                LOGGER.warning("[KnK Regions] Managed-region repair skipped after " + attempt + " attempt(s): " + cause.getMessage()
                        + ". No region was changed; run /knk regions repair once the API is up.");
            }
        });
    }

    private static List<RegionManager> loadedManagers() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded();
    }

    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }
}
