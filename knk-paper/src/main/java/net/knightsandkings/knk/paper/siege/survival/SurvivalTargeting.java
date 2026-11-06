package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.SiegeGateApproach;
import net.knightsandkings.knk.core.targeting.TargetCandidate;
import net.knightsandkings.knk.core.targeting.TargetKind;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.GateSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.ObjectiveSpec;
import net.knightsandkings.knk.paper.siege.survival.TargetEligibilityRules.Verdict;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds the target candidates of one enemy from the live match (DESIGN §4): the players (ghosts fail hard
 * eligibility, §3b), the scenario's objectives (not NPC-held, not denied, not behind a closed gate) and the gates (a
 * closed, standing, damageable door of an objective that is not NPC-held, or an allowed gate), with a gate's position
 * being the stand point in front of its closed door for this mob ({@code core/siege/SiegeGateApproach}). Main thread.
 */
public final class SurvivalTargeting {

    private final SurvivalConfiguration config;
    private final SiegeMatch match;
    private final GateManager gates;
    private final Predicate<UUID> ghost;

    public SurvivalTargeting(SurvivalConfiguration config, SiegeMatch match, GateManager gates, Predicate<UUID> ghost) {
        this.config = config;
        this.match = match;
        this.gates = gates;
        this.ghost = ghost;
    }

