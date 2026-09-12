package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Implements the three gate pass-through modes a player can trigger by right-clicking a closed,
 * AllowPassThrough-enabled gate door (see GatePassThroughConsequenceListener for the permission
 * and mode-resolution logic that calls into this class).
 *
 * Kept as a plain service (not a Bukkit Listener), mirroring GateFireSystem/HealthSystem - the
 * listener adapts the Bukkit event, this class owns the actual gameplay consequence.
 */
public class GatePassThroughService {
    private static final Logger LOGGER = Logger.getLogger(GatePassThroughService.class.getName());

    // Vertical reach (in blocks, along the gate's v-axis) around the player's feet position that
    // counts as "in their path" for InstantOpen - covers a standing player's ~1.8 block height.
    private static final double INSTANT_OPEN_VERTICAL_TOLERANCE = 1.5;

    // How far (in blocks, up then down) Teleport mode will search around the computed destination
    // for a spot that won't suffocate/strand the player, before falling back to a nudge-only fix.
    private static final int TELEPORT_SAFE_SEARCH_RADIUS = 4;

    private final GateManager gateManager;
    private final Plugin plugin;
    private final int instantOpenRadiusBlocks;
    private final long instantOpenTimeoutTicks;

    // Gate ID -> pending auto-close task, so a re-trigger while the gate is already open can
    // cancel and reschedule the close instead of stacking multiple close tasks.
    private final Map<Integer, BukkitTask> pendingAutoClose = new HashMap<>();

    public GatePassThroughService(
        GateManager gateManager,
        Plugin plugin,
        int instantOpenRadiusBlocks,
        int instantOpenTimeoutSeconds
    ) {
        this.gateManager = gateManager;
        this.plugin = plugin;
        this.instantOpenRadiusBlocks = instantOpenRadiusBlocks;
        this.instantOpenTimeoutTicks = Math.max(1, instantOpenTimeoutSeconds) * 20L;
    }

    /**
     * Dispatch to the handler for the given (already permission-checked) mode.
     */
    public void dispatch(CachedGateDoor gate, Player player, GatePassThroughMethod mode) {
        switch (mode) {
            case INSTANT_OPEN -> handleInstantOpen(gate, player);
            case TELEPORT -> handleTeleport(gate, player);
            default -> handleDefault(gate);
        }
    }

    /**
     * Default mode: open the gate (if closed), then (re)schedule an auto-close after the gate's
     * configured PassThroughDurationSeconds. Re-triggering while already open/opening just resets
     * the close timer, which is the "stays open while players keep passing through" behavior.
     */
    private void handleDefault(CachedGateDoor gate) {
        int gateId = gate.getId();
        AnimationState state = gate.getCurrentState();

        switch (state) {
            case CLOSED -> {
                gateManager.openGate(gateId);
                scheduleAutoClose(gate);
            }
            case OPEN, OPENING -> scheduleAutoClose(gate);
            case CLOSING -> gateManager.setAnimationCompletionCallback(gateId, finished -> {
                gateManager.openGate(gateId);
                scheduleAutoClose(gate);
            });
        }
    }

    private void scheduleAutoClose(CachedGateDoor gate) {
        int gateId = gate.getId();
        BukkitTask existing = pendingAutoClose.remove(gateId);
        if (existing != null) {
            existing.cancel();
        }

        long delayTicks = Math.max(1, gate.getEffectivePassThroughDurationSeconds()) * 20L;
        BukkitTask task = new GatePassThroughAutoCloseTask(gateManager, gateId).runTaskLater(plugin, delayTicks);
        pendingAutoClose.put(gateId, task);
    }

