package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.siege.SiegeGateApproach;

/**
 * Where the navigation compass sends a siege member for an objective to capture while a closed gate of that objective
 * stands in the way (PandiO/knk-workspace#176): in front of that gate, not to the capture point behind it
 * ({@link SiegeGateApproach}). Main thread (reads the gate cache).
 */
@FunctionalInterface
public interface SiegeGateApproaches {

    /** No gate approaches: every objective is navigated to its capture point. */
    SiegeGateApproaches NONE = (objective, team) -> Optional.empty();

    /**
     * The location to head for instead of {@code objective}'s capture point for a member of {@code team}, or empty
     * when no closed door of the objective's gate stands in the way (or it has no gate).
     */
    Optional<KnkLocation> approach(KnkSiegeObjective objective, KnkSiegeTeam team);

    /** From the gate cache: the objective gate's doors that are neither open nor destroyed. */
    static SiegeGateApproaches of(GateManager gates) {
        Objects.requireNonNull(gates, "gates");
        return (objective, team) -> {
            KnkLocation capture = objective.captureLocation();
            if (!objective.hasGate() || capture == null || capture.x() == null || capture.y() == null || capture.z() == null) {
                return Optional.empty();
            }
            List<SiegeGateApproach.Door> closed = new ArrayList<>();
            for (CachedGateDoor door : gates.getDoorsForStructure(objective.gateStructureId())) {
                if (door.getCurrentState() == AnimationState.OPEN || door.isEffectivelyDestroyed()) {
                    continue;
                }
                closed.add(new SiegeGateApproach.Door(door.getName(), cells(gates.closedFootprint(door.getId())),
                    cells(gates.openFootprint(door.getId()))));
            }
            List<double[]> sides = new ArrayList<>();
            for (KnkSiegeSpawnpoint spawn : team.spawnpoints()) {
                KnkLocation at = spawn.location();
                if (at != null && at.x() != null && at.z() != null
                    && (at.world() == null || capture.world() == null || at.world().equalsIgnoreCase(capture.world()))) {
                    sides.add(new double[] {at.x(), at.z()});
                }
            }
            return SiegeGateApproach.approach(closed, new double[] {capture.x(), capture.y(), capture.z()}, sides)
                .map(a -> new KnkLocation(capture.id(), capture.name(), a.x(), a.y(), a.z(), 0f, 0f, capture.world()));
        };
    }

    private static List<int[]> cells(List<Vector> positions) {
        List<int[]> out = new ArrayList<>(positions.size());
        for (Vector v : positions) {
            out.add(new int[] {v.getBlockX(), v.getBlockY(), v.getBlockZ()});
        }
        return out;
    }
}
