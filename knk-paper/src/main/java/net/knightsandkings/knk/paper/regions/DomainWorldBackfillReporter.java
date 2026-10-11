package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;

import net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi;
import net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi.MissingDomain;

/**
 * KNG-112: after startup, tells the API in which loaded world(s) the region of each domain without a world exists, so
 * domains created before worlds were stored get theirs (the API fills a domain only where this report, its Location and
 * its parent agree). Domains still left are logged for an admin to set in the web app. Changes nothing in WorldGuard;
 * retried a few times while the API is coming up.
 */
public final class DomainWorldBackfillReporter {
    private static final Logger LOGGER = Logger.getLogger(DomainWorldBackfillReporter.class.getName());
    private static final int ATTEMPTS = 3;
    private static final long RETRY_TICKS = 60L * 20L;
    private static final int LOGGED_UNRESOLVED = 10;

    private final JavaPlugin plugin;
    private final DomainWorldBackfillApi api;

    public DomainWorldBackfillReporter(JavaPlugin plugin, DomainWorldBackfillApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** Runs once, {@code delayTicks} after enable (once WorldGuard has loaded its regions). */
    public void schedule(long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(1), Math.max(1, delayTicks));
    }

    private void attempt(int attempt) {
        if (!plugin.isEnabled()) {
            return;
        }
        api.listMissing()
            .thenCompose(missing -> missing.isEmpty()
                ? CompletableFuture.completedFuture((DomainWorldBackfillApi.Result) null)
                : onMainThread(() -> worldsOf(missing)).thenCompose(api::backfill))
            .whenComplete((result, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    if (attempt < ATTEMPTS && plugin.isEnabled()) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(attempt + 1), RETRY_TICKS);
                    } else {
                        LOGGER.warning("[KnK Worlds] Could not report region worlds to the API: " + cause.getMessage());
                    }
                    return;
                }
                report(result);
            });
    }

    /**
     * For each region of a domain without a world: the worlds (in {@code hasRegionByWorld}'s order) that have a region
     * with that id.
     */
    static Map<String, List<String>> worldsOf(List<MissingDomain> missing, Map<String, Predicate<String>> hasRegionByWorld) {
        Map<String, List<String>> worlds = new LinkedHashMap<>();
        for (MissingDomain domain : missing) {
            String regionId = domain.wgRegionId();
            if (regionId == null || regionId.isBlank() || worlds.containsKey(regionId)) {
                continue;
            }
            List<String> found = new ArrayList<>();
            hasRegionByWorld.forEach((world, hasRegion) -> {
                if (hasRegion.test(regionId)) {
                    found.add(world);
                }
            });
            worlds.put(regionId, found);
        }
        return worlds;
    }

    /** Main thread: every loaded world's WorldGuard regions. */
    private static Map<String, List<String>> worldsOf(List<MissingDomain> missing) {
        Map<String, Predicate<String>> hasRegionByWorld = new LinkedHashMap<>();
        for (RegionManager manager : WorldGuard.getInstance().getPlatform().getRegionContainer().getLoaded()) {
            hasRegionByWorld.put(manager.getName(), manager::hasRegion);
        }
        return worldsOf(missing, hasRegionByWorld);
    }

    private <T> CompletableFuture<T> onMainThread(java.util.function.Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(work.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private static void report(DomainWorldBackfillApi.Result result) {
        if (result == null) {
            LOGGER.fine("[KnK Worlds] Every domain has a world");
            return;
        }
        LOGGER.info("[KnK Worlds] Domain world backfill: " + result.updated() + " domain(s) given a world, "
            + result.unresolved().size() + " still without one");
        result.unresolved().stream().limit(LOGGED_UNRESOLVED).forEach(domain -> LOGGER.warning(
            "[KnK Worlds] " + domain.domainType() + " " + domain.name() + " (#" + domain.id() + ", region "
                + domain.wgRegionId() + ") has no world" + (domain.candidateWorlds().isEmpty()
                    ? " and its region is in no loaded world"
                    : "; its region is in " + domain.candidateWorlds()) + ": choose it in the domain's form"));
        if (result.unresolved().size() > LOGGED_UNRESOLVED) {
            LOGGER.warning("[KnK Worlds] ... and " + (result.unresolved().size() - LOGGED_UNRESOLVED)
                + " more (GET /api/Domains/world/missing)");
        }
    }
}
