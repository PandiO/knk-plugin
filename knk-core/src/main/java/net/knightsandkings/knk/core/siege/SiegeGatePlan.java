package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Siege Phase 7a: every gate a match touches and who may do what with it (DESIGN §8.1). Built from
 * the frozen scenario at lockdown; keeps the runtime owner of each selected gate (objective captures
 * hand objective gates over). No Bukkit types; main-thread use only (not synchronized).
 * <table>
 *   <tr><th>Gate</th><th>Control</th><th>Damage</th><th>On objective capture</th></tr>
 *   <tr><td>Selected</td><td>owner team's alliance</td><td>enemies of the owner, if damageable</td><td>-</td></tr>
 *   <tr><td>Selected + objective</td><td>as above</td><td>as above</td><td>owner := capturer's team, forced to GateStateOnCapture</td></tr>
 *   <tr><td>Area (any other gate in the scenario area)</td><td>nobody (forced open)</td><td>nobody (invincible)</td><td>-</td></tr>
 * </table>
 */
public final class SiegeGatePlan {

    public enum Role { SELECTED, AREA }

    /** Why a player may or may not open/close a gate. */
    public enum Control { ALLOWED, NOT_IN_MATCH, NOT_OWNER, FORCED_OPEN, NOT_IN_PLAN }

    /**
     * @param initialOpen        the state forced at lockdown
     * @param initialOwnerTeamId the owner at lockdown (0 for area gates)
     */
    public record Entry(int gateStructureId, Role role, boolean objectiveGate, boolean damageable,
                        boolean initialOpen, int initialOwnerTeamId) {
        /** Area gates and non-damageable selected gates can't be damaged at all. */
        public boolean invincible() {
            return role == Role.AREA || !damageable;
        }

        public boolean forcedOpen() {
            return role == Role.AREA;
        }
    }

    /** What an objective capture does to its gate. */
    public record Transfer(int gateStructureId, int newOwnerTeamId, boolean open) {}

    private final Map<Integer, Entry> entries;
    private final Map<Integer, Integer> owners = new HashMap<>();
    private final Map<Integer, KnkSiegeObjective> objectivesById = new HashMap<>();

    private SiegeGatePlan(Map<Integer, Entry> entries, List<KnkSiegeObjective> objectives) {
        this.entries = Collections.unmodifiableMap(entries);
        entries.values().stream().filter(e -> e.role() == Role.SELECTED)
                .forEach(e -> owners.put(e.gateStructureId(), e.initialOwnerTeamId()));
        objectives.forEach(o -> objectivesById.put(o.id(), o));
    }

    public static SiegeGatePlan of(KnkSiegeScenario scenario) {
        Map<Integer, Entry> entries = new LinkedHashMap<>();
        for (KnkSiegeGate gate : scenario.gates()) {
            entries.put(gate.gateStructureId(), new Entry(gate.gateStructureId(), Role.SELECTED, gate.objectiveGate(),
                    gate.damageable(), gate.initialState() == SiegeGateState.OPEN, gate.initialOwnerTeamId()));
        }
        for (Integer id : scenario.areaGateStructureIds()) {
            entries.putIfAbsent(id, new Entry(id, Role.AREA, false, false, true, 0));
        }
        return new SiegeGatePlan(entries, scenario.objectives());
    }

    public List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    public Optional<Entry> entry(int gateStructureId) {
        return Optional.ofNullable(entries.get(gateStructureId));
    }

    public boolean contains(int gateStructureId) {
        return entries.containsKey(gateStructureId);
    }

    /** The current owner of a selected gate; empty for area gates and gates outside the plan. */
    public OptionalInt owner(int gateStructureId) {
        Integer owner = owners.get(gateStructureId);
        return owner == null ? OptionalInt.empty() : OptionalInt.of(owner);
    }

    /**
     * An objective changed hands: if it has a selected gate, that gate's owner becomes the
     * capturer's team and it is forced to the objective's GateStateOnCapture - on every recapture too.
     */
    public Optional<Transfer> onCapture(int objectiveId, int newHolderTeamId) {
        KnkSiegeObjective objective = objectivesById.get(objectiveId);
        if (objective == null || !objective.hasGate()) return Optional.empty();
        Entry entry = entries.get(objective.gateStructureId());
        if (entry == null || entry.role() != Role.SELECTED) return Optional.empty();
        owners.put(entry.gateStructureId(), newHolderTeamId);
        return Optional.of(new Transfer(entry.gateStructureId(), newHolderTeamId,
                objective.gateStateOnCapture() == SiegeGateState.OPEN));
    }

    /**
     * May this player open/close the gate?
     *
     * @param playerTeamId the player's team in the running match, or null for non-members
     */
    public Control canControl(int gateStructureId, Integer playerTeamId, AllianceResolver alliances) {
        Entry entry = entries.get(gateStructureId);
        if (entry == null) return Control.NOT_IN_PLAN;
        if (entry.role() == Role.AREA) return Control.FORCED_OPEN;
        if (playerTeamId == null) return Control.NOT_IN_MATCH;
        OptionalInt owner = owner(gateStructureId);
        return owner.isPresent() && alliances.areAllies(owner.getAsInt(), playerTeamId) ? Control.ALLOWED : Control.NOT_OWNER;
    }

    /**
     * May this attacker damage the gate? Only a match member whose team is an enemy of the current
     * owner, on a damageable selected gate. Non-members and unattributed damage (explosions without
     * a player) never count.
     */
    public boolean canDamage(int gateStructureId, Integer attackerTeamId, AllianceResolver alliances) {
        Entry entry = entries.get(gateStructureId);
        if (entry == null || entry.invincible() || attackerTeamId == null) return false;
        OptionalInt owner = owner(gateStructureId);
        return owner.isPresent() && alliances.areEnemies(owner.getAsInt(), attackerTeamId);
    }
}
