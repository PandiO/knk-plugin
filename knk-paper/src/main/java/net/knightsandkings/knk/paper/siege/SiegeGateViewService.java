package net.knightsandkings.knk.paper.siege;

import io.papermc.paper.math.Position;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.siege.SiegeNonMemberGateView;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.core.siege.SiegePreGameGate;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Click;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Pre;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Real;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.View;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.gates.GateDisplayManager;
import net.knightsandkings.knk.paper.gates.GateDisplayManager.HoverState;
import net.knightsandkings.knk.paper.gates.GateViewCells;
import net.knightsandkings.knk.paper.gates.GateViewCells.ViewCell;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.logging.Level;

/**
 * Siege DESIGN §8.5, redesigned 2026-11-02 (PandiO/knk-workspace#213): for players who are not in the match,
 * <b>every gate in the siege area keeps its pre-game state</b>. The real world keeps the siege state; this
 * service shows non-members the pre-game doors and keeps the per-pass state the other parts read:
 * <ul>
 *   <li><b>View</b> (#214): per (viewer, door) fake blocks ({@code Player.sendMultiBlockChange}) after
 *   {@link SiegePreGameGate#viewOf}: a door closed before the match but open or destroyed now gets its closed
 *   frame; a door open before the match but closed now is shown as air, and walking into it carries the player
 *   across with the gate's TELEPORT pass-through. Re-sent every {@link #PERIOD_TICKS} ticks when the door changed
 *   or the viewer is new in range, and every {@link #CLOSE_REFRESH_TICKS} ticks to viewers close by (a click or a
 *   dig on a fake block makes the client drop it). Animation frames overwrite the fakes until the next pass - a
 *   brief flicker, accepted.</li>
 *   <li><b>Blocked cells</b> (#215): the closed frame of every locked door that was closed before the match, read
 *   by {@link SiegeGateCrossingGuard} - the server-side check that actually keeps non-members out, whatever the
 *   fake blocks do. They apply in both {@link SiegeNonMemberGateView} modes.</li>
 *   <li><b>Per-player opening</b> (#216): a non-member allowed to pass the door under the pre-game rules who
 *   right-clicks a pre-game-closed door that is really open gets the real blocks and a crossing allowance for
 *   the door's pass-through time (kept while they still stand in the frame).</li>
 *   <li><b>Hover</b> (#217): the pre-game hover text per door for {@link SiegeGateHoverPackets} (PacketEvents),
 *   with the viewers it applies to; a viewer who starts or stops getting it has the display re-sent.</li>
 * </ul>
 * Members never get fakes. {@code NonMemberGateView = PassThroughOnly}: no fakes and no hover; the crossing check
 * and the click rules stay. When a lockdown ends, everyone who got fakes is sent the real blocks again once the
 * restore animations had time to finish. Main thread only.
 */
public final class SiegeGateViewService extends BukkitRunnable implements Listener {

    static final int PERIOD_TICKS = 5;
    static final double VIEW_RANGE = 96.0;
    /** Viewers this close get their fakes re-sent every {@link #CLOSE_REFRESH_TICKS} ticks anyway. */
    static final double CLOSE_RANGE = 12.0;
    static final int CLOSE_REFRESH_TICKS = 20;
    /** After a lockdown ends: resend the real blocks this much later (restore animations finish first). */
    static final long CLEAR_DELAY_TICKS = 100L;
    /** A non-member is carried across a door at most this often (no double trigger around the teleport). */
    static final long CARRY_COOLDOWN_MS = 1000L;
    /** How far ahead of a walking non-member a really solid (but hidden) door cell triggers the carry. */
    static final double CARRY_PROBE_DISTANCE = 0.8;
    /** How far a right-click reaches when looking for a fake door cell, and the step of that search. */
    static final double CLICK_REACH = 4.5;
    static final double CLICK_STEP = 0.05;
    /** An allowance that ran out is kept this much longer while the player still stands in the frame. */
    static final long ALLOWANCE_GRACE_MS = 1000L;

