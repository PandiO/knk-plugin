package net.knightsandkings.knk.paper.regions.access;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.ports.api.DomainAccessRulesApi;
import net.knightsandkings.knk.core.regions.OrphanedSubRegions;
import net.knightsandkings.knk.core.regions.SubRegionMirror;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync;
import net.knightsandkings.knk.paper.regions.WorldGuardOrphanedSubRegionStore;
import net.knightsandkings.knk.paper.regions.WorldGuardSubRegionMirrorStore;

/**
 * Keeps the KnK access flags on the WorldGuard regions in step with the API (KNG-56): at startup
 * (with retries), every {@code interval-minutes}, on {@code /knk regions repair}, after a new
 * domain region is finalized, and - once the API can push (SignalR, KNG-57) - when a domain
 * changes ({@link #syncNow()}).
 *
 * <p>When the API is unreachable nothing is touched: the flags WorldGuard saved last time stay in
 * force, so enforcement never depends on the API being up.
 *
 * <p>KNG-255: each run then makes every domain's sub-regions copies of its main region ({@link SubRegionMirror}), so an
 * admin's flag on the main region reaches them within {@code interval-minutes}. KNG-258: then it marks the
 * {@code domain_<d>_sub_<s>} regions no domain lists any more as orphans ({@link OrphanedSubRegions}), which the daily
 * temp-region cleanup deletes after the retention period.
 */
public final class DomainAccessFlagSync {
    private static final Logger LOGGER = Logger.getLogger(DomainAccessFlagSync.class.getName());

    /** {@code regions.access.sync} in config.yml. */
    public record Settings(boolean enabled, long delayTicks, int attempts, int retryDelaySeconds, int intervalMinutes) {
        public static Settings read(JavaPlugin plugin) {
            var config = plugin.getConfig();
            return new Settings(
                config.getBoolean("regions.access.sync.enabled", true),
                Math.max(1, config.getLong("regions.access.sync.delay-ticks", 100)),
                Math.max(1, config.getInt("regions.access.sync.attempts", 3)),
                Math.max(1, config.getInt("regions.access.sync.retry-delay-seconds", 30)),
                Math.max(0, config.getInt("regions.access.sync.interval-minutes", 5)));
        }
    }

    private final JavaPlugin plugin;
    private final DomainAccessRulesApi api;
    private final Settings settings;
    private final AccessFlagSync sync = new AccessFlagSync();
    private final WorldGuardAccessFlagStore store = new WorldGuardAccessFlagStore(DomainAccessFlagSync::loadedManagers);
    private final AtomicBoolean running = new AtomicBoolean();
    private final SubRegionMirror mirror = new SubRegionMirror();
    private final WorldGuardSubRegionMirrorStore mirrorStore = new WorldGuardSubRegionMirrorStore(DomainAccessFlagSync::loadedManagers);
    private volatile SubRegionMirror.Report lastSubRegionReport;
    private final OrphanedSubRegions orphans = new OrphanedSubRegions();
    private final WorldGuardOrphanedSubRegionStore orphanStore = new WorldGuardOrphanedSubRegionStore(DomainAccessFlagSync::loadedManagers);
    /** KNG-258: the sub-region ids the last completed sync listed (lower-case); null before the first. */
    private volatile Set<String> listedSubRegions;

    public DomainAccessFlagSync(JavaPlugin plugin, DomainAccessRulesApi api, Settings settings) {
        this.plugin = plugin;
        this.api = api;
        this.settings = settings;
    }

    /** Startup sync with retries, then the periodic one. Call once from onEnable. */
    public void schedule() {
        if (!settings.enabled()) {
            LOGGER.warning("[KnK Access] Region access sync disabled (regions.access.sync.enabled=false): "
                + "domain AllowEntry/AllowExit changes reach the game server only via /knk regions repair");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(1), settings.delayTicks());
        if (settings.intervalMinutes() > 0) {
            long period = settings.intervalMinutes() * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(plugin, () -> syncNow().exceptionally(error -> null),
                settings.delayTicks() + period, period);
        }
    }

