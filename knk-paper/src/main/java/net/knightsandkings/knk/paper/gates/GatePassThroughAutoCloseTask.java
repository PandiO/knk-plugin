package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * One-shot task that closes a gate after its pass-through hold-open window elapses. Kept as a
 * small named class (rather than an inline lambda) so GatePassThroughService can hold a reference
 * to the scheduled BukkitTask per gate ID and cancel/reschedule it if the gate is re-triggered
 * while still open - matching the GateFireDamageTask/GateFireSystem split.
 */
public class GatePassThroughAutoCloseTask extends BukkitRunnable {
    private final GateManager gateManager;
    private final int gateId;
    // PandiO/knk-workspace#167: a snap pass-through also closes at once, without the closing animation.
    private final boolean snap;

    public GatePassThroughAutoCloseTask(GateManager gateManager, int gateId) {
        this(gateManager, gateId, false);
    }

    public GatePassThroughAutoCloseTask(GateManager gateManager, int gateId, boolean snap) {
        this.gateManager = gateManager;
        this.gateId = gateId;
        this.snap = snap;
    }

    @Override
    public void run() {
        if (snap) {
            gateManager.snapGate(gateId, false);
        } else {
            gateManager.closeGate(gateId);
        }
    }
}
