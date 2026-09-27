package net.knightsandkings.knk.paper.discovery;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import net.knightsandkings.knk.core.discovery.DiscoveryRecorder;
import net.knightsandkings.knk.core.discovery.DiscoveryTracker;
import net.knightsandkings.knk.core.discovery.PendingDiscovery;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;

/**
 * Detects the regions a player may be discovering (docs/specs/domain-discovery DESIGN.md §3.6),
 * straight from WorldGuard's region ids at the player's position - not from OnRegionEnterEvent,
 * which fires before an entry is allowed or denied:
 * <ul>
 *   <li><b>Move / teleport</b> (MONITOR, ignoring cancelled events, so a denied entry never counts):
 *   every region at the destination that the {@link DiscoveryTracker} doesn't already know about.
 *   Moves are only looked at when the player changed block.</li>
 *   <li><b>Join</b>: on {@link UserDataLoadedEvent} (account loaded, loading hold off) the player's
 *   discovered set is loaded and every region they joined inside becomes a JoinInside candidate. A
 *   player whose account couldn't be loaded (API down: no user id) is tracked under their UUID anyway;
 *   their candidates are sent once the user id is looked up, or spooled until then
 *   ({@link DiscoveryFlushTask}), and their known set loads when the id is known.</li>
 *   <li><b>Resync</b> ({@link #resync}): after a staff reset (web app or {@code /knk discovery reset})
 *   the known set is read again and the regions the player stands in are re-checked, so the reset
 *   place is discovered again without moving.</li>
 *   <li>Each candidate is <b>confirmed a tick later</b>: the player must still be in that region, so an
 *   entry undone right away (bounced back, teleported out) isn't discovered, while a player running
 *   through a small structure still is.</li>
 *   <li><b>Quit</b>: candidates not yet sent are spooled and replayed later; the session is dropped.</li>
 * </ul>
 * Only eligible players ({@link DiscoveryEligibility}) are looked at. Main thread.
 */
