package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import net.kyori.adventure.text.Component;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;

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
 * <p>KNG-55: a move is judged from the regions at the player's actual position ({@code from}), and the
 * tracked region set (and the enter/leave events) only follow a move that is allowed or bypassed. A
 * denied move is cancelled by {@code WorldGuardRegionListener} and leaves the player - and this
 * tracker - where they were, so the next step over the border is judged (and denied) again. Before,
 * the tracked set was updated before the decision, so after one cancelled step the tracker believed
 * the player was already across and let every following step through.
 */
public class WorldGuardRegionTracker {
    private final Function<Location, Set<String>> regionLookup;
    private final RegionTransitionService transitionService;
    private final RegionDomainResolver regionResolver;
    private final Executor lookupExecutor;
    private final Executor mainThread;
    private final Consumer<Event> eventDispatcher;
    private final Logger logger;
    private final boolean enableConsoleLogging;

    private final Map<UUID, Set<String>> regionsByPlayer = new HashMap<>();
    private final Map<String, Long> failedRegionLookups = new ConcurrentHashMap<>();
    // One future per region id being fetched; removed (and completed) when its lookup finishes.
    private final Map<String, CompletableFuture<Void>> inFlightLookups = new ConcurrentHashMap<>();
    // Players being moved by enforcementTeleport: that teleport is never judged (it may leave a no-exit domain).
    private final Set<UUID> enforcementTeleports = new HashSet<>();
    // Who may ignore AllowEntry/AllowExit denials (knk.region.bypass, docs/specs/teleport/DESIGN.md §4 D11).
    private volatile Predicate<Player> denialBypass = player -> false;