    /** A door's resting frames: cells and their packed positions. */
    private record DoorCells(List<ViewCell> closed, List<ViewCell> open, Set<Long> closedKeys, Set<Long> openKeys) {}

    /** What one door currently means for non-members: its fake blocks (packed position → block data). */
    private record DoorView(SiegeGateController.LockedDoor locked, CachedGateDoor door, Map<Long, BlockData> fakes,
                            long version) {}

    /** A cell of a door that blocks non-members, or that carries them across: whose lobby and which door. */
    record DoorCell(SiegeLobbyRuntime lobby, int doorId) {}

    private final Plugin plugin;
    private final SiegeGateController gates;
    private final GateManager gateManager;
    private final boolean rasterizationEnabled;
    private GateDisplayManager displays;
    private SiegeGateHoverPackets hover;

    private final Map<Integer, DoorCells> cellCache = new HashMap<>();
    /** viewer → (doorId → version last sent) */
    private final Map<UUID, Map<Integer, Long>> sent = new HashMap<>();
    /** viewer → (doorId → every position faked for them), to put the real blocks back. */
    private final Map<UUID, Map<Integer, Set<Long>>> faked = new HashMap<>();
    /** world name → really solid hidden cells of doors that carry non-members across. */
    private final Map<String, Map<Long, DoorCell>> carryCells = new HashMap<>();
    /** world name → pre-game closed-frame cells (the crossing check). */
    private final Map<String, Map<Long, DoorCell>> blockedCells = new HashMap<>();
    /** doorId → its pre-game closed-frame cells, for allowances. */
    private final Map<Integer, Set<Long>> blockedByDoor = new HashMap<>();
    /** viewer → (doorId → per-player opening valid until, epoch ms). */
    private final Map<UUID, Map<Integer, Long>> allowances = new HashMap<>();
    /** viewer → when they were last carried across a door. */
    private final Map<UUID, Long> lastCarried = new HashMap<>();
    /** viewer → doors whose pre-game hover they got in the last pass. */
    private final Map<UUID, Set<Integer>> hoverViewers = new HashMap<>();
    private Set<Integer> lastDoorIds = Set.of();
    private int tick;

    public SiegeGateViewService(Plugin plugin, SiegeGateController gates, GateManager gateManager, boolean rasterizationEnabled) {
        this.plugin = plugin;
        this.gates = gates;
        this.gateManager = gateManager;
        this.rasterizationEnabled = rasterizationEnabled;
    }

    /** #217: the gate hovers and the packet rewriter; either may be null (then non-members see the live hover). */
    public void setHover(GateDisplayManager displays, SiegeGateHoverPackets hover) {
        this.displays = displays;
        this.hover = hover;
    }

    public void start() {
        runTaskTimer(plugin, PERIOD_TICKS, PERIOD_TICKS);
    }

    // ==================== read by SiegeGateCrossingGuard ====================

    /** True while some lockdown holds a door that was closed before the match. */
    boolean hasBlockedCells() {
        return !blockedCells.isEmpty();
    }

    /** True while some lockdown holds a door in this world that was closed before the match. */
    boolean hasBlockedCells(World world) {
        Map<Long, DoorCell> cells = blockedCells.get(world.getName());
        return cells != null && !cells.isEmpty();
    }

    /**
     * The cells this player may not enter in this world: pre-game closed frames of lockdowns they aren't a member
     * of, minus doors opened for them.
     */
    LongPredicate blockedFor(Player player, World world) {
        Map<Long, DoorCell> cells = blockedCells.get(world.getName());
        if (cells == null || cells.isEmpty()) return cell -> false;
        UUID id = player.getUniqueId();
        Map<Integer, Long> allowed = allowances.getOrDefault(id, Map.of());
        long now = System.currentTimeMillis();
        return cell -> {
            DoorCell door = cells.get(cell);
            if (door == null || door.lobby().isMember(id)) return false;
            Long until = allowed.get(door.doorId());
            return until == null || until < now;
        };
    }

    // ==================== periodic pass ====================

