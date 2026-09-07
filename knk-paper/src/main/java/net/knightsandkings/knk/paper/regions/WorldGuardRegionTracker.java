package net.knightsandkings.knk.paper.regions;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import net.kyori.adventure.text.Component;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.knightsandkings.knk.core.regions.RegionTransitionDecision;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionTransitionService;
import net.knightsandkings.knk.core.regions.RegionTransitionType;
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
 */
public class WorldGuardRegionTracker {
    private final RegionContainer regionContainer;
    private final RegionQuery regionQuery;
    private final RegionTransitionService transitionService;
    private final RegionDomainResolver regionResolver;
    private final Executor lookupExecutor;
    private final Logger logger;
    private final boolean enableConsoleLogging;
    private final Plugin plugin;

    private final Map<UUID, Set<String>> regionsByPlayer = new HashMap<>();
    private final Map<String, Long> failedRegionLookups = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> inFlightLookups = new ConcurrentHashMap<>();
    
    private static final long FAILED_LOOKUP_COOLDOWN_MS = 30000;  // 30 second cooldown

    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor, Plugin plugin, Logger logger, boolean enableConsoleLogging) {
        this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.regionQuery = regionContainer.createQuery();
        this.transitionService = transitionService;
        this.regionResolver = regionResolver;
        this.lookupExecutor = lookupExecutor;
        this.plugin = plugin;
        this.logger = logger;
        this.enableConsoleLogging = enableConsoleLogging;
    }

    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor, Plugin plugin) {
        this(transitionService, regionResolver, lookupExecutor, plugin, null, false);
    }

    @Deprecated
    public WorldGuardRegionTracker(RegionTransitionService transitionService, RegionDomainResolver regionResolver, Executor lookupExecutor) {
        this(transitionService, regionResolver, lookupExecutor, null, null, false);
    }

    /**
     * Handle player movement between WorldGuard regions.
     * 
     * @return RegionTransitionDecision if regions changed and all data is cached, null otherwise
     */
    public RegionTransitionDecision handleMove(Player player, Location from, Location to) {
        if (player == null || to == null) {
            return null;
        }

        UUID playerId = player.getUniqueId();
        Set<String> oldRegions = regionsByPlayer.getOrDefault(playerId, Collections.emptySet());
        Set<String> newRegions = getRegionNamesAt(to);

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " move: oldRegions=" + oldRegions + ", newRegions=" + newRegions);
        }

        // Fire region events if changed
        if (!oldRegions.equals(newRegions)) {
            fireRegionEvents(player, oldRegions, newRegions);
        }

        // No region change = no processing
        if (newRegions.equals(oldRegions)) {
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
            if (!cacheStatus.missing.isEmpty()) {
                startAsyncLookupWithRevalidation(player, cacheStatus.missing, oldRegions, newRegions, false);
            }
            
            // Update player regions and allow movement with stale/partial data
            regionsByPlayer.put(playerId, newRegions);
            
            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " allowing movement (fetch in progress)");
            }
            return null;
        }

        // All data cached and fresh - process transition
        regionsByPlayer.put(playerId, newRegions);
        
        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " processing transition (all data cached)");
        }

        RegionTransitionDecision decision = transitionService.handleRegionTransition(playerId, oldRegions, newRegions);
        
        if (logger != null && decision != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " decision: allowed=" + decision.isMovementAllowed() + 
                        ", message=" + decision.getMessage().orElse("(none)"));
        }
        
        return decision;
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
                startAsyncLookupWithRevalidation(player, cacheStatus.missing, Collections.emptySet(), current, true);
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

        com.sk89q.worldedit.util.Location wgLoc = BukkitAdapter.adapt(bukkitLocation);
        ApplicableRegionSet set = regionQuery.getApplicableRegions(wgLoc);

        Set<String> names = new HashSet<>();
        for (ProtectedRegion region : set) {
            names.add(region.getId());
        }
        
        return names;
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
     * Start async lookup for missing regions with queue-based re-validation.
     * After API fetch completes, re-validates player location on main thread.
     *
     * @param forceRevalidation if true, skips the "is the player still in the same spot" check
     *     and processes newRegions as-is once the fetch completes - used for join-time pre-warm,
     *     where we want the player's original regions treated as "entered" once resolved,
     *     regardless of whether they've since walked elsewhere (see handleJoin).
     */
    private void startAsyncLookupWithRevalidation(Player player, Set<String> missingIds, Set<String> oldRegions, Set<String> newRegions, boolean forceRevalidation) {
        if (missingIds.isEmpty()) {
            return;
        }

        // Create combined key for this exact set of regions
        String lookupKey = String.join(",", missingIds.stream().sorted().toList());
        
        // Check if this exact set is already being fetched
        if (inFlightLookups.containsKey(lookupKey)) {
            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " lookup already in-flight for: " + missingIds);
            }
            return;
        }

        if (logger != null) {
            logger.fine("[KnK Tracker] " + player.getName() + " starting async lookup for: " + missingIds);
        }

        // Mark all individual IDs as in-flight
        for (String id : missingIds) {
            inFlightLookups.putIfAbsent(id, new CompletableFuture<>());
        }

        UUID playerId = player.getUniqueId();
        
        CompletableFuture<Void> lookupFuture = CompletableFuture.runAsync(() -> {
            try {
                regionResolver.resolveRegionsFromApi(missingIds).get();
                if (logger != null) {
                    logger.fine("[KnK Tracker] " + player.getName() + " async lookup completed: " + missingIds);
                }
                
                // Schedule re-validation on main thread
                if (plugin != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        revalidatePlayerLocation(player, oldRegions, newRegions, forceRevalidation);
                    });
                }
                
                // Mark individual lookups as complete
                for (String id : missingIds) {
                    CompletableFuture<Void> idFuture = inFlightLookups.remove(id);
                    if (idFuture != null) {
                        idFuture.complete(null);
                    }
                }
            } catch (Exception ex) {
                if (logger != null) {
                    logger.warning("[KnK Tracker] " + player.getName() + " async lookup FAILED: " + ex.getMessage());
                }
                recordFailedLookup(missingIds);
                
                // Mark individual lookups as failed
                for (String id : missingIds) {
                    CompletableFuture<Void> idFuture = inFlightLookups.remove(id);
                    if (idFuture != null) {
                        idFuture.completeExceptionally(ex);
                    }
                }
            }
        }, lookupExecutor);

        inFlightLookups.put(lookupKey, lookupFuture);
    }

    /**
     * Re-validate player location after async API fetch completes.
     * Checks if player is still in the regions and enforces entry/exit rules.
     * MUST be called on main thread.
     *
     * @param forceRevalidation if true (join-time pre-warm only), skips the "is the player still
     *     in the same spot" check and processes expectedNewRegions unconditionally - this exists
     *     purely to unblock one-time "entered" side effects (like district gate loading) for
     *     wherever the player joined, so entry/exit enforcement is intentionally NOT re-run here:
     *     retroactively teleporting a player away because a delayed pass resolved a deny-entry
     *     policy for their join spot, seconds after they've already been playing from wherever
     *     they walked to since, would be a surprising thing for the plugin to do on its own.
     */
    private void revalidatePlayerLocation(Player player, Set<String> oldRegions, Set<String> expectedNewRegions, boolean forceRevalidation) {
        if (player == null || !player.isOnline()) {
            if (logger != null) {
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

        // Check if player is still in the expected regions
        if (!currentRegions.equals(expectedNewRegions)) {
            if (logger != null) {
                logger.fine("[KnK Tracker] " + player.getName() + " moved during fetch, skipping revalidation");
            }
            return;
        }

        // Re-run transition check with fresh data
        RegionTransitionDecision decision = transitionService.handleRegionTransition(playerId, oldRegions, currentRegions);

        if (decision != null && !decision.isMovementAllowed()) {
            // Movement should have been denied - teleport player back
            if (logger != null) {
                logger.warning("[KnK Tracker] " + player.getName() + " entry denied after revalidation, teleporting to spawn");
            }
            
            decision.getMessage().ifPresent(msg -> 
                player.sendMessage(org.bukkit.ChatColor.RED + msg)
            );
            
            // Teleport to world spawn
            Location spawnLocation = player.getWorld().getSpawnLocation();
            player.teleport(spawnLocation);
            
            // Fire exit events for regions player shouldn't be in
            for (String regionId : currentRegions) {
                Bukkit.getPluginManager().callEvent(new OnRegionLeaveEvent(player, regionId));
            }
            
            // Update tracked regions
            regionsByPlayer.put(playerId, Collections.emptySet());
        } else if (decision != null ) {
            // Entry allowed - show message
            decision.getMessage().ifPresent(msg -> 
                player.sendActionBar(Component.text(msg).color(ColorOptions.message))
            );
        }
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
            Bukkit.getPluginManager().callEvent(new OnRegionEnterEvent(player, regionId));
        }

        for (String regionId : left) {
            Bukkit.getPluginManager().callEvent(new OnRegionLeaveEvent(player, regionId));
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