    /** Whether the objective is NPC-held (its holder is on the NPC team's alliance). */
    public boolean npcHeld(int objectiveId) {
        ObjectiveState state;
        try {
            state = match.board().objective(objectiveId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return match.alliances().areAllies(state.holderTeamId(), config.npcTeamId());
    }

    /** The ids of the NPC-held objectives as strings (the spawn frontier's input). */
    public Set<String> npcHeldObjectiveIds() {
        Set<String> held = new java.util.LinkedHashSet<>();
        for (ObjectiveSpec o : config.objectives()) {
            if (npcHeld(o.id())) held.add(String.valueOf(o.id()));
        }
        return held;
    }

    /** The gate's doors that stand closed and active (what blocks a route and can be hit). */
    public List<CachedGateDoor> closedDoors(int gateStructureId) {
        if (gates == null) return List.of();
        List<CachedGateDoor> closed = new ArrayList<>();
        for (CachedGateDoor door : gates.getDoorsForStructure(gateStructureId)) {
            if (door.getCurrentState() == AnimationState.CLOSED && door.isEffectivelyActive() && !door.isEffectivelyDestroyed()) {
                closed.add(door);
            }
        }
        return closed;
    }

    /** Whether the gate manager knows the structure (otherwise "no gate" for the objective rule). */
    public boolean gateKnown(int gateStructureId) {
        return gates != null && !gates.getDoorsForStructure(gateStructureId).isEmpty();
    }

    /**
     * The candidates for a mob standing at {@code mobX/mobZ} in {@code world}.
     *
     * @param players the lobby's online members in the match (living or ghosts), with their locations
     */
    public List<TargetCandidate> candidates(List<Player> players, String world, double mobX, double mobY, double mobZ) {
        List<TargetCandidate> out = new ArrayList<>();
        for (Player p : players) {
            Location at = p.getLocation();
            if (at.getWorld() == null || !at.getWorld().getName().equals(world)) continue;
            boolean inMatch = match.roster().contains(p.getUniqueId());
            Verdict v = TargetEligibilityRules.player(p.isOnline(), inMatch, !p.isDead(), ghost.test(p.getUniqueId()));
            out.add(new TargetCandidate("player:" + p.getUniqueId(), TargetKind.PLAYER, at.getX(), at.getY(), at.getZ(), v.eligible(), v.reason()));
        }
        for (ObjectiveSpec o : config.objectives()) {
            Optional<SurvivalConfiguration.WorldPoint> capture = o.capture().filter(c -> c.world().isEmpty() || c.world().equals(world));
            Optional<Boolean> gateClosed = o.gateStructureId().isPresent() && gateKnown(o.gateStructureId().getAsInt())
                ? Optional.of(!closedDoors(o.gateStructureId().getAsInt()).isEmpty()) : Optional.empty();
            Verdict v = TargetEligibilityRules.objective(o.override(), npcHeld(o.id()), gateClosed, capture.isPresent());
            double x = capture.map(SurvivalConfiguration.WorldPoint::x).orElse(mobX);
            double y = capture.map(SurvivalConfiguration.WorldPoint::y).orElse(mobY);
            double z = capture.map(SurvivalConfiguration.WorldPoint::z).orElse(mobZ);
            out.add(new TargetCandidate(o.targetId(), TargetKind.OBJECTIVE, x, y, z, v.eligible(), v.reason()));
        }
        for (GateSpec g : config.gates()) {
            List<CachedGateDoor> closed = closedDoors(g.gateStructureId());
            boolean damageable = match.scenario().gate(g.gateStructureId()).map(KnkSiegeGate::damageable).orElse(true);
            Optional<ObjectiveSpec> objective = config.objectiveOfGate(g.gateStructureId());
            boolean objectiveNpcHeld = objective.map(o -> npcHeld(o.id())).orElse(false);
            Optional<SiegeGateApproach.Approach> stand = closed.isEmpty() ? Optional.empty()
                : standPoint(closed, objective.flatMap(ObjectiveSpec::capture), world, mobX, mobZ);
            Verdict v = TargetEligibilityRules.gate(g.override(), !closed.isEmpty(), damageable, objective.isPresent(),
                objectiveNpcHeld, stand.isPresent());
            double x = stand.map(SiegeGateApproach.Approach::x).orElse(mobX);
            double y = stand.map(SiegeGateApproach.Approach::y).orElse(mobY);
            double z = stand.map(SiegeGateApproach.Approach::z).orElse(mobZ);
            out.add(new TargetCandidate(g.targetId(), TargetKind.GATE, x, y, z, v.eligible(), v.reason()));
        }
        return out;
    }

    /**
     * The stand point in front of the closed door nearest to the mob, on the mob's side. The "capture" point the
     * approach keeps behind the door is the objective's capture point; for an area gate (no objective) it is the
     * door centre mirrored away from the mob, so the mob stands on its own side.
     */
    Optional<SiegeGateApproach.Approach> standPoint(List<CachedGateDoor> closed, Optional<SurvivalConfiguration.WorldPoint> capture,
                                                     String world, double mobX, double mobZ) {
        List<SiegeGateApproach.Door> doors = new ArrayList<>();
        double cx = 0;
        double cz = 0;
        int cells = 0;
        for (CachedGateDoor door : closed) {
            if (door.getWorldName() != null && !door.getWorldName().equals(world)) continue;
            List<int[]> closedCells = cells(gates.closedFootprint(door.getId()));
            for (int[] c : closedCells) {
                cx += c[0] + 0.5;
                cz += c[2] + 0.5;
                cells++;
            }
            doors.add(new SiegeGateApproach.Door(door.getName(), closedCells, cells(gates.openFootprint(door.getId()))));
        }
        if (doors.isEmpty() || cells == 0) return Optional.empty();
        cx /= cells;
        cz /= cells;
        double[] captureXyz;
        if (capture.isPresent()) {
            captureXyz = new double[] {capture.get().x(), capture.get().y(), capture.get().z()};
        } else {
            captureXyz = new double[] {cx + (cx - mobX), 0, cz + (cz - mobZ)};
        }
        return SiegeGateApproach.approach(doors, captureXyz, List.of(new double[] {mobX, mobZ}));
    }

    private static List<int[]> cells(List<Vector> positions) {
        List<int[]> out = new ArrayList<>(positions.size());
        for (Vector v : positions) out.add(new int[] {v.getBlockX(), v.getBlockY(), v.getBlockZ()});
        return out;
    }
}