    // Also covers a lookup that came back without a domain (a WorldGuard region no domain uses).
    private static final long FAILED_LOOKUP_COOLDOWN_MS = 30000;  // 30 second cooldown

    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor, Plugin plugin, Logger logger, boolean enableConsoleLogging) {
        this(worldGuardRegionLookup(), transitionService, regionResolver, lookupExecutor,
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
        this.regionLookup = regionLookup;
        this.transitionService = transitionService;
        this.regionResolver = regionResolver;
        this.lookupExecutor = lookupExecutor;
        this.mainThread = mainThread;
        this.eventDispatcher = eventDispatcher;
        this.logger = logger;
        this.enableConsoleLogging = enableConsoleLogging;
    }

    private static Function<Location, Set<String>> worldGuardRegionLookup() {
        RegionQuery regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        return bukkitLocation -> {
            com.sk89q.worldedit.util.Location wgLoc = BukkitAdapter.adapt(bukkitLocation);
            ApplicableRegionSet set = regionQuery.getApplicableRegions(wgLoc);

            Set<String> names = new HashSet<>();
            for (ProtectedRegion region : set) {
                names.add(region.getId());
            }
            return names;
        };
    }

    /**
     * Handle player movement between WorldGuard regions.
     *
     * <p>The regions the player leaves are the ones at {@code from} - where the player really is -
     * not the tracked set, so a move another listener cancelled, or a ride the tracker never saw,
     * can't make the tracker judge the wrong border. The tracked set and the enter/leave events are
     * only updated when the move goes ahead: when the returned decision denies the move and the
     * player holds no bypass, the caller must cancel it, and the tracker keeps the player outside.
     *
     * @return RegionTransitionDecision if regions changed and all data is cached, null otherwise
     */
    public RegionTransitionDecision handleMove(Player player, Location from, Location to) {
        if (player == null || to == null) {
            return null;
        }

        UUID playerId = player.getUniqueId();
        Set<String> newRegions = getRegionNamesAt(to);
        if (enforcementTeleports.contains(playerId)) {
            commitRegions(player, newRegions);
            return null;
        }

        Set<String> trackedRegions = regionsByPlayer.getOrDefault(playerId, Collections.emptySet());
        Set<String> oldRegions = from != null ? getRegionNamesAt(from) : trackedRegions;

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " move: oldRegions=" + oldRegions + ", newRegions=" + newRegions);
        }

        // No region change = no processing (just resync a tracked set that fell behind)
        if (newRegions.equals(oldRegions)) {
            if (!trackedRegions.equals(newRegions)) {
                commitRegions(player, newRegions);
            }
            return null;
        }

        // Check cache status for all relevant regions
        Set<String> lookupIds = new HashSet<>(newRegions);
        lookupIds.addAll(oldRegions);

        CacheStatus cacheStatus = checkCacheStatus(lookupIds);

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " cache: fresh=" + cacheStatus.fresh.size() +
                        ", stale=" + cacheStatus.stale.size() + ", missing=" + cacheStatus.missing.size() +
                        ", inFlight=" + cacheStatus.inFlight.size());
        }

        // If data is missing or being fetched, start/wait for async lookup
        if (!cacheStatus.missing.isEmpty() || !cacheStatus.inFlight.isEmpty()) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            if (!cacheStatus.missing.isEmpty()) {
                pending.add(startAsyncLookup(player, cacheStatus.missing));
            }
            // A lookup another move started: this player is re-validated when it lands, too.
            for (String id : cacheStatus.inFlight) {
                CompletableFuture<Void> lookup = inFlightLookups.get(id);
                if (lookup != null) {
                    pending.add(lookup);
                }
            }
            revalidateWhenResolved(player, pending, oldRegions, newRegions, from, false);

            // Update player regions and allow movement with stale/partial data
            commitRegions(player, newRegions);

            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " allowing movement (fetch in progress)");
            }
            return null;
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " processing transition (all data cached)");
        }

        RegionTransitionDecision decision = transitionService.handleRegionTransition(playerId, oldRegions, newRegions);

        if (logger != null && decision != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " decision: allowed=" + decision.isMovementAllowed() +
                        ", message=" + decision.getMessage().orElse("(none)"));
        }

        if (decision == null || decision.isMovementAllowed() || bypassesDenials(player)) {
            commitRegions(player, newRegions);
        }
        // else: denied - the caller cancels the move, so the player (and the tracked set) stay put
        // and the next step over the border is judged again.
        return decision;
    }

    /**
     * Players matching {@code bypass} are never stopped by an AllowEntry/AllowExit denial - neither
     * by {@code WorldGuardRegionListener} nor by the delayed re-validation below.
     */
    public void setDenialBypass(Predicate<Player> bypass) {
        this.denialBypass = bypass != null ? bypass : player -> false;
    }

    public boolean bypassesDenials(Player player) {
        return player != null && denialBypass.test(player);
    }

    /**
     * Would moving {@code player} to {@code to} be refused by a domain's AllowEntry/AllowExit?
     * Side-effect free (no region events, no tracked-region update, no gate control) and cache-only,
     * for the teleport engine's up-front check (docs/specs/teleport/DESIGN.md §3.4). Main thread only.
     *
     * @return a deny decision, or null when allowed or not decidable from the cache
     */
    public RegionTransitionDecision previewAccess(Player player, Location to) {
        if (player == null || to == null || to.getWorld() == null) {
            return null;
        }
        Set<String> oldRegions = regionsByPlayer.get(player.getUniqueId());
        if (oldRegions == null) {
            oldRegions = getRegionNamesAt(player.getLocation());
        }
        Set<String> newRegions = getRegionNamesAt(to);
        if (oldRegions.equals(newRegions)) {
            return null;
        }
        return transitionService.previewAccess(oldRegions, newRegions);
    }

    /**
     * Teleport {@code player} to {@code target} to undo a move a domain refuses (KNG-55). The teleport
     * itself is not judged - it may have to take the player out of a domain they may not leave - and
     * the tracked regions follow wherever the player ends up. Main thread only.
     */
    public void enforcementTeleport(Player player, Location target) {
        UUID playerId = player.getUniqueId();
        enforcementTeleports.add(playerId);
        try {
            player.teleport(target);
        } finally {
            enforcementTeleports.remove(playerId);
        }
        commitRegions(player, getRegionNamesAt(player.getLocation()));
    }

    public void handleQuit(Player player) {
        if (player != null) {
            regionsByPlayer.remove(player.getUniqueId());
        }
    }

    /**
     * Handle player join - pre-warm cache for current regions.
     */
    public RegionTransitionDecision handleJoin(Player player) {
        if (player == null || player.getLocation() == null) return null;

        UUID playerId = player.getUniqueId();
        Set<String> current = getRegionNamesAt(player.getLocation());

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " JOIN: initial regions=" + current);
        }

        regionsByPlayer.put(playerId, current);

        if (!current.isEmpty()) {
            // Start async pre-warm (don't block join)
            CacheStatus cacheStatus = checkCacheStatus(current);
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
                revalidateWhenResolved(player, List.of(startAsyncLookup(player, cacheStatus.missing)),
                    Collections.emptySet(), current, null, true);
            }
        }

        // Process transition if all data is cached
        if (current.isEmpty()) {
            return null;
        }

        RegionTransitionDecision decision = transitionService.handleRegionTransition(playerId, Collections.emptySet(), current);
        return decision;
    }

    private Set<String> getRegionNamesAt(Location bukkitLocation) {
        if (bukkitLocation == null || bukkitLocation.getWorld() == null) {
            return Collections.emptySet();
        }
        return regionLookup.apply(bukkitLocation);
    }

    /**
     * Check cache status for all region IDs.
     * Returns breakdown of fresh/stale/missing/in-flight regions.
     */
    private CacheStatus checkCacheStatus(Set<String> regionIds) {
        Set<String> fresh = new HashSet<>();
        Set<String> stale = new HashSet<>();
        Set<String> missing = new HashSet<>();
        Set<String> inFlight = new HashSet<>();

        for (String id : regionIds) {
            // Check if already being fetched
            if (inFlightLookups.containsKey(id)) {
                inFlight.add(id);
                continue;
            }

            // Check if recently failed (cooldown period)
            Long lastFailedTime = failedRegionLookups.get(id);
            if (lastFailedTime != null && System.currentTimeMillis() - lastFailedTime < FAILED_LOOKUP_COOLDOWN_MS) {
                // Treat failed lookups as "stale" data (allow movement but skip refetch)
                stale.add(id);
                continue;
            }

            // Check cache (without triggering background refresh)
            if (!regionResolver.getDomainByRegionIdNoRefresh(id).isPresent()) {
                missing.add(id);
            } else {
                // Domain exists in cache (fresh or stale, doesn't matter - we have data)
                fresh.add(id);
            }
        }

        return new CacheStatus(fresh, stale, missing, inFlight);
    }

    /**
     * Start an async lookup for regions missing from the cache. The returned future completes (never
     * exceptionally) once the lookup has finished, successfully or not. Each id is in flight until
     * then - and only until then, so a later move can fetch it again (KNG-55: before, a combined key
     * for multi-region lookups was never removed, and every later lookup of the same set was skipped
     * along with its re-validation). An id the lookup leaves without a domain - a WorldGuard region
     * no domain uses, or an API failure - goes on the failed-lookup cooldown, so the next moves are
     * judged from the cache instead of being waved through as "fetch in progress" again.
     */
    private CompletableFuture<Void> startAsyncLookup(Player player, Set<String> missingIds) {
        Set<String> ids = Set.copyOf(missingIds);
        CompletableFuture<Void> lookup = new CompletableFuture<>();
        for (String id : ids) {
            inFlightLookups.put(id, lookup);
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " starting async lookup for: " + ids);
        }

        try {
            lookupExecutor.execute(() -> {
                try {
                    regionResolver.resolveRegionsFromApi(ids).get();
                    if (logger != null) {
                        logger.fine("[KnK Tracker] " + player.getName() + " async lookup completed: " + ids);
                    }
                } catch (Exception ex) {
                    if (logger != null) {
                        logger.warning("[KnK Tracker] " + player.getName() + " async lookup FAILED: " + ex.getMessage());
                    }
                } finally {
                    finishLookup(ids, lookup);
                }
            });
        } catch (RuntimeException rejected) {
            finishLookup(ids, lookup);
        }
        return lookup;
    }

    private void finishLookup(Set<String> ids, CompletableFuture<Void> lookup) {
        Set<String> unresolved = new HashSet<>();
        for (String id : ids) {
            if (regionResolver.getDomainByRegionIdNoRefresh(id).isEmpty()) {
                unresolved.add(id);
            }
        }
        recordFailedLookup(unresolved);
        for (String id : ids) {
            inFlightLookups.remove(id, lookup);
        }
        lookup.complete(null);
    }

    /** Once every pending lookup has finished, re-validate the move on the main thread. */
    private void revalidateWhenResolved(Player player, List<CompletableFuture<Void>> pending, Set<String> oldRegions,
                                        Set<String> newRegions, Location returnTo, boolean forceRevalidation) {
        if (pending.isEmpty()) {
            return;
        }
        Location target = returnTo != null ? returnTo.clone() : null;
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, ex) -> mainThread.execute(
                () -> revalidatePlayerLocation(player, oldRegions, newRegions, target, forceRevalidation)));
    }

    /**
     * Re-validate player location after async API fetch completes.
     * Checks if player is still in the regions and enforces entry/exit rules.
     * MUST be called on main thread.
     *
     * <p>KNG-55: a player who walked on while the lookup ran is still checked. Before, re-validation
     * was skipped unless the player stood in exactly the regions of the move, so walking one step
     * further (into a nested region, say) kept them in a domain they may not enter. Now the move is
     * judged from where it started to where the player is now - side-effect free, since later moves
     * already ran their own transitions - and a refused player is put back where the move started.
     *
     * @param returnTo where the player was before the move ({@code from}); the world spawn if null
     * @param forceRevalidation if true (join-time pre-warm only), skips the "is the player still
     *     in the same spot" check and processes expectedNewRegions unconditionally - this exists
     *     purely to unblock one-time "entered" side effects (like district gate loading) for
     *     wherever the player joined, so entry/exit enforcement is intentionally NOT re-run here:
     *     retroactively teleporting a player away because a delayed pass resolved a deny-entry
     *     policy for their join spot, seconds after they've already been playing from wherever
     *     they walked to since, would be a surprising thing for the plugin to do on its own.
     */
    private void revalidatePlayerLocation(Player player, Set<String> oldRegions, Set<String> expectedNewRegions,
                                          Location returnTo, boolean forceRevalidation) {
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
            transitionService.handleRegionTransition(playerId, oldRegions, expectedNewRegions);
            return;
        }

        Set<String> currentRegions = getRegionNamesAt(player.getLocation());

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " revalidating: expected=" + expectedNewRegions + ", current=" + currentRegions);
        }

        RegionTransitionDecision decision;
        if (currentRegions.equals(expectedNewRegions)) {
            // Still where the move went: run the full transition with fresh data
            decision = transitionService.handleRegionTransition(playerId, oldRegions, currentRegions);
        } else if (currentRegions.equals(oldRegions)) {
            // Back where the move started: nothing to enforce
            return;
        } else {
            // Moved on during the fetch: only the access rules, from the move's start to here
            decision = transitionService.previewAccess(oldRegions, currentRegions);
            if (decision != null && decision.isMovementAllowed()) {
                decision = null;  // the later moves showed their own messages
            }
        }

        if (decision != null && !decision.isMovementAllowed() && bypassesDenials(player)) {
            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " entry denied after revalidation, but holds the region bypass");
            }
        } else if (decision != null && !decision.isMovementAllowed()) {
            // Movement should have been denied - put the player back where the move started
            Location target = returnTo != null && returnTo.getWorld() != null
                ? returnTo : player.getWorld().getSpawnLocation();
            if (logger != null) {
                logger.warning("[KnK Tracker] " + player.getName() + " movement denied after revalidation, teleporting back to " + target);
            }

            decision.getMessage().ifPresent(msg ->
                player.sendMessage(Component.text(msg).color(ColorOptions.error))
            );

            enforcementTeleport(player, target);
        } else if (decision != null) {
            // Entry allowed - show message
            decision.getMessage().ifPresent(msg ->
                player.sendActionBar(Component.text(msg).color(ColorOptions.message))
            );
        }
    }

    /** Record {@code newRegions} as the player's regions, firing enter/leave events for the difference. */
    private void commitRegions(Player player, Set<String> newRegions) {
        Set<String> oldRegions = regionsByPlayer.getOrDefault(player.getUniqueId(), Collections.emptySet());
        regionsByPlayer.put(player.getUniqueId(), newRegions);
        fireRegionEvents(player, oldRegions, newRegions);
    }

    /**
     * Fire region enter/leave events.
     */
    private void fireRegionEvents(Player player, Set<String> oldRegions, Set<String> newRegions) {
        Set<String> entered = new HashSet<>(newRegions);
        entered.removeAll(oldRegions);

        Set<String> left = new HashSet<>(oldRegions);
        left.removeAll(newRegions);

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

    private void recordFailedLookup(Set<String> regionIds) {
        long now = System.currentTimeMillis();
        for (String id : regionIds) {
            failedRegionLookups.put(id, now);
        }
    }

    /**
     * Helper record for cache status breakdown.
     */
    private record CacheStatus(
        Set<String> fresh,      // Cached and available
        Set<String> stale,      // Recently failed (cooldown)
        Set<String> missing,    // Not in cache at all
        Set<String> inFlight    // Currently being fetched
    ) {}
}