    /**
     * InstantOpen mode: remove the door blocks in the player's path - both their current (u,v)
     * position on the door plane and a point projected ~1 block ahead of them along their facing
     * direction, across the gate's full depth - then restore those exact blocks once they've
     * crossed to the far side (or a timeout elapses, as a safety net). The gate's AnimationState
     * never changes - this is a block swap, not an animation.
     *
     * NOTE: a real block is removed here, briefly visible/walkable to anyone nearby - the same
     * exposure window Default mode already has while held open. Per-player-only passability
     * (via client-side block-change packets, leaving the real block solid) was tried and reverted:
     * Paper's server-side movement validation rejects a player's reported position landing inside
     * a block that's still really solid, regardless of what their client was shown, so packets
     * alone cannot make a genuinely solid block passable for just one player.
     */
    private void handleInstantOpen(CachedGateDoor gate, Player player) {
        Vector anchor = gate.getAnchorPoint();
        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector nAxis = gate.getNAxis();
        if (anchor == null || uAxis == null || vAxis == null || nAxis == null) {
            LOGGER.warning("Gate " + gate.getName() + " is missing basis vectors; cannot InstantOpen");
            return;
        }

        World world = player.getWorld();
        Location playerLoc = player.getLocation();
        Vector playerPosition = playerLoc.toVector();
        Vector forwardPosition = playerPosition.clone().add(horizontalFacingDirection(playerLoc.getYaw()));
        Vector playerLocal = playerPosition.clone().subtract(anchor);
        double startSign = Math.signum(playerLocal.dot(nAxis));

        Map<Vector, String> removedBlocks = new LinkedHashMap<>();
        for (BlockSnapshot block : gate.getBlocks()) {
            Vector closedPos = GateFrameCalculator.calculateBlockPosition(gate, block, 0);
            if (closedPos == null) {
                continue;
            }

            boolean inPath = isBlockInPassThroughPath(gate, closedPos, playerPosition, instantOpenRadiusBlocks)
                || isBlockInPassThroughPath(gate, closedPos, forwardPosition, instantOpenRadiusBlocks);
            if (!inPath) {
                continue;
            }

            if (GateBlockPlacer.removeBlockIfMatches(world, closedPos, block.getBlockData(), Material.STONE)) {
                removedBlocks.put(closedPos, block.getBlockData());
            }
        }

        if (removedBlocks.isEmpty()) {
            return;
        }

        new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                ticks++;
                boolean stillOnline = player.isOnline();
                boolean crossed = false;
                if (stillOnline) {
                    Vector nowLocal = player.getLocation().toVector().subtract(anchor);
                    double nowSign = Math.signum(nowLocal.dot(nAxis));
                    crossed = nowSign != 0 && startSign != 0 && nowSign != startSign;
                }

                if (crossed || ticks >= instantOpenTimeoutTicks || !stillOnline) {
                    for (Map.Entry<Vector, String> entry : removedBlocks.entrySet()) {
                        GateBlockPlacer.placeBlockIfVacant(world, entry.getKey(), entry.getValue(), Material.STONE);
                    }
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Horizontal (Y=0) unit vector for a yaw, matching Bukkit's own yaw-to-direction convention
     * (yaw 0 = -Z, increasing clockwise). Used to project a point ~1 block ahead of the player in
     * their facing direction, so InstantOpen's block selection accounts for where they're heading
     * (e.g. approaching the doorway at an angle) and not just their exact standing position.
     */
    static Vector horizontalFacingDirection(float yaw) {
        double yawRad = Math.toRadians(yaw);
        return new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
    }

    /**
     * Teleport mode: project the player's current (u,v) offset from the gate's anchor back onto
     * the door plane, then step to the far side by the gate's depth (+1 block clearance) along
     * its normal axis - no animation, no state change, yaw/pitch preserved. The far side's ground
     * level isn't guaranteed to match the near side's (e.g. approaching from one block below the
     * gate can otherwise land the player inside solid ground on the other side), so the raw
     * destination's Y is adjusted to the nearest non-suffocating, standable spot before teleporting.
     */
    private void handleTeleport(CachedGateDoor gate, Player player) {
        Location playerLoc = player.getLocation();
        Vector destination = computeTeleportDestination(gate, playerLoc.toVector());
        if (destination == null) {
            LOGGER.warning("Gate " + gate.getName() + " is missing basis vectors; cannot teleport");
            return;
        }

        Location destinationLoc = findSafeTeleportLocation(
            playerLoc.getWorld(), destination, playerLoc.getYaw(), playerLoc.getPitch());
        player.teleport(destinationLoc);
    }

    /**
     * Resolves a safe Y for the given raw destination (x/z kept as computed): searches outward
     * from the raw Y, alternating up then down, for the nearest spot where the player's feet and
     * head are both passable with solid ground underneath. Falls back to nudging upward from the
     * raw Y until the player at least won't suffocate, if no standable spot is found within range.
     */
    static Location findSafeTeleportLocation(World world, Vector rawDestination, float yaw, float pitch) {
        int x = rawDestination.getBlockX();
        int z = rawDestination.getBlockZ();
        int startY = rawDestination.getBlockY();

        Integer safeY = findStandableY(world, x, startY, z);
        if (safeY == null) {
            safeY = findNonSuffocatingY(world, x, startY, z);
        }

        return new Location(world, rawDestination.getX(), safeY, rawDestination.getZ(), yaw, pitch);
    }

    static Integer findStandableY(World world, int x, int startY, int z) {
        int minY = Math.max(world.getMinHeight(), startY - TELEPORT_SAFE_SEARCH_RADIUS);
        int maxY = Math.min(world.getMaxHeight() - 2, startY + TELEPORT_SAFE_SEARCH_RADIUS);

        for (int offset = 0; offset <= TELEPORT_SAFE_SEARCH_RADIUS; offset++) {
            int[] candidates = offset == 0 ? new int[]{startY} : new int[]{startY + offset, startY - offset};
            for (int y : candidates) {
                if (y < minY || y > maxY) {
                    continue;
                }
                if (isPassable(world, x, y, z) && isPassable(world, x, y + 1, z) && isSolid(world, x, y - 1, z)) {
                    return y;
                }
            }
        }
        return null;
    }

    /**
     * No standable spot found nearby - at minimum, nudge upward until the player's feet and head
     * aren't inside solid blocks, even without solid ground underneath (a short fall beats
     * spawning inside a wall).
     */
    static int findNonSuffocatingY(World world, int x, int startY, int z) {
        int y = startY;
        int maxY = world.getMaxHeight() - 2;
        while (y < maxY && (!isPassable(world, x, y, z) || !isPassable(world, x, y + 1, z))) {
            y++;
        }
        return y;
    }

    static boolean isPassable(World world, int x, int y, int z) {
        return !isSolid(world, x, y, z);
    }

    static boolean isSolid(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        return block.getType().isSolid();
    }

    /**
     * True if a door block's closed-frame world position lies within radiusBlocks (sideways,
     * along the gate's u-axis) and a fixed vertical tolerance (along its v-axis, covering a
     * standing player's ~1.8 block height) of the given player position - i.e. "in their path"
     * for InstantOpen. Package-private and static: pure geometry, independently testable without
     * a live Bukkit world/player.
     */
    static boolean isBlockInPassThroughPath(CachedGateDoor gate, Vector blockClosedWorldPos, Vector playerPosition, int radiusBlocks) {
        Vector anchor = gate.getAnchorPoint();
        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        if (anchor == null || uAxis == null || vAxis == null) {
            return false;
        }

        Vector playerLocal = playerPosition.clone().subtract(anchor);
        double playerU = playerLocal.dot(uAxis);
        double playerV = playerLocal.dot(vAxis);

        Vector blockLocal = blockClosedWorldPos.clone().subtract(anchor);
        double blockU = blockLocal.dot(uAxis);
        double blockV = blockLocal.dot(vAxis);

        return Math.abs(blockU - playerU) <= radiusBlocks + 0.5
            && Math.abs(blockV - playerV) <= INSTANT_OPEN_VERTICAL_TOLERANCE;
    }

    /**
     * Projects playerPosition's (u,v) offset from the gate's anchor back onto the door plane,
     * then steps to the far side by the gate's depth (+1 block clearance) along its normal axis.
     * Returns null if the gate is missing basis vectors. Package-private and static: pure
     * geometry, independently testable without a live Bukkit world/player.
     */
    static Vector computeTeleportDestination(CachedGateDoor gate, Vector playerPosition) {
        Vector anchor = gate.getAnchorPoint();
        Vector uAxis = gate.getUAxis();
        Vector vAxis = gate.getVAxis();
        Vector nAxis = gate.getNAxis();
        if (anchor == null || uAxis == null || vAxis == null || nAxis == null) {
            return null;
        }

        Vector local = playerPosition.clone().subtract(anchor);
        double offsetU = local.dot(uAxis);
        double offsetV = local.dot(vAxis);
        double offsetN = local.dot(nAxis);
        double side = offsetN >= 0 ? 1.0 : -1.0;

        double stepDistance = Math.max(1, gate.getGeometryDepth()) + 1;
        return anchor.clone()
            .add(uAxis.clone().multiply(offsetU))
            .add(vAxis.clone().multiply(offsetV))
            .add(nAxis.clone().multiply(-side * stepDistance));
    }
}
