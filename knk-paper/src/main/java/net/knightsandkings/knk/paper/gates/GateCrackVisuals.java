package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a damaged gate's door blocks with vanilla's block-breaking cracks. Minecraft has
 * {@value #STAGES} crack stages (the texture a block you are mining goes through until it breaks); the
 * gate's HP is divided into that many equal segments of lost HP, and the blocks show the stage of the
 * segment the gate is in, so a gate at 25% HP looks about three quarters broken instead of only saying
 * so on its info display.
 * <p>
 * Purely visual: the cracks are sent to nearby players as block-break animation packets (the same ones the
 * server sends for another player mining a block) and change nothing in the world. The client forgets an
 * animation after about 20 seconds without an update, so {@link #sync} re-sends them on a slow cycle (which
 * also shows them to players who came into range). The cracks only belong to a CLOSED, standing gate - an
 * opening, open or destroyed one has them taken down, and a repaired or respawned gate has none. Main thread
 * only.
 */
public class GateCrackVisuals {
    /** Vanilla's block-breaking stages, 0 (first cracks) to 9 (about to break). */
    static final int STAGES = 10;
    /** Players within this many blocks of the gate's edge see its cracks. */
    static final double VIEW_MARGIN = 64.0;

    /** What a gate currently shows its viewers: the stage and the block cells it was sent for. */
    record Shown(int stage, List<Vector> cells) {
    }

    private final Map<Integer, Shown> shownByGate = new HashMap<>();
    private final GateManager gateManager;

    public GateCrackVisuals(GateManager gateManager) {
        this.gateManager = gateManager;
    }

    /**
     * The crack stage for a gate's HP: -1 for none (full HP), otherwise 0-9 by the segment of lost HP,
     * 0 for the first tenth up to 9 for the last.
     */
    static int stageFor(double healthCurrent, double healthMax) {
        if (!(healthMax > 0) || healthCurrent >= healthMax) {
            return -1;
        }
        double lost = (healthMax - Math.max(0.0, healthCurrent)) / healthMax;
        return Math.min(STAGES - 1, (int) Math.floor(lost * STAGES));
    }

    /**
     * The progress to hand Paper's {@code Player.sendBlockDamage} for a stage: Paper shows
     * {@code (int) (9 * progress)} and treats 0 as "clear", so a stage is sent as the middle of its band.
     */
    static float progressFor(int stage) {
        return stage >= STAGES - 1 ? 1.0f : (stage + 0.5f) / (STAGES - 1);
    }

    /** The stage this gate's blocks should show right now: none unless it is damaged, standing and closed. */
    static int targetStage(CachedGateDoor gate) {
        if (gate.isEffectivelyDestroyed() || gate.getCurrentState() != AnimationState.CLOSED) {
            return -1;
        }
        return stageFor(gate.getHealthCurrent(), gate.getHealthMax());
    }

    /** Sync every gate (the periodic task). {@code resend} re-sends cracks the client may have timed out. */
    public void syncAll(boolean resend) {
        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            sync(gate, resend);
        }
    }

    /**
     * Bring the gate's cracks in line with its HP and state: send them when the stage changed (or on
     * {@code resend}), take them down when it no longer qualifies.
     */
    public void sync(CachedGateDoor gate, boolean resend) {
        if (gate == null) {
            return;
        }
        int target = targetStage(gate);
        Shown shown = shownByGate.get(gate.getId());
        if (target < 0) {
            if (shown != null) {
                shownByGate.remove(gate.getId());
                send(gate, shown.cells(), 0.0f);
            }
            return;
        }
        if (shown != null && shown.stage() == target && !resend) {
            return;
        }
        // A closed gate stands at frame 0, so its cells don't change between syncs.
        List<Vector> cells = shown != null ? shown.cells() : HealthSystem.doorCellsAt(gate, 0);
        shownByGate.put(gate.getId(), new Shown(target, cells));
        send(gate, cells, progressFor(target));
    }

    /** The stage currently shown for a gate, or -1 (tests). */
    int shownStage(CachedGateDoor gate) {
        Shown shown = shownByGate.get(gate.getId());
        return shown == null ? -1 : shown.stage();
    }

    private void send(CachedGateDoor gate, List<Vector> cells, float progress) {
        World world = Bukkit.getWorld(gate.getWorldName());
        Vector anchor = gate.getAnchorPoint();
        if (world == null || anchor == null || cells.isEmpty()) {
            return;
        }
        double reach = VIEW_MARGIN + Math.max(gate.getGeometryWidth(), Math.max(gate.getGeometryHeight(), gate.getGeometryDepth()));
        Location center = new Location(world, anchor.getX(), anchor.getY(), anchor.getZ());
        for (Player viewer : world.getNearbyPlayers(center, reach)) {
            for (int index = 0; index < cells.size(); index++) {
                Vector cell = cells.get(index);
                viewer.sendBlockDamage(new Location(world, cell.getBlockX(), cell.getBlockY(), cell.getBlockZ()),
                    progress, sourceId(gate.getId(), index));
            }
        }
    }

    /**
     * The "breaker" the animation of one door block belongs to. The client keeps one animation per breaker
     * and block, and an id of a real entity would clash with that entity's own mining, so these are
     * negative (entity ids never are) and one per block of a gate.
     */
    static int sourceId(int gateId, int cellIndex) {
        return Integer.MIN_VALUE + (((gateId & 0x7FFF) << 16) | (cellIndex & 0xFFFF));
    }
}
