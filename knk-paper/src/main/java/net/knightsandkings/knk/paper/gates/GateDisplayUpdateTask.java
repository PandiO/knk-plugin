package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodic safety-net refresh of every gate's info display, in case a state change was missed by
 * the event-driven hooks (gate open/close completion, health/destroy changes). Runs every 20
 * ticks (1 second) per the health display spec.
 */
public class GateDisplayUpdateTask extends BukkitRunnable {
    private final GateDisplayManager displayManager;
    private final GateManager gateManager;

    public GateDisplayUpdateTask(GateDisplayManager displayManager, GateManager gateManager) {
        this.displayManager = displayManager;
        this.gateManager = gateManager;
    }

    @Override
    public void run() {
        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            if (!gate.isAnimating()) {
                displayManager.syncDisplay(gate);
            }
        }
    }
}
