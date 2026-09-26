package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.siege.SiegeNonMemberGateView;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.paper.gates.GateViewCells;
import net.knightsandkings.knk.paper.gates.GateViewCells.ViewCell;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Siege Phase 7b (DESIGN §8.5, D6): non-members see every locked-down gate as it was before the
 * lockdown, with per-player block changes ({@code Player.sendBlockChange}, Paper API only). For each
 * door whose real state differs from its pre-lockdown state, a non-member within {@link #VIEW_RANGE}
 * blocks gets the pre-lockdown resting-frame blocks, and the real frame's other cells as air.
 * <ul>
 *   <li><b>Re-send:</b> every {@link #PERIOD_TICKS} ticks, per (viewer, door) only when the door's real
 *   state or frame changed since the last send, or the viewer is new in range; teleport, respawn, join
 *   and world change force a re-send. (Animation frames overwrite the fakes until the next pass - a
 *   brief flicker, accepted; no gate-package hooks needed.)</li>
 *   <li><b>Virtual collision:</b> a non-member can't step into a cell that looks solid (pre-closed door)
 *   but is passable in reality (the door is open or destroyed now).</li>
 *   <li><b>Pre-open / real-closed:</b> handled by {@code SiegeGateController.tryNonMemberPassThrough}
 *   (right-click → TELEPORT pass-through).</li>
 *   <li><b>Degrade switch:</b> {@code SiegeConfiguration.NonMemberGateView = PassThroughOnly} turns the
 *   view and the collision off (non-members see the siege state; the pass-through stays).</li>
 * </ul>
 * Members never get fakes. When a lockdown ends, everyone who got fakes is sent the real blocks again
 * once the restore animations had time to finish.
 */
public final class SiegeGateViewService extends BukkitRunnable implements Listener {

    static final int PERIOD_TICKS = 5;
    static final double VIEW_RANGE = 96.0;
    /** After a lockdown ends: resend the real blocks this much later (restore animations finish first). */
    static final long CLEAR_DELAY_TICKS = 100L;

    private record DoorCells(List<ViewCell> closed, List<ViewCell> open) {}

    private record DoorView(SiegeGateController.LockedDoor locked, CachedGateDoor door, List<ViewCell> view,
                            List<ViewCell> hidden, Set<Long> ghostSolid, long version) {}

    private final Plugin plugin;
    private final SiegeGateController gates;
    private final GateManager gateManager;
    private final boolean rasterizationEnabled;

    private final Map<Integer, DoorCells> cellCache = new HashMap<>();
    /** viewer → (doorId → version last sent) */
    private final Map<UUID, Map<Integer, Long>> sent = new HashMap<>();
    /** viewer → (doorId → every position faked for them), to put the real blocks back. */
    private final Map<UUID, Map<Integer, Set<Location>>> faked = new HashMap<>();
    /** world name → ghost-solid cells of the current pass, with the lobby they belong to. */
    private final Map<String, Map<Long, SiegeLobbyRuntime>> ghostSolid = new HashMap<>();
    private Set<Integer> lastDoorIds = Set.of();

    public SiegeGateViewService(Plugin plugin, SiegeGateController gates, GateManager gateManager, boolean rasterizationEnabled) {
        this.plugin = plugin;
        this.gates = gates;
        this.gateManager = gateManager;
        this.rasterizationEnabled = rasterizationEnabled;
    }

    public void start() {
        runTaskTimer(plugin, PERIOD_TICKS, PERIOD_TICKS);
    }

    // ==================== periodic pass ====================

    @Override
    public void run() {
        try {
            pass();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[Siege] Non-member gate view pass failed", e);
        }
    }

    private void pass() {
        List<DoorView> views = new ArrayList<>();
        Set<Integer> doorIds = new HashSet<>();
        Map<String, Map<Long, SiegeLobbyRuntime>> ghosts = new HashMap<>();
        for (SiegeGateController.LockedDoor locked : gates.lockedDoors()) {
            doorIds.add(locked.doorId());
            if (locked.viewMode() != SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW) continue;
            CachedGateDoor door = gateManager.getGate(locked.doorId());
            if (door == null || door.getWorldName() == null) continue;
            DoorView view = viewOf(locked, door);
            if (view == null) continue;
            views.add(view);
            Map<Long, SiegeLobbyRuntime> worldGhosts = ghosts.computeIfAbsent(door.getWorldName(), w -> new HashMap<>());
            view.ghostSolid().forEach(cell -> worldGhosts.put(cell, locked.lobby()));
        }
        ghostSolid.clear();
        ghostSolid.putAll(ghosts);

        // Doors whose lockdown ended since the last pass: put the real blocks back (delayed).
        Set<Integer> ended = new HashSet<>(lastDoorIds);
        ended.removeAll(doorIds);
        if (!ended.isEmpty()) scheduleClear(ended);
        lastDoorIds = doorIds;
        if (views.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            for (DoorView view : views) {
                if (view.locked().lobby().isMember(id) || !inRange(player, view.door())) {
                    forget(id, view.door().getId());
                    continue;
                }
                Map<Integer, Long> versions = sent.computeIfAbsent(id, k -> new HashMap<>());
                Long last = versions.get(view.door().getId());
                if (last != null && last == view.version()) continue;
                send(player, view);
                versions.put(view.door().getId(), view.version());
            }
        }
    }

    /** The pre-lockdown view of a door, or null when it looks the same as in reality. */
    private DoorView viewOf(SiegeGateController.LockedDoor locked, CachedGateDoor door) {
        boolean realDestroyed = door.isEffectivelyDestroyed();
        AnimationState state = door.getCurrentState();
        boolean realOpen = state == AnimationState.OPEN || state == AnimationState.OPENING;
        boolean animating = state == AnimationState.OPENING || state == AnimationState.CLOSING;
        if (!animating && realDestroyed == locked.preDestroyed() && (realDestroyed || realOpen == locked.preOpened())) {
            return null;
        }
        DoorCells cells = cellCache.computeIfAbsent(door.getId(), idKey -> new DoorCells(
                GateViewCells.restingCells(door, false, rasterizationEnabled),
                GateViewCells.restingCells(door, true, rasterizationEnabled)));
        List<ViewCell> view = locked.preDestroyed() ? List.of() : (locked.preOpened() ? cells.open() : cells.closed());
        List<ViewCell> real = realDestroyed ? List.of() : (realOpen ? cells.open() : cells.closed());
        Set<Long> viewPositions = new HashSet<>();
        view.forEach(c -> viewPositions.add(GateSpatialIndex.packCell(c.position())));
        Set<Long> realPositions = new HashSet<>();
        real.forEach(c -> realPositions.add(GateSpatialIndex.packCell(c.position())));
        List<ViewCell> hidden = real.stream().filter(c -> !viewPositions.contains(GateSpatialIndex.packCell(c.position()))).toList();
        Set<Long> ghost = new HashSet<>(viewPositions);
        ghost.removeAll(realPositions);
        long version = ((long) state.ordinal() << 40) ^ ((long) door.getCurrentFrame() << 8) ^ (realDestroyed ? 1 : 0);
        return new DoorView(locked, door, view, hidden, ghost, version);
    }

    private void send(Player player, DoorView view) {
        World world = player.getWorld();
        if (!world.getName().equals(view.door().getWorldName())) return;
        Set<Location> positions = faked.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(view.door().getId(), k -> new HashSet<>());
        BlockData air = Material.AIR.createBlockData();
        for (ViewCell cell : view.hidden()) {
            Location location = cell.position().toLocation(world);
            player.sendBlockChange(location, air);
            positions.add(location);
        }
        for (ViewCell cell : view.view()) {
            Location location = cell.position().toLocation(world);
            player.sendBlockChange(location, GateViewCells.blockData(cell.blockData(), Material.STONE));
            positions.add(location);
        }
    }

    private boolean inRange(Player player, CachedGateDoor door) {
        if (door.getAnchorPoint() == null || !player.getWorld().getName().equals(door.getWorldName())) return false;
        return player.getLocation().toVector().distanceSquared(door.getAnchorPoint()) <= VIEW_RANGE * VIEW_RANGE;
    }

    /** Drops what was sent for this door, so re-entering range re-sends it. */
    private void forget(UUID playerId, int doorId) {
        Map<Integer, Long> versions = sent.get(playerId);
        if (versions != null) versions.remove(doorId);
    }

    private void scheduleClear(Set<Integer> doorIds) {
        cellCache.keySet().removeAll(doorIds);
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Map.Entry<UUID, Map<Integer, Set<Location>>> entry : faked.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                for (Integer doorId : doorIds) {
                    Set<Location> positions = entry.getValue().remove(doorId);
                    if (player == null || positions == null) continue;
                    for (Location location : positions) {
                        if (location.getWorld() != null && location.getWorld().equals(player.getWorld())) {
                            player.sendBlockChange(location, location.getBlock().getBlockData());
                        }
                    }
                }
                Map<Integer, Long> versions = sent.get(entry.getKey());
                if (versions != null) versions.keySet().removeAll(doorIds);
            }
        }, CLEAR_DELAY_TICKS);
    }

    // ==================== listeners ====================

    /** Virtual collision: no stepping into a cell that looks like a closed door but is open in reality. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (ghostSolid.isEmpty()) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Map<Long, SiegeLobbyRuntime> cells = ghostSolid.get(to.getWorld().getName());
        if (cells == null) return;
        SiegeLobbyRuntime feet = cells.get(GateSpatialIndex.packCell(to.getBlockX(), to.getBlockY(), to.getBlockZ()));
        SiegeLobbyRuntime head = cells.get(GateSpatialIndex.packCell(to.getBlockX(), to.getBlockY() + 1, to.getBlockZ()));
        SiegeLobbyRuntime lobby = feet != null ? feet : head;
        if (lobby != null && !lobby.isMember(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        faked.remove(event.getPlayer().getUniqueId());
    }
}
