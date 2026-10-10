package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import com.sk89q.worldguard.WorldGuard;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionTransitionService;
import net.knightsandkings.knk.paper.events.OnRegionEnterEvent;
import net.knightsandkings.knk.paper.events.OnRegionLeaveEvent;
import net.knightsandkings.knk.paper.utils.ColorOptions;

/**
 * Tracks WorldGuard region transitions for players with intelligent caching and API call optimization.
 *
 * Key features:
 * - In-flight request tracking prevents duplicate API calls
 * - Queue-based re-validation enforces security after async API fetch
 * - Stale cache usage allows movement while fresh data loads
 * - Failed lookup cooldown prevents API hammering
 *
 * <p><b>Does not enforce AllowEntry/AllowExit</b> (KNG-56): WorldGuard does, through
 * {@code regions.access.DomainAccessHandler} and the flags it keeps on disk, before this tracker sees
 * a move (its listener runs at MONITOR). The tracker only describes moves that happened - welcome
 * messages, gate control, on-demand district gate loading and {@code OnRegionEnterEvent}/
 * {@code OnRegionLeaveEvent} - from the API's domain data.
 *
 * <p>KNG-55: a move is described from the regions at the player's actual position ({@code from}),
 * so a move the tracker never saw can't make it describe the wrong border.
 */
public class WorldGuardRegionTracker {
    private final RegionIds regionIds;
    private final Function<Location, Set<String>> regionLookup;
    private final RegionTransitionService transitionService;
    private final RegionDomainResolver regionResolver;
    private final Executor lookupExecutor;
    private final Executor mainThread;
    private final Consumer<Event> eventDispatcher;
    private final Logger logger;
    private final boolean enableConsoleLogging;

