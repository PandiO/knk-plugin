package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * The domain of a region for {@link NavigationAccess}: the cache, else the API - but never waiting for the API on the
 * main thread (KNG-123).
 * <p>
 * Off the main thread (the router) a region missing from the cache is asked for and waited on briefly, as before. On
 * the main thread (a policy built for {@code /navigate}, the route re-check) it is answered as unknown - open, as for
 * the trail ({@link NavigationAccess#mayEnter}) - and asked for in the background, one request per region at a time,
 * so the next build or re-check has it. Before, the main thread waited up to {@value #API_FALLBACK_SECONDS} s per such
 * region, which froze the server while the API was unreachable.
 */
final class NavigationDomainLookup {

    private static final Logger LOGGER = Logger.getLogger(NavigationDomainLookup.class.getName());
    static final long API_FALLBACK_SECONDS = 3;

    private final RegionDomainResolver resolver;
    private final BooleanSupplier onMainThread;
    /** Regions whose domain is being asked for in the background on behalf of a main-thread lookup. */
    private final Set<String> backgroundLookups = ConcurrentHashMap.newKeySet();

    /** @param onMainThread whether the caller is on the server main thread ({@code Bukkit::isPrimaryThread}) */
    NavigationDomainLookup(RegionDomainResolver resolver, BooleanSupplier onMainThread) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.onMainThread = Objects.requireNonNull(onMainThread, "onMainThread");
    }

    /** @param world the region's world (null: world-blind - answered only when the id is in one world) */
    Optional<DomainSnapshot> domainByRegionId(String world, String regionId) {
        Optional<DomainSnapshot> cached = resolver.getDomainByRegionIdNoRefresh(world, regionId);
        if (cached.isPresent()) {
            // KNG-104: an expired entry is answered as it is and re-asked in the background, so a change made in the
            // web app (AllowEntry/AllowExit, a domain moved to another region) reaches the next route or re-check
            resolver.refreshIfStale(world, Set.of(regionId));
            return cached;
        }
        if (onMainThread.getAsBoolean()) {
            lookUpInBackground(world, regionId);
            return Optional.empty();
        }
        try {
            resolver.resolveRegionsFromApi(world, Set.of(regionId)).get(API_FALLBACK_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[Navigation] Domain of region " + regionId + " not resolvable now: " + e.getMessage());
            return Optional.empty();
        }
        return resolver.getDomainByRegionIdNoRefresh(world, regionId);
    }

    private void lookUpInBackground(String world, String regionId) {
        String key = world + "\u0000" + regionId;
        if (!backgroundLookups.add(key)) {
            return;
        }
        try {
            resolver.resolveRegionsFromApi(world, Set.of(regionId)).whenComplete((snapshot, ex) -> {
                backgroundLookups.remove(key);
                if (ex != null) {
                    LOGGER.log(Level.FINE, "[Navigation] Domain of region " + regionId + " not resolvable now: " + ex.getMessage());
                }
            });
        } catch (RuntimeException e) {
            backgroundLookups.remove(key);
            LOGGER.log(Level.FINE, "[Navigation] Domain of region " + regionId + " not resolvable now: " + e.getMessage());
        }
    }
}
