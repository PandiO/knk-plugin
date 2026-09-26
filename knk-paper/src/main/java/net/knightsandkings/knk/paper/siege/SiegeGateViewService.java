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
 * Siege Phase 7b, reworked after the 2026-09-26 smoke test ("non-siege players should go about their
 * business with the least trouble"): for non-members every locked-down siege gate is <b>removed</b>,
 * with per-player block changes ({@code Player.sendBlockChange}, Paper API only). A non-member within
 * {@link #VIEW_RANGE} blocks gets every cell of the door's closed and open resting frames as air.
 * <ul>
 *   <li><b>Walking through:</b> the server still collides with really solid blocks (per-player air
 *   alone can't make them passable - GatePassThroughService's javadoc), so a non-member walking up to a
 *   door that is really closed is carried across it with the gate's TELEPORT pass-through
 *   ({@code SiegeGateController.carryNonMemberThrough}), at most once per {@link #CARRY_COOLDOWN_MS}.</li>
 *   <li><b>Re-send:</b> every {@link #PERIOD_TICKS} ticks, per (viewer, door) only when the door's real
 *   state or frame changed since the last send, or the viewer is new in range; teleport, respawn, join
 *   and world change force a re-send. (Animation frames overwrite the fakes until the next pass - a
 *   brief flicker, accepted.)</li>
 *   <li><b>Degrade switch:</b> {@code SiegeConfiguration.NonMemberGateView = PassThroughOnly} turns this
 *   off (non-members see and collide with the real siege gates; right-clicking a door that was open
 *   before the lockdown still carries them across).</li>
 * </ul>
 * Members never get fakes. When a lockdown ends, everyone who got fakes is sent the real blocks again
 * once the restore animations had time to finish.
 */
public final class SiegeGateViewService extends BukkitRunnable implements Listener {

    static final int PERIOD_TICKS = 5;
    static final double VIEW_RANGE = 96.0;
    /** After a lockdown ends: resend the real blocks this much later (restore animations finish first). */
    static final long CLEAR_DELAY_TICKS = 100L;
    /** A non-member is carried across a door at most this often (no double trigger around the teleport). */
    static final long CARRY_COOLDOWN_MS = 1000L;
    /** How far ahead of a walking non-member a really solid (but hidden) door cell triggers the carry. */
    static final double CARRY_PROBE_DISTANCE = 0.8;

    private record DoorCells(List<ViewCell> closed, List<ViewCell> open) {}

    /** @param hidden cells sent as air; @param solid cells that are really solid now (packed positions) */
    private record DoorView(SiegeGateController.LockedDoor locked, CachedGateDoor door, List<ViewCell> hidden,
                            Set<Long> solid, long version) {}

    /** A really solid, hidden door cell: whose lobby it belongs to and which door. */
    private record SolidCell(SiegeLobbyRuntime lobby, int doorId) {}

    private final Plugin plugin;
    private final SiegeGateController gates;
    private final GateManager gateManager;
    private final boolean rasterizationEnabled;

    private final Map<Integer, DoorCells> cellCache = new HashMap<>();
    /** viewer → (doorId → version last sent) */
    private final Map<UUID, Map<Integer, Long>> sent = new HashMap<>();
    /** viewer → (doorId → every position faked for them), to put the real blocks back. */
    private final Map<UUID, Map<Integer, Set<Location>>> faked = new HashMap<>();
    /** world name → really solid hidden door cells of the current pass. */
    private final Map<String, Map<Long, SolidCell>> solidHidden = new HashMap<>();
    /** viewer → when they were last carried across a door. */
    private final Map<UUID, Long> lastCarried = new HashMap<>();
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
        Map<String, Map<Long, SolidCell>> solids = new HashMap<>();
        for (SiegeGateController.LockedDoor locked : gates.lockedDoors()) {
            doorIds.add(locked.doorId());
            if (locked.viewMode() != SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW) continue;
            CachedGateDoor door = gateManager.getGate(locked.doorId());
            if (door == null || door.getWorldName() == null) continue;
            DoorView view = viewOf(locked, door);
            if (view == null) continue;
            views.add(view);
            Map<Long, SolidCell> worldSolids = solids.computeIfAbsent(door.getWorldName(), w -> new HashMap<>());
            SolidCell ref = new SolidCell(locked.lobby(), door.getId());
            view.solid().forEach(cell -> worldSolids.put(cell, ref));
        }
        solidHidden.clear();
        solidHidden.putAll(solids);

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

    /**
     * The removed view of a door: all its closed- and open-frame cells as air (so mid-animation cells
     * are mostly covered too), and which of them are really solid now. Null when it's destroyed (then
     * there is nothing to hide).
     */
    private DoorView viewOf(SiegeGateController.LockedDoor locked, CachedGateDoor door) {
        if (door.isEffectivelyDestroyed()) return null;
        AnimationState state = door.getCurrentState();
        DoorCells cells = cellCache.computeIfAbsent(door.getId(), idKey -> new DoorCells(
                GateViewCells.restingCells(door, false, rasterizationEnabled),
                GateViewCells.restingCells(door, true, rasterizationEnabled)));
        Map<Long, ViewCell> all = new java.util.LinkedHashMap<>();
        cells.closed().forEach(c -> all.put(GateSpatialIndex.packCell(c.position()), c));
        cells.open().forEach(c -> all.putIfAbsent(GateSpatialIndex.packCell(c.position()), c));
        Set<Long> solid = new HashSet<>();
        switch (state) {
            case CLOSED -> cells.closed().forEach(c -> solid.add(GateSpatialIndex.packCell(c.position())));
            case OPEN -> cells.open().forEach(c -> solid.add(GateSpatialIndex.packCell(c.position())));
            default -> solid.addAll(all.keySet()); // animating: any frame cell may be solid right now
        }
        long version = ((long) state.ordinal() << 40) ^ ((long) door.getCurrentFrame() << 8);
        return new DoorView(locked, door, List.copyOf(all.values()), solid, version);
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

    /**
     * Walking through: a non-member heading into a really solid door cell they see as air is carried
     * across (the server would reject the move into the block). Probes {@link #CARRY_PROBE_DISTANCE}
     * ahead of the move at feet and head height.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (solidHidden.isEmpty()) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null) return;
        Map<Long, SolidCell> cells = solidHidden.get(to.getWorld().getName());
        if (cells == null) return;
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1e-4) return;
        double px = to.getX() + dx / length * CARRY_PROBE_DISTANCE;
        double pz = to.getZ() + dz / length * CARRY_PROBE_DISTANCE;
        int bx = (int) Math.floor(px);
        int bz = (int) Math.floor(pz);
        int by = to.getBlockY();
        SolidCell cell = cells.get(GateSpatialIndex.packCell(bx, by, bz));
        if (cell == null) cell = cells.get(GateSpatialIndex.packCell(bx, by + 1, bz));
        if (cell == null) return;
        Player player = event.getPlayer();
        if (cell.lobby().isMember(player.getUniqueId())) return;
        long now = System.currentTimeMillis();
        Long last = lastCarried.get(player.getUniqueId());
        if (last != null && now - last < CARRY_COOLDOWN_MS) return;
        CachedGateDoor door = gateManager.getGate(cell.doorId());
        if (door == null) return;
        lastCarried.put(player.getUniqueId(), now);
        gates.carryNonMemberThrough(player, door);
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
        lastCarried.remove(event.getPlayer().getUniqueId());
    }
}