    private final Map<UUID, Set<String>> regionsByPlayer = new HashMap<>();
    /** KNG-112: the world of each player's tracked regions (absent when unknown). */
    private final Map<UUID, String> worldByPlayer = new HashMap<>();
    private static final char KEY_SEPARATOR = '\u0000';
    private final Map<String, Long> failedRegionLookups = new ConcurrentHashMap<>();
    // One future per region id being fetched; removed (and completed) when its lookup finishes.
    private final Map<String, CompletableFuture<Void>> inFlightLookups = new ConcurrentHashMap<>();
    // Also covers a lookup that came back without a domain (a WorldGuard region no domain uses).
    private static final long FAILED_LOOKUP_COOLDOWN_MS = 30000;  // 30 second cooldown

    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor, Plugin plugin, Logger logger, boolean enableConsoleLogging) {
        this(new RegionIds(), transitionService, regionResolver, lookupExecutor,
            plugin != null ? task -> Bukkit.getScheduler().runTask(plugin, task) : task -> { },
            event -> Bukkit.getPluginManager().callEvent(event), logger, enableConsoleLogging);
    }

    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor, Plugin plugin) {
        this(transitionService, regionResolver, lookupExecutor, plugin, null, false);
    }

    @Deprecated
    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor) {
        this(transitionService, regionResolver, lookupExecutor, null, null, false);
    }

    /**
     * Seam for tests: region ids per location, the main-thread scheduler and the event dispatcher are
     * supplied instead of being taken from WorldGuard and Bukkit.
     */
    WorldGuardRegionTracker(Function<Location, Set<String>> regionLookup, RegionTransitionService transitionService,
                            RegionDomainResolver regionResolver, Executor lookupExecutor, Executor mainThread,
                            Consumer<Event> eventDispatcher, Logger logger, boolean enableConsoleLogging) {
        this(null, regionLookup, transitionService, regionResolver, lookupExecutor, mainThread, eventDispatcher,
            logger, enableConsoleLogging);
    }

    /** The server's tracker: region ids through the shared {@link RegionIds} (R8). */
    private WorldGuardRegionTracker(RegionIds regionIds, RegionTransitionService transitionService,
                                    RegionDomainResolver regionResolver, Executor lookupExecutor, Executor mainThread,
                                    Consumer<Event> eventDispatcher, Logger logger, boolean enableConsoleLogging) {
        this(regionIds, regionIds::at, transitionService, regionResolver, lookupExecutor, mainThread, eventDispatcher,
            logger, enableConsoleLogging);
    }

    private WorldGuardRegionTracker(RegionIds regionIds, Function<Location, Set<String>> regionLookup,
                                    RegionTransitionService transitionService, RegionDomainResolver regionResolver,
                                    Executor lookupExecutor, Executor mainThread, Consumer<Event> eventDispatcher,
                                    Logger logger, boolean enableConsoleLogging) {
        this.regionIds = regionIds;
        this.regionLookup = regionLookup;
        this.transitionService = transitionService;
        this.regionResolver = regionResolver;
        this.lookupExecutor = lookupExecutor;
        this.mainThread = mainThread;
        this.eventDispatcher = eventDispatcher;
        this.logger = logger;
        this.enableConsoleLogging = enableConsoleLogging;
    }


    /**
     * Handle a player's move between WorldGuard regions (one that WorldGuard allowed).
     *
     * <p>The regions the player leaves are the ones at {@code from} - where the player really is -
     * not the tracked set, so a move another listener cancelled, or a ride the tracker never saw,
     * can't make the tracker describe the wrong border.
     *
     * <p>KNG-112: regions are judged in their own world. A move into another world leaves every region of the old
     * world and enters every region of the new one, even when both worlds have regions with the same ids.
     *
     * @return RegionTransitionDecision if regions changed and all data is cached, null otherwise
     */
    public RegionTransitionDecision handleMove(Player player, Location from, Location to) {
        if (player == null || to == null) {
            return null;
        }

        UUID playerId = player.getUniqueId();
        String newWorld = worldOf(to);
        Set<String> newRegions = getRegionNamesAt(to);
        Set<String> trackedRegions = regionsByPlayer.getOrDefault(playerId, Collections.emptySet());
        String trackedWorld = worldByPlayer.get(playerId);
        String oldWorld = from != null ? worldOf(from) : trackedWorld;
        Set<String> oldRegions = from != null ? getRegionNamesAt(from) : trackedRegions;
        boolean sameWorld = sameWorld(oldWorld, newWorld);

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " move: oldRegions=" + oldRegions + describeWorld(oldWorld)
                + ", newRegions=" + newRegions + describeWorld(newWorld));
        }

        // No region change = no processing (just resync a tracked set that fell behind)
        if (sameWorld && newRegions.equals(oldRegions)) {
            if (!trackedRegions.equals(newRegions) || !sameWorld(trackedWorld, newWorld)) {
                commitRegions(player, newWorld, newRegions);
            }
            return null;
        }

        // Check cache status for all relevant regions, each in its own world
        CacheStatus cacheStatus = checkCacheStatus(newWorld, newRegions).plus(checkCacheStatus(oldWorld, oldRegions));

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " cache: fresh=" + cacheStatus.fresh.size() +
                        ", stale=" + cacheStatus.stale.size() + ", missing=" + cacheStatus.missing.size() +
                        ", inFlight=" + cacheStatus.inFlight.size());
        }

        // If data is missing or being fetched, start/wait for async lookup
        if (!cacheStatus.missing.isEmpty() || !cacheStatus.inFlight.isEmpty()) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (Map.Entry<String, Set<String>> missing : byWorld(cacheStatus.missing).entrySet()) {
                pending.add(startAsyncLookup(player, worldFromKey(missing.getKey()), missing.getValue()));
            }
            // A lookup another move started: this player is re-validated when it lands, too.
            for (String key : cacheStatus.inFlight) {
                CompletableFuture<Void> lookup = inFlightLookups.get(key);
                if (lookup != null) {
                    pending.add(lookup);
                }
            }
            revalidateWhenResolved(player, pending, oldWorld, oldRegions, newWorld, newRegions, false);

            // Update player regions and allow movement with stale/partial data
            commitRegions(player, newWorld, newRegions);

            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " allowing movement (fetch in progress)");
            }
            return null;
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " processing transition (all data cached)");
        }

        RegionTransitionDecision decision = transitionService.handleRegionTransition(
            playerId, oldWorld, oldRegions, newWorld, newRegions);

        if (logger != null && decision != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " decision: allowed=" + decision.isMovementAllowed() +
                        ", message=" + decision.getMessage().orElse("(none)"));
        }

        commitRegions(player, newWorld, newRegions);
        return decision;
    }

    public void handleQuit(Player player) {
        if (player != null) {
            regionsByPlayer.remove(player.getUniqueId());
            worldByPlayer.remove(player.getUniqueId());
        }
    }

    /**
     * Handle player join - pre-warm cache for current regions.
     */
    public RegionTransitionDecision handleJoin(Player player) {
        if (player == null || player.getLocation() == null) return null;

        UUID playerId = player.getUniqueId();
        String world = worldOf(player.getLocation());
        Set<String> current = getRegionNamesAt(player.getLocation());

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " JOIN: initial regions=" + current + describeWorld(world));
        }

        regionsByPlayer.put(playerId, current);
        trackWorld(playerId, world);

        if (!current.isEmpty()) {
            // Start async pre-warm (don't block join)
            CacheStatus cacheStatus = checkCacheStatus(world, current);
            if (!cacheStatus.missing.isEmpty()) {
                if (logger != null) {
                    logger.fine("[KnK Tracker] " + player.getName() + " JOIN: pre-warming cache for: " + cacheStatus.missing);
                }
                // forceRevalidation=true: a player who joins already standing inside a district
                // never gets a genuine "entering" transition otherwise. The synchronous call
                // below resolves empty (cache is cold at this point), and if the player walks
                // away before this async fetch completes - which most players do within a couple
                // of seconds of spawning - the normal revalidatePlayerLocation would silently
                // skip re-processing entirely (it only acts if the player is still in the exact
                // same spot), permanently losing the one chance to notice they'd "entered"
                // whatever they joined inside, along with anything that depends on that event
                // (like on-demand district gate loading).
                revalidateWhenResolved(player, List.of(startAsyncLookup(player, world, idsOf(cacheStatus.missing))),
                    world, Collections.emptySet(), world, current, true);
            }
        }

        // Process transition if all data is cached
        if (current.isEmpty()) {
            return null;
        }

        RegionTransitionDecision decision = transitionService.handleRegionTransition(
            playerId, world, Collections.emptySet(), world, current);
        return decision;
    }

    /** The region ids at a location (R8: delegates to {@link RegionIds#at}). */
    private Set<String> getRegionNamesAt(Location bukkitLocation) {
        if (bukkitLocation == null || bukkitLocation.getWorld() == null) {
            return Collections.emptySet();
        }
        return regionLookup.apply(bukkitLocation);
    }

    /** The shared region-id lookup, for the road builder and navigation (R8); null in the test seam. */
    public RegionIds regionIds() {
        return regionIds;
    }

    /**
     * Check cache status for the region IDs of one world.
     * Returns breakdown of fresh/stale/missing/in-flight regions, as {@link #lookupKey} keys.
     */
    private CacheStatus checkCacheStatus(String world, Set<String> regionIds) {
        Set<String> fresh = new HashSet<>();
        Set<String> stale = new HashSet<>();
        Set<String> missing = new HashSet<>();
        Set<String> inFlight = new HashSet<>();

        for (String id : regionIds) {
            String key = lookupKey(world, id);
            // Check if already being fetched
            if (inFlightLookups.containsKey(key)) {
                inFlight.add(key);
                continue;
            }

            // Check if recently failed (cooldown period)
            Long lastFailedTime = failedRegionLookups.get(key);
            if (lastFailedTime != null && System.currentTimeMillis() - lastFailedTime < FAILED_LOOKUP_COOLDOWN_MS) {
                // Treat failed lookups as "stale" data (allow movement but skip refetch)
                stale.add(key);
                continue;
            }

            // Check cache (without triggering background refresh)
            if (!regionResolver.getDomainByRegionIdNoRefresh(world, id).isPresent()) {
                missing.add(key);
            } else {
                // Domain exists in cache (fresh or stale, doesn't matter - we have data)
                fresh.add(key);
            }
        }

        return new CacheStatus(fresh, stale, missing, inFlight);
    }

    /**
     * Start an async lookup for regions of {@code world} missing from the cache. The returned future completes (never
     * exceptionally) once the lookup has finished, successfully or not. Each id is in flight until
     * then - and only until then, so a later move can fetch it again (KNG-55: before, a combined key
     * for multi-region lookups was never removed, and every later lookup of the same set was skipped
     * along with its re-validation). An id the lookup leaves without a domain - a WorldGuard region
     * no domain uses, or an API failure - goes on the failed-lookup cooldown, so the next moves are
     * judged from the cache instead of being waved through as "fetch in progress" again.
     */
    private CompletableFuture<Void> startAsyncLookup(Player player, String world, Set<String> missingIds) {
        Set<String> ids = Set.copyOf(missingIds);
        CompletableFuture<Void> lookup = new CompletableFuture<>();
        for (String id : ids) {
            inFlightLookups.put(lookupKey(world, id), lookup);
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " starting async lookup for: " + ids + describeWorld(world));
        }

        try {
            lookupExecutor.execute(() -> {
                try {
                    regionResolver.resolveRegionsFromApi(world, ids).get();
                    if (logger != null) {
                        logger.fine("[KnK Tracker] " + player.getName() + " async lookup completed: " + ids);
                    }
                } catch (Exception ex) {
                    if (logger != null) {
                        logger.warning("[KnK Tracker] " + player.getName() + " async lookup FAILED: " + ex.getMessage());
                    }
                } finally {
                    finishLookup(world, ids, lookup);
                }
            });
        } catch (RuntimeException rejected) {
            finishLookup(world, ids, lookup);
        }
        return lookup;
    }

    private void finishLookup(String world, Set<String> ids, CompletableFuture<Void> lookup) {
        Set<String> unresolved = new HashSet<>();
        for (String id : ids) {
            if (regionResolver.getDomainByRegionIdNoRefresh(world, id).isEmpty()) {
                unresolved.add(lookupKey(world, id));
            }
        }
        recordFailedLookup(unresolved);
        for (String id : ids) {
            inFlightLookups.remove(lookupKey(world, id), lookup);
        }
        lookup.complete(null);
    }

    /** Once every pending lookup has finished, re-validate the move on the main thread. */
    private void revalidateWhenResolved(Player player, List<CompletableFuture<Void>> pending, String oldWorld,
                                        Set<String> oldRegions, String newWorld, Set<String> newRegions,
                                        boolean forceRevalidation) {
        if (pending.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, ex) -> mainThread.execute(
                () -> revalidatePlayerLocation(player, oldWorld, oldRegions, newWorld, newRegions, forceRevalidation)));
    }

    /**
     * Describe a move once the domain data it needed has arrived (welcome message, gates, district
     * gate loading). MUST be called on main thread. Access is not judged here: WorldGuard already
     * allowed the move (KNG-56).
     *
     * @param forceRevalidation if true (join-time pre-warm only), skips the "is the player still
     *     in the same spot" check and processes expectedNewRegions unconditionally - this exists
     *     purely to unblock one-time "entered" side effects (like district gate loading) for
     *     wherever the player joined, so entry/exit enforcement is intentionally NOT re-run here:
     *     retroactively teleporting a player away because a delayed pass resolved a deny-entry
     *     policy for their join spot, seconds after they've already been playing from wherever
     *     they walked to since, would be a surprising thing for the plugin to do on its own.
     */
    private void revalidatePlayerLocation(Player player, String oldWorld, Set<String> oldRegions, String expectedWorld,
                                          Set<String> expectedNewRegions, boolean forceRevalidation) {
        if (player == null || !player.isOnline()) {
            if (logger != null && player != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " revalidation skipped (offline)");
            }
            return;
        }

        UUID playerId = player.getUniqueId();

        if (forceRevalidation) {
            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " forced join revalidation for: " + expectedNewRegions);
            }
            transitionService.handleRegionTransition(playerId, oldWorld, oldRegions, expectedWorld, expectedNewRegions);
            return;
        }

        Location location = player.getLocation();
        Set<String> currentRegions = getRegionNamesAt(location);

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " revalidating: expected=" + expectedNewRegions + ", current=" + currentRegions);
        }

        if (!sameWorld(worldOf(location), expectedWorld) || !currentRegions.equals(expectedNewRegions)) {
            return;  // moved on: the later moves described themselves
        }
        RegionTransitionDecision decision = transitionService.handleRegionTransition(
            playerId, oldWorld, oldRegions, expectedWorld, currentRegions);
        if (decision != null && decision.isMovementAllowed()) {
            decision.getMessage().ifPresent(msg ->
                player.sendActionBar(Component.text(msg).color(ColorOptions.message))
            );
        }
    }

    /** Record {@code newRegions} (of {@code newWorld}) as the player's regions, firing enter/leave events for the difference. */
    private void commitRegions(Player player, String newWorld, Set<String> newRegions) {
        UUID playerId = player.getUniqueId();
        Set<String> oldRegions = regionsByPlayer.getOrDefault(playerId, Collections.emptySet());
        String oldWorld = worldByPlayer.get(playerId);
        regionsByPlayer.put(playerId, newRegions);
        trackWorld(playerId, newWorld);
        fireRegionEvents(player, sameWorld(oldWorld, newWorld), oldRegions, newRegions);
    }

    /**
     * Fire region enter/leave events. In another world (KNG-112) every old region is left and every new one entered,
     * even when the ids are the same.
     */
    private void fireRegionEvents(Player player, boolean sameWorld, Set<String> oldRegions, Set<String> newRegions) {
        Set<String> entered = new HashSet<>(newRegions);
        Set<String> left = new HashSet<>(oldRegions);
        if (sameWorld) {
            entered.removeAll(oldRegions);
            left.removeAll(newRegions);
        }

        if (entered.isEmpty() && left.isEmpty()) {
            return;
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " WG region change: entered=" + entered + ", left=" + left);
        }

        for (String regionId : entered) {
            eventDispatcher.accept(new OnRegionEnterEvent(player, regionId));
        }

        for (String regionId : left) {
            eventDispatcher.accept(new OnRegionLeaveEvent(player, regionId));
        }
    }

    private void recordFailedLookup(Set<String> keys) {
        long now = System.currentTimeMillis();
        for (String key : keys) {
            failedRegionLookups.put(key, now);
        }
    }

    // ===== KNG-112: worlds =====

    /** The world name of a location; null when it has none (or, in tests, an unnamed mock world). */
    private static String worldOf(Location location) {
        return location == null || location.getWorld() == null ? null : location.getWorld().getName();
    }

    private static boolean sameWorld(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private void trackWorld(UUID playerId, String world) {
        if (world == null) {
            worldByPlayer.remove(playerId);
        } else {
            worldByPlayer.put(playerId, world);
        }
    }

    private static String describeWorld(String world) {
        return world == null ? "" : " in " + world;
    }

    /** The in-flight and cooldown key of a region in a world; regions of other worlds never share a key. */
    private static String lookupKey(String world, String regionId) {
        return (world == null ? "" : world.toLowerCase(Locale.ROOT)) + KEY_SEPARATOR + regionId;
    }

    private static String worldFromKey(String key) {
        String world = key.substring(0, key.indexOf(KEY_SEPARATOR));
        return world.isEmpty() ? null : world;
    }

    private static String idFromKey(String key) {
        return key.substring(key.indexOf(KEY_SEPARATOR) + 1);
    }

    private static Set<String> idsOf(Set<String> keys) {
        Set<String> ids = new HashSet<>();
        for (String key : keys) {
            ids.add(idFromKey(key));
        }
        return ids;
    }

    private static Map<String, Set<String>> byWorld(Set<String> keys) {
        Map<String, Set<String>> grouped = new HashMap<>();
        for (String key : keys) {
            grouped.computeIfAbsent(key.substring(0, key.indexOf(KEY_SEPARATOR)) + KEY_SEPARATOR, k -> new HashSet<>())
                .add(idFromKey(key));
        }
        return grouped;
    }

    /**
     * Helper record for cache status breakdown ({@link #lookupKey} keys).
     */
    private record CacheStatus(
        Set<String> fresh,      // Cached and available
        Set<String> stale,      // Recently failed (cooldown)
        Set<String> missing,    // Not in cache at all
        Set<String> inFlight    // Currently being fetched
    ) {
        CacheStatus plus(CacheStatus other) {
            return new CacheStatus(union(fresh, other.fresh), union(stale, other.stale),
                union(missing, other.missing), union(inFlight, other.inFlight));
        }

        private static Set<String> union(Set<String> a, Set<String> b) {
            Set<String> all = new HashSet<>(a);
            all.addAll(b);
            return all;
        }
    }
}
