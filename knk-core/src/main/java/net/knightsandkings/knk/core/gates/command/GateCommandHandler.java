package net.knightsandkings.knk.core.gates.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateManager;

/**
 * Overview map L7 (PandiO/knk-workspace#741, OVERVIEW_MAP_DESIGN § 7.1.3, O17): runs a gate command the API sent over
 * the game hub ({@code GateCommand}) with the existing gate operations, so the doors animate as with {@code /gate}:
 * {@link GateManager#openGate}, {@link GateManager#closeGate}, {@link GateManager#toggleGate} and the shared repair
 * ({@code GateCommandSupport.repair}: full health, not destroyed, persisted). It never changes how those behave.
 * <ol>
 *   <li>A siege lockdown on the gate refuses the command ({@link Outcome#SIEGE}), whatever the API decided.</li>
 *   <li>A door id that is not a loaded door of the gate, or a gate without loaded doors, is refused.</li>
 *   <li>Per door, the same rules as the in-game commands: an inactive door is skipped; a destroyed door doesn't open
 *       (or toggle open); a door already in the asked state counts as done; an animating door doesn't toggle.</li>
 *   <li>{@link Outcome#DONE} when at least one door did what was asked (or already was so), otherwise
 *       {@link Outcome#REFUSED} with the reasons.</li>
 * </ol>
 * Bukkit-free; call it on the server's main thread (the gate cache and animations live there).
 */
public final class GateCommandHandler {

    /** The command's action. */
    public enum Action {
        OPEN, CLOSE, TOGGLE, REPAIR;

        /** The action for a wire value ({@code open}, any case), or null. */
        public static Action parse(String text) {
            if (text == null) return null;
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The result word sent back to the API. */
    public enum Outcome {
        DONE, REFUSED, SIEGE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What happened to one door. */
    public enum DoorOutcome {
        /** The animation (or the repair) started. */
        STARTED,
        /** It already was in the asked state (or opening/closing towards it). */
        ALREADY,
        /** Repaired. */
        REPAIRED,
        /** Not done; see the reason. */
        SKIPPED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One door after the command.
     *
     * @param state lower case: {@code open}, {@code closed}, {@code opening}, {@code closing}
     */
    public record DoorResult(int doorId, String name, String state, double healthCurrent, double healthMax,
                             boolean destroyed, DoorOutcome outcome, String reason) {
    }

    /** The answer to one command. */
    public record Result(Outcome outcome, String message, List<DoorResult> doors) {
        public Result {
            doors = List.copyOf(doors);
        }
    }

    public static final String SIEGE_MESSAGE = "Gate is in a running siege.";

    private final GateManager gates;
    private final IntPredicate inRunningSiege;
    private final Consumer<CachedGateDoor> repair;

    /**
     * @param gates          the gate cache and its open/close/toggle operations
     * @param inRunningSiege whether a running siege match holds the gate structure (its lockdown)
     * @param repair         the existing repair of one door (full health, not destroyed, persisted, state fired)
     */
    public GateCommandHandler(GateManager gates, IntPredicate inRunningSiege, Consumer<CachedGateDoor> repair) {
        this.gates = Objects.requireNonNull(gates, "gates");
        this.inRunningSiege = inRunningSiege == null ? id -> false : inRunningSiege;
        this.repair = Objects.requireNonNull(repair, "repair");
    }

    /**
     * Runs the command.
     *
     * @param gateId the gate structure
     * @param doorId one door of it, or null for every door
     */
    public Result handle(int gateId, Integer doorId, Action action) {
        if (action == null) {
            return refused("Unknown action.");
        }
        if (inRunningSiege.test(gateId)) {
            return new Result(Outcome.SIEGE, SIEGE_MESSAGE, List.of());
        }
        List<CachedGateDoor> doors;
        if (doorId != null) {
            CachedGateDoor door = gates.getGate(doorId);
            if (door == null || door.getGateStructureId() != gateId) {
                return refused("Door " + doorId + " is not a loaded door of gate " + gateId + " on this server.");
            }
            doors = List.of(door);
        } else {
            doors = new ArrayList<>(gates.getDoorsForStructure(gateId));
            doors.sort(Comparator.comparingInt(CachedGateDoor::getId));
            if (doors.isEmpty()) {
                return refused("Gate " + gateId + " has no loaded doors on this server.");
            }
        }

        List<DoorResult> results = new ArrayList<>();
        for (CachedGateDoor door : doors) {
            results.add(run(door, action));
        }
        boolean anyDone = results.stream().anyMatch(r -> r.outcome() != DoorOutcome.SKIPPED);
        return new Result(anyDone ? Outcome.DONE : Outcome.REFUSED, summary(gateLabel(gateId, doors), action, results), results);
    }

    private DoorResult run(CachedGateDoor door, Action action) {
        if (action == Action.REPAIR) {
            repair.accept(door);
            return result(door, DoorOutcome.REPAIRED, null);
        }
        if (!door.isEffectivelyActive()) {
            return result(door, DoorOutcome.SKIPPED, "not active");
        }
        AnimationState state = door.getCurrentState();
        Action effective = action;
        if (action == Action.TOGGLE) {
            if (state == AnimationState.OPENING || state == AnimationState.CLOSING) {
                return result(door, DoorOutcome.SKIPPED, "moving");
            }
            effective = state == AnimationState.OPEN ? Action.CLOSE : Action.OPEN;
        }
        if (effective == Action.OPEN) {
            if (door.isEffectivelyDestroyed()) {
                return result(door, DoorOutcome.SKIPPED, "destroyed");
            }
            if (state == AnimationState.OPEN || state == AnimationState.OPENING) {
                return result(door, DoorOutcome.ALREADY, null);
            }
            return gates.openGate(door.getId())
                ? result(door, DoorOutcome.STARTED, null) : result(door, DoorOutcome.SKIPPED, "could not open");
        }
        if (state == AnimationState.CLOSED || state == AnimationState.CLOSING) {
            return result(door, DoorOutcome.ALREADY, null);
        }
        return gates.closeGate(door.getId())
            ? result(door, DoorOutcome.STARTED, null) : result(door, DoorOutcome.SKIPPED, "could not close");
    }

    private static DoorResult result(CachedGateDoor door, DoorOutcome outcome, String reason) {
        AnimationState state = door.getCurrentState();
        return new DoorResult(door.getId(), door.getName(), state == null ? null : state.name().toLowerCase(Locale.ROOT),
            door.getHealthCurrent(), door.getHealthMax(), door.isEffectivelyDestroyed(), outcome, reason);
    }

    private String gateLabel(int gateId, List<CachedGateDoor> doors) {
        CachedGateStructure structure = gates.getStructure(gateId);
        String name = structure != null ? structure.getName() : doors.get(0).getStructureName();
        return "Gate " + (name == null || name.isBlank() ? "#" + gateId : "'" + name + "'");
    }

    private static String summary(String gate, Action action, List<DoorResult> doors) {
        StringBuilder text = new StringBuilder(gate).append(' ').append(action.wire()).append(": ");
        for (int i = 0; i < doors.size(); i++) {
            DoorResult door = doors.get(i);
            if (i > 0) text.append(", ");
            text.append(door.name() == null ? "#" + door.doorId() : door.name()).append(' ').append(door.outcome().wire());
            if (door.reason() != null) text.append(" (").append(door.reason()).append(')');
        }
        return text.append('.').toString();
    }

    private static Result refused(String message) {
        return new Result(Outcome.REFUSED, message, List.of());
    }
}