    /**
     * Read every domain's rules from the API and write them onto the regions (main thread). The
     * future fails when the API or WorldGuard is not ready; the regions are then left as they are.
     */
    public CompletableFuture<AccessFlagSync.Report> syncNow() {
        if (!DomainAccessFlags.registered()) {
            return CompletableFuture.failedFuture(new IllegalStateException("KnK access flags are not registered with WorldGuard"));
        }
        if (loadedManagers().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("WorldGuard has no loaded region managers yet"));
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("a region access sync is already running"));
        }
        CompletableFuture<AccessFlagSync.Report> result = new CompletableFuture<>();
        api.listAccessRules().whenComplete((rules, error) -> {
            if (error != null) {
                running.set(false);
                result.completeExceptionally(error);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    AccessFlagSync.Report report = sync.run(rules, store);
                    log(report);
                    try {
                        SubRegionMirror.Report subRegions = mirror.run(SubRegionMirror.DomainRegions.of(rules), mirrorStore);
                        lastSubRegionReport = subRegions;
                        log(subRegions);
                    } catch (RuntimeException e) {
                        lastSubRegionReport = null;
                        LOGGER.warning("[KnK Regions] Sub-region sync failed: " + e.getMessage());
                    }
                    listedSubRegions = rules.stream().flatMap(rule -> rule.subRegionIds().stream())
                        .map(id -> id.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
                    try {
                        OrphanedSubRegions.Report orphaned = orphans.run(rules, orphanStore, System.currentTimeMillis());
                        if (orphaned.changed() || !orphaned.failures().isEmpty()) {
                            LOGGER.info("[KnK Regions] Orphaned sub-regions: " + orphaned.summary()
                                + (orphaned.marked().isEmpty() ? "" : " - newly orphaned: " + orphaned.marked()));
                        }
                        orphaned.failures().stream().limit(10).forEach(failure -> LOGGER.warning("[KnK Regions] " + failure));
                    } catch (RuntimeException e) {
                        LOGGER.warning("[KnK Regions] Orphaned sub-region check failed: " + e.getMessage());
                    }
                    result.complete(report);
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                } finally {
                    running.set(false);
                }
            });
        });
        return result;
    }

    /**
     * KNG-258: whether the last completed sync listed {@code regionId} as a domain's sub-region; true for every region
     * until a sync has completed, so the temp-region cleanup deletes nothing it can't rule out.
     */
    public boolean listsSubRegion(String regionId) {
        Set<String> listed = listedSubRegions;
        return listed == null || (regionId != null && listed.contains(regionId.toLowerCase(Locale.ROOT)));
    }

    /** KNG-255: what the sub-region mirror did in the last completed sync (null before the first, or when it failed). */
    public SubRegionMirror.Report lastSubRegionReport() {
        return lastSubRegionReport;
    }

    private void attempt(int attempt) {
        if (!plugin.isEnabled()) {
            return;
        }
        syncNow().whenComplete((report, error) -> {
            if (error == null) {
                return;
            }
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            if (attempt < settings.attempts() && plugin.isEnabled()) {
                LOGGER.warning("[KnK Access] Region access sync attempt " + attempt + "/" + settings.attempts()
                    + " could not run (" + cause.getMessage() + "); retrying in " + settings.retryDelaySeconds() + "s");
                Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(attempt + 1), settings.retryDelaySeconds() * 20L);
            } else {
                LOGGER.warning("[KnK Access] Region access sync skipped (" + cause.getMessage()
                    + "). The access rules last saved on the regions stay in force.");
            }
        });
    }

    private static void log(AccessFlagSync.Report report) {
        if (report.updated() + report.cleared() > 0 || !report.failures().isEmpty()) {
            LOGGER.info("[KnK Access] Region access sync: " + report.summary());
        } else {
            LOGGER.fine("[KnK Access] Region access sync: " + report.summary());
        }
        if (!report.missingRegions().isEmpty()) {
            LOGGER.fine("[KnK Access] Domain regions not loaded: " + report.missingRegions());
        }
        report.failures().stream().limit(10).forEach(failure -> LOGGER.warning("[KnK Access] " + failure));
    }

    private static void log(SubRegionMirror.Report report) {
        if (report.domains() == 0) {
            return;
        }
        if (report.updated() > 0 || !report.failures().isEmpty() || !report.missingSubRegions().isEmpty()) {
            LOGGER.info("[KnK Regions] Sub-region sync: " + report.summary());
        } else {
            LOGGER.fine("[KnK Regions] Sub-region sync: " + report.summary());
        }
        if (!report.missingSubRegions().isEmpty()) {
            LOGGER.info("[KnK Regions] Sub-regions not in WorldGuard: " + report.missingSubRegions());
        }
        report.failures().stream().limit(10).forEach(failure -> LOGGER.warning("[KnK Regions] " + failure));
    }

    /** Every loaded world's WorldGuard region manager (main thread). */
    public static List<RegionManager> loadedManagers() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded();
    }
}