    @Override
    public void run() {
        try {
            tick += PERIOD_TICKS;
            pass();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "[Siege] Non-member gate view pass failed", e);
        }
    }

    private void pass() {
        Map<Integer, DoorView> views = new HashMap<>();
        Map<String, Map<Long, DoorCell>> carry = new HashMap<>();
        Map<String, Map<Long, DoorCell>> blocked = new HashMap<>();
        Map<Integer, Set<Long>> blockedDoors = new HashMap<>();
        Map<Integer, Component> preGameHover = new HashMap<>();
        Set<Integer> doorIds = new HashSet<>();
        for (SiegeGateController.LockedDoor locked : gates.lockedDoors()) {
            doorIds.add(locked.doorId());
            CachedGateDoor door = gateManager.getGate(locked.doorId());
            if (door == null || door.getWorldName() == null) continue;
            DoorCells cells = cellsOf(door);
            Pre pre = locked.pre();
            Real real = SiegeGateController.realStateOf(door);
            DoorCell ref = new DoorCell(locked.lobby(), door.getId());
            if (SiegePreGameGate.blocks(pre)) {
                Map<Long, DoorCell> worldCells = blocked.computeIfAbsent(door.getWorldName(), w -> new HashMap<>());
                cells.closedKeys().forEach(cell -> worldCells.put(cell, ref));
                blockedDoors.put(door.getId(), cells.closedKeys());
            }
            if (locked.viewMode() != SiegeNonMemberGateView.PRE_LOCKDOWN_VIEW) continue;
            View view = SiegePreGameGate.viewOf(pre, real);
            Map<Long, BlockData> fakes = fakesOf(cells, view);
            views.put(door.getId(), new DoorView(locked, door, fakes, versionOf(door)));
            if (view.carryOnSolid()) {
                Map<Long, DoorCell> worldCells = carry.computeIfAbsent(door.getWorldName(), w -> new HashMap<>());
                for (Long cell : solidHidden(cells, fakes, real)) worldCells.put(cell, ref);
            }
            preGameHover.put(door.getId(), GateDisplayManager.buildDisplayText(HoverState.preGame(door,
                    locked.preOpened(), locked.preDestroyed(), locked.preHealth(), locked.preInvincible())));
        }
        carryCells.clear();
        carryCells.putAll(carry);
        blockedCells.clear();
        blockedCells.putAll(blocked);
        blockedByDoor.clear();
        blockedByDoor.putAll(blockedDoors);

        // Doors whose lockdown ended since the last pass: put the real blocks back (delayed).
        Set<Integer> ended = new HashSet<>(lastDoorIds);
        ended.removeAll(doorIds);
        if (!ended.isEmpty()) scheduleClear(ended);
        lastDoorIds = doorIds;

        expireAllowances();
        boolean refreshClose = tick % CLOSE_REFRESH_TICKS == 0;
        Map<Integer, Set<UUID>> hoverTargets = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Set<Integer> hoverNow = new HashSet<>();
            for (DoorView view : views.values()) {
                int doorId = view.door().getId();
                double distanceSq = distanceSq(player, view.door());
                if (view.locked().lobby().isMember(id) || distanceSq > VIEW_RANGE * VIEW_RANGE) {
                    restoreReal(player, doorId);
                    forget(id, doorId);
                    continue;
                }
                hoverNow.add(doorId);
                hoverTargets.computeIfAbsent(doorId, k -> new HashSet<>()).add(id);
                if (isAllowed(id, doorId) || view.fakes().isEmpty()) {
                    restoreReal(player, doorId);
                    forget(id, doorId);
                    continue;
                }
                Map<Integer, Long> versions = sent.computeIfAbsent(id, k -> new HashMap<>());
                Long last = versions.get(doorId);
                boolean close = distanceSq <= CLOSE_RANGE * CLOSE_RANGE;
                if (last != null && last == view.version() && !(close && refreshClose)) continue;
                send(player, view);
                versions.put(doorId, view.version());
            }
            Set<Integer> hoverBefore = hoverViewers.getOrDefault(id, Set.of());
            if (!hoverBefore.equals(hoverNow)) {
                Set<Integer> changed = new HashSet<>(hoverBefore);
                changed.addAll(hoverNow);
                changed.removeIf(d -> hoverBefore.contains(d) && hoverNow.contains(d));
                pendingHoverRefresh.put(id, changed);
            }
            if (hoverNow.isEmpty()) hoverViewers.remove(id);
            else hoverViewers.put(id, hoverNow);
        }
        // Publish first, then re-send the displays whose override changed for a viewer, so the new text applies.
        publishHover(preGameHover, hoverTargets);
        pendingHoverRefresh.forEach((id, doors) -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) refreshHover(player, doors);
        });
        pendingHoverRefresh.clear();
    }

    private final Map<UUID, Set<Integer>> pendingHoverRefresh = new HashMap<>();

    private DoorCells cellsOf(CachedGateDoor door) {
        return cellCache.computeIfAbsent(door.getId(), idKey -> {
            List<ViewCell> closed = GateViewCells.restingCells(door, false, rasterizationEnabled);
            List<ViewCell> open = GateViewCells.restingCells(door, true, rasterizationEnabled);
            Set<Long> closedKeys = new HashSet<>();
            closed.forEach(c -> closedKeys.add(GateSpatialIndex.packCell(c.position())));
            Set<Long> openKeys = new HashSet<>();
            open.forEach(c -> openKeys.add(GateSpatialIndex.packCell(c.position())));
            return new DoorCells(closed, open, Set.copyOf(closedKeys), Set.copyOf(openKeys));
        });
    }

    /** The fake blocks of a door's pre-game view, per {@link SiegePreGameGate#viewOf}. */
    private static Map<Long, BlockData> fakesOf(DoorCells cells, View view) {
        Map<Long, BlockData> fakes = new LinkedHashMap<>();
        if (!view.sendsAnything()) return fakes;
        BlockData air = Material.AIR.createBlockData();
        if (view.hideOpenOnly()) {
            for (ViewCell cell : cells.open()) {
                long key = GateSpatialIndex.packCell(cell.position());
                if (view.hideClosedOnly() || !cells.closedKeys().contains(key)) fakes.put(key, air);
            }
        }
        if (view.hideClosedOnly()) {
            for (ViewCell cell : cells.closed()) {
                long key = GateSpatialIndex.packCell(cell.position());
                if (view.hideOpenOnly() || !cells.openKeys().contains(key)) fakes.put(key, air);
            }
        }
        if (view.fakeClosedFrame()) {
            for (ViewCell cell : cells.closed()) {
                fakes.put(GateSpatialIndex.packCell(cell.position()), GateViewCells.blockData(cell.blockData(), Material.STONE));
            }
        }
        return fakes;
    }

    /** Cells shown as air that are really solid now: the closed frame at rest, any frame cell mid-animation. */
    private static Set<Long> solidHidden(DoorCells cells, Map<Long, BlockData> fakes, Real real) {
        Set<Long> solid = new HashSet<>();
        fakes.forEach((cell, data) -> {
            if (!data.getMaterial().isAir()) return;
            if (real == Real.MOVING || (real == Real.CLOSED && cells.closedKeys().contains(cell))) solid.add(cell);
        });
        return solid;
    }

    private static long versionOf(CachedGateDoor door) {
        int state = door.getCurrentState() == null ? 0 : door.getCurrentState().ordinal();
        return ((long) state << 40) ^ ((long) door.getCurrentFrame() << 8) ^ (door.isEffectivelyDestroyed() ? 1L : 0L);
    }

    private void send(Player player, DoorView view) {
        World world = player.getWorld();
        if (!world.getName().equals(view.door().getWorldName())) return;
        Set<Long> positions = faked.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(view.door().getId(), k -> new HashSet<>());
        Map<Position, BlockData> changes = new HashMap<>();
        // Cells faked before that aren't part of the view any more get their real block back.
        for (Long cell : positions) {
            if (!view.fakes().containsKey(cell)) changes.put(position(cell), blockAt(world, cell).getBlockData());
        }
        positions.clear();
        view.fakes().forEach((cell, data) -> {
            changes.put(position(cell), data);
            positions.add(cell);
        });
        if (!changes.isEmpty()) player.sendMultiBlockChange(changes);
    }

    /** Sends the real blocks for every cell faked for this player at this door. */
    private void restoreReal(Player player, int doorId) {
        Map<Integer, Set<Long>> byDoor = faked.get(player.getUniqueId());
        if (byDoor == null) return;
        Set<Long> positions = byDoor.remove(doorId);
        if (positions == null || positions.isEmpty()) return;
        CachedGateDoor door = gateManager.getGate(doorId);
        World world = player.getWorld();
        if (door != null && !world.getName().equals(door.getWorldName())) return;
        Map<Position, BlockData> changes = new HashMap<>();
        for (Long cell : positions) changes.put(position(cell), blockAt(world, cell).getBlockData());
        player.sendMultiBlockChange(changes);
    }

    private static Block blockAt(World world, long cell) {
        return world.getBlockAt(BlockKey.x(cell), BlockKey.y(cell), BlockKey.z(cell));
    }

    private static Position position(long cell) {
        return Position.block(BlockKey.x(cell), BlockKey.y(cell), BlockKey.z(cell));
    }

    private static double distanceSq(Player player, CachedGateDoor door) {
        if (door.getAnchorPoint() == null || !player.getWorld().getName().equals(door.getWorldName())) return Double.MAX_VALUE;
        return player.getLocation().toVector().distanceSquared(door.getAnchorPoint());
    }

    /** Drops what was sent for this door, so the next pass re-sends it. */
    private void forget(UUID playerId, int doorId) {
        Map<Integer, Long> versions = sent.get(playerId);
        if (versions != null) versions.remove(doorId);
    }

    private void scheduleClear(Set<Integer> doorIds) {
        cellCache.keySet().removeAll(doorIds);
        allowances.values().forEach(byDoor -> byDoor.keySet().removeAll(doorIds));
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID id : new ArrayList<>(faked.keySet())) {
                Player player = Bukkit.getPlayer(id);
                for (Integer doorId : doorIds) {
                    if (player != null) restoreReal(player, doorId);
                    else faked.get(id).remove(doorId);
                }
                Map<Integer, Long> versions = sent.get(id);
                if (versions != null) versions.keySet().removeAll(doorIds);
            }
        }, CLEAR_DELAY_TICKS);
    }

    // ==================== per-player opening (#216) ====================

    private boolean isAllowed(UUID playerId, int doorId) {
        Map<Integer, Long> byDoor = allowances.get(playerId);
        Long until = byDoor == null ? null : byDoor.get(doorId);
        return until != null && until >= System.currentTimeMillis();
    }

    /** Ends allowances that ran out - kept a little longer while the player still stands in the frame. */
    private void expireAllowances() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<Integer, Long>> entry : allowances.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            entry.getValue().entrySet().removeIf(allowance -> {
                if (allowance.getValue() >= now) return false;
                Set<Long> frame = blockedByDoor.get(allowance.getKey());
                if (player != null && frame != null) {
                    Location at = player.getLocation();
                    if (SiegePreGameGate.overlaps(at.getX(), at.getY(), at.getZ(), frame::contains)) {
                        allowance.setValue(now + ALLOWANCE_GRACE_MS);
                        return false;
                    }
                }
                forget(entry.getKey(), allowance.getKey());
                return true;
            });
        }
        allowances.values().removeIf(Map::isEmpty);
    }

    /**
     * A non-member clicked a locked door (here or via {@code SiegeGateListener}): the controller decides on the
     * pre-game rules (and carries them across itself); a per-player opening is applied here, for the door's
     * pass-through time.
     */
    public void handleNonMemberClick(Player player, CachedGateDoor door) {
        Click click = gates.nonMemberClick(player, door);
        switch (click) {
            case OPEN_FOR_PLAYER -> {
                long seconds = Math.max(1, door.getEffectivePassThroughDurationSeconds());
                allowances.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                        .put(door.getId(), System.currentTimeMillis() + seconds * 1000L);
                restoreReal(player, door.getId());
                forget(player.getUniqueId(), door.getId());
            }
            case DENY -> player.sendActionBar(SiegeMessages.bad("This gate is closed."));
            case CARRY, IGNORE -> { }
        }
    }

    // ==================== hover (#217) ====================

    private void publishHover(Map<Integer, Component> textByDoor, Map<Integer, Set<UUID>> viewersByDoor) {
        if (hover == null || displays == null) return;
        Map<Integer, SiegeGateHoverPackets.HoverText> byEntity = new HashMap<>();
        viewersByDoor.forEach((doorId, viewers) -> {
            TextDisplay display = displays.displayOf(doorId);
            if (display == null) return;
            Component text = textByDoor.get(doorId);
            byEntity.put(display.getEntityId(), new SiegeGateHoverPackets.HoverText(
                    text == null ? Component.empty() : text, Set.copyOf(viewers)));
        });
        hover.publish(byEntity);
    }

    /** Re-sends these doors' displays to the player, so the hover text they should see now is applied. */
    private void refreshHover(Player player, Set<Integer> doorIds) {
        if (hover == null || displays == null || doorIds.isEmpty() || !plugin.isEnabled()) return;
        List<TextDisplay> entities = new ArrayList<>();
        for (Integer doorId : doorIds) {
            TextDisplay display = displays.displayOf(doorId);
            if (display != null && display.getWorld().equals(player.getWorld())) entities.add(display);
        }
        if (entities.isEmpty()) return;
        entities.forEach(e -> player.hideEntity(plugin, e));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            entities.forEach(e -> {
                if (e.isValid()) player.showEntity(plugin, e);
            });
        }, 1L);
    }

    // ==================== listeners ====================

    /**
     * Walking through a door that was open before the match but is really closed: a non-member heading into a
     * really solid door cell they see as air is carried across (the server would reject the move into the
     * block). Probes {@link #CARRY_PROBE_DISTANCE} ahead of the move at feet and head height.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (carryCells.isEmpty()) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null) return;
        Map<Long, DoorCell> cells = carryCells.get(to.getWorld().getName());
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
        DoorCell cell = cells.get(GateSpatialIndex.packCell(bx, by, bz));
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

    /**
     * A non-member right-clicks a door that looks closed to them but is really open (#216): the server sees air
     * there, so the gate package fires nothing. Looks along their line of sight for a pre-game-closed door cell
     * (stopping at the first really solid block) and handles it as a click on that door. Any click by a
     * non-member on such a door also re-sends their fakes next pass (the client drops a fake block it touches).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (blockedCells.isEmpty() || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Map<Long, DoorCell> cells = blockedCells.get(player.getWorld().getName());
        if (cells == null) return;
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        CachedGateDoor hit = null;
        for (double d = 0; d <= CLICK_REACH; d += CLICK_STEP) {
            int x = (int) Math.floor(eye.getX() + direction.getX() * d);
            int y = (int) Math.floor(eye.getY() + direction.getY() * d);
            int z = (int) Math.floor(eye.getZ() + direction.getZ() * d);
            DoorCell cell = cells.get(GateSpatialIndex.packCell(x, y, z));
            if (cell != null) {
                if (cell.lobby().isMember(player.getUniqueId())) return;
                CachedGateDoor door = gateManager.getGate(cell.doorId());
                // A really closed door is the gate package's (GateDoorInteractEvent → SiegeGateListener).
                if (door != null && SiegeGateController.realStateOf(door) != Real.CLOSED) hit = door;
                break;
            }
            if (!player.getWorld().getBlockAt(x, y, z).isPassable()) break;
        }
        if (hit == null) return;
        forget(player.getUniqueId(), hit.getId());
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);
        handleNonMemberClick(player, hit);
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
        hoverViewers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        sent.remove(event.getPlayer().getUniqueId());
        faked.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sent.remove(id);
        faked.remove(id);
        lastCarried.remove(id);
        allowances.remove(id);
        hoverViewers.remove(id);
    }
}