public final class DomainDiscoveryListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(DomainDiscoveryListener.class.getName());
    private static final String GLOBAL_REGION = "__global__";

    private final Plugin plugin;
    private final DiscoveryTracker tracker;
    private final DiscoveryRecorder recorder;
    private final DiscoveriesApi discoveriesApi;
    private final DiscoveryEligibility eligibility;
    private final DiscoveryFlushTask flushTask;
    private final Clock clock;
    private final RegionQuery regionQuery;

    /** Candidates waiting for next tick's confirmation, per player. */
    private final Map<UUID, Map<String, DiscoverySource>> toConfirm = new HashMap<>();

    public DomainDiscoveryListener(Plugin plugin, DiscoveryTracker tracker, DiscoveryRecorder recorder,
                                   DiscoveriesApi discoveriesApi, DiscoveryEligibility eligibility,
                                   DiscoveryFlushTask flushTask, Clock clock) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.recorder = recorder;
        this.discoveriesApi = discoveriesApi;
        this.eligibility = eligibility;
        this.flushTask = flushTask;
        this.clock = clock;
        this.regionQuery = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        flushTask.setUserResolvedHandler(this::loadKnown);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        detect(event.getPlayer(), to, DiscoverySource.REGION_ENTER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            detect(event.getPlayer(), event.getTo(), DiscoverySource.REGION_ENTER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Integer userId = event.getUserId();
        if (userId == null || userId <= 0) {
            // The account couldn't be loaded (API down): still track them, under their UUID.
            startUnresolved(event.getPlayer());
        } else {
            start(event.getPlayer(), userId);
        }
        flushTask.replayFor(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        toConfirm.remove(uuid);
        OptionalInt userId = tracker.userId(uuid);
        List<PendingDiscovery> pending = tracker.endSession(uuid);
        if (!pending.isEmpty()) {
            // Without a user id yet, spooled under the UUID; the replay looks the id up.
            recorder.spoolPending(uuid, userId.orElse(DiscoveryTracker.UNRESOLVED_USER), pending);
        }
    }

    /**
     * Players already online when discovery starts (a plugin reload): tracked from now on - under
     * their UUID when no user id is cached - with the regions they stand in as JoinInside candidates.
     */
    public void startOnlinePlayers(Function<UUID, Integer> cachedUserId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Integer userId = cachedUserId.apply(player.getUniqueId());
            if (userId != null && userId > 0) {
                start(player, userId);
            } else {
                startUnresolved(player);
            }
        }
    }

    /**
     * Re-syncs an online player after one of their discoveries was reset (the web app's reset arrives
     * as a DiscoveryReset player notification, {@code /knk discovery reset} calls it directly): reads
     * their known set again (off the main thread), replaces the tracker's, then re-checks the regions
     * they stand in (JoinInside, eligibility and next-tick confirmation as usual), so standing still
     * inside the reset place discovers it again. A second resync for the same reset (the command's own,
     * then its notification) finds nothing new to do: the server grants a place at most once. No-op
     * for a player who isn't tracked or whose user id isn't known yet (their known set loads then).
     * Main thread.
     */
    public void resync(Player player) {
        UUID uuid = player.getUniqueId();
        OptionalInt tracked = tracker.userId(uuid);
        if (tracked.isEmpty()) {
            return;
        }
        int userId = tracked.getAsInt();
        discoveriesApi.known(userId).whenComplete((known, error) -> runOnMainThread(() -> {
            if (error != null || known == null) {
                LOGGER.log(Level.WARNING, "[Discovery] Could not re-read the discoveries of user " + userId
                        + " after a reset; they count again after a rejoin", error);
                return;
            }
            if (tracker.userId(uuid).orElse(-1) != userId) {
                return; // quit meanwhile
            }
            tracker.replaceKnown(uuid, known);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                detect(online, online.getLocation(), DiscoverySource.JOIN_INSIDE);
            }
        }));
    }

    private void start(Player player, int userId) {
        UUID uuid = player.getUniqueId();
        tracker.startSession(uuid, userId);
        loadKnown(uuid, userId);
        detect(player, player.getLocation(), DiscoverySource.JOIN_INSIDE);
    }

    /**
     * A player without a knk user id (the API was down at join): tracked under their UUID with the
     * known set unavailable, so what they find is queued, then sent once the id is looked up or
     * spooled until it can be.
     */
    private void startUnresolved(Player player) {
        LOGGER.info("[Discovery] " + player.getName() + " has no user id yet (API unreachable at join); "
                + "tracking their discoveries until it can be looked up");
        tracker.startUnresolvedSession(player.getUniqueId());
        detect(player, player.getLocation(), DiscoverySource.JOIN_INSIDE);
    }

    /** Loads the player's discovered set (join, or once an unresolved player's user id is known). */
    private void loadKnown(UUID uuid, int userId) {
        discoveriesApi.known(userId).whenComplete((known, error) -> runOnMainThread(() -> {
            if (error != null || known == null) {
                LOGGER.log(Level.WARNING, "[Discovery] Could not load the discoveries of user " + userId
                        + "; sending candidates without the known-set filter", error);
                tracker.knownLoadFailed(uuid);
            } else {
                tracker.knownLoaded(uuid, known);
            }
        }));
    }

    private void detect(Player player, Location location, DiscoverySource source) {
        UUID uuid = player.getUniqueId();
        if (!tracker.hasSession(uuid) || !eligibility.isEligible(player)) {
            return;
        }
        Instant now = clock.instant();
        Map<String, DiscoverySource> waiting = null;
        for (String regionId : regionIdsAt(location)) {
            if (!tracker.isCandidate(uuid, regionId, now)) {
                continue;
            }
            if (waiting == null) {
                waiting = toConfirm.get(uuid);
                if (waiting == null) {
                    waiting = new LinkedHashMap<>();
                    toConfirm.put(uuid, waiting);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> confirm(uuid), 1L);
                }
            }
            waiting.putIfAbsent(regionId, source);
        }
    }

    /** Next tick: queue the candidates the player is still inside of. */
    private void confirm(UUID uuid) {
        Map<String, DiscoverySource> waiting = toConfirm.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (waiting == null || player == null || !eligibility.isEligible(player)) {
            return;
        }
        Set<String> current = new HashSet<>();
        regionIdsAt(player.getLocation()).forEach(id -> current.add(PendingDiscovery.key(id)));
        Instant now = clock.instant();
        waiting.forEach((regionId, source) -> {
            if (current.contains(PendingDiscovery.key(regionId)) && tracker.offer(uuid, regionId, source, now)) {
                LOGGER.fine("[Discovery] " + player.getName() + " candidate " + regionId + " (" + source.apiName() + ")");
            }
        });
    }

    private Set<String> regionIdsAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return Set.of();
        }
        ApplicableRegionSet regions = regionQuery.getApplicableRegions(BukkitAdapter.adapt(location));
        Set<String> ids = new HashSet<>();
        for (ProtectedRegion region : regions) {
            if (!GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
                ids.add(region.getId());
            }
        }
        return ids;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
