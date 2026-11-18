package net.knightsandkings.knk.core.gates.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.Action;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.DoorOutcome;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.Outcome;
import net.knightsandkings.knk.core.gates.command.GateCommandHandler.Result;

/**
 * Overview map L7 (PandiO/knk-workspace#741, spec § 7.1.3, O17, A18/A19 plugin side): a web gate command runs the
 * existing gate operations (the door starts its normal animation through {@link GateManager}), refuses under a siege
 * lockdown and for a door of another gate, and follows the in-game rules per door.
 */
class GateCommandHandlerTest {

    private static final int GATE = 12;
    private static final int OTHER_GATE = 13;

    private GateManager gates;
    private final Set<Integer> lockedGates = new HashSet<>();
    private final List<Integer> repaired = new ArrayList<>();
    private GateCommandHandler handler;

    @BeforeEach
    void setUp() {
        gates = new GateManager();
        gates.cacheStructure(new CachedGateStructure(GATE, "North Gate"));
        gates.cacheStructure(new CachedGateStructure(OTHER_GATE, "South Gate"));
        door(21, GATE, "West");
        door(22, GATE, "East");
        door(31, OTHER_GATE, "Main");
        // The shared /gate repair, as wired in the plugin: full health, not destroyed.
        handler = new GateCommandHandler(gates, lockedGates::contains, d -> {
            repaired.add(d.getId());
            d.setHealthCurrent(d.getHealthMax());
            d.setIsDestroyed(false);
        });
    }

    private CachedGateDoor door(int id, int gate, String name) {
        CachedGateDoor door = new CachedGateDoor(id, gate, name, "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 1, 1, 1, 500.0, 500.0, true, false, true, 90, "north");
        gates.cacheGate(door);
        return door;
    }

    private AnimationState state(int door) {
        return gates.getGate(door).getCurrentState();
    }

    // ===== open / close / toggle with the normal animation (A18) =====

    @Test
    void openOneDoor_StartsItsOpeningAnimation_OnlyThatDoor() {
        Result result = handler.handle(GATE, 21, Action.OPEN);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(AnimationState.OPENING, state(21));
        assertEquals(AnimationState.CLOSED, state(22));
        assertEquals(1, result.doors().size());
        assertEquals(DoorOutcome.STARTED, result.doors().get(0).outcome());
        assertEquals("opening", result.doors().get(0).state());
        assertEquals("Gate 'North Gate' open: West started.", result.message());
    }

    @Test
    void openWithoutADoor_OpensEveryDoorOfTheGate_NotOtherGates() {
        Result result = handler.handle(GATE, null, Action.OPEN);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(List.of(21, 22), result.doors().stream().map(GateCommandHandler.DoorResult::doorId).toList());
        assertEquals(AnimationState.OPENING, state(21));
        assertEquals(AnimationState.OPENING, state(22));
        assertEquals(AnimationState.CLOSED, state(31));
    }

    @Test
    void close_StartsTheClosingAnimation() {
        gates.forceGateState(21, true);

        Result result = handler.handle(GATE, 21, Action.CLOSE);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(AnimationState.CLOSING, state(21));
    }

    @Test
    void alreadyInTheAskedState_IsDone_AndNothingMoves() {
        Result result = handler.handle(GATE, 21, Action.CLOSE);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(DoorOutcome.ALREADY, result.doors().get(0).outcome());
        assertEquals(AnimationState.CLOSED, state(21));
    }

    @Test
    void toggle_OpensAClosedDoor_AndClosesAnOpenOne_SkipsAMovingOne() {
        gates.forceGateState(22, true);

        assertEquals(Outcome.DONE, handler.handle(GATE, 21, Action.TOGGLE).outcome());
        assertEquals(AnimationState.OPENING, state(21));
        assertEquals(Outcome.DONE, handler.handle(GATE, 22, Action.TOGGLE).outcome());
        assertEquals(AnimationState.CLOSING, state(22));

        Result moving = handler.handle(GATE, 21, Action.TOGGLE);
        assertEquals(Outcome.REFUSED, moving.outcome());
        assertEquals("moving", moving.doors().get(0).reason());
        assertEquals(AnimationState.OPENING, state(21));
    }

    // ===== repair (A18) =====

    @Test
    void repair_UsesTheSharedRepair_FullHealthNotDestroyed() {
        CachedGateDoor west = gates.getGate(21);
        west.setHealthCurrent(0);
        west.setIsDestroyed(true);

        Result result = handler.handle(GATE, 21, Action.REPAIR);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(List.of(21), repaired);
        assertEquals(DoorOutcome.REPAIRED, result.doors().get(0).outcome());
        assertEquals(500.0, result.doors().get(0).healthCurrent());
        assertEquals(false, result.doors().get(0).destroyed());
    }

    @Test
    void repairWithoutADoor_RepairsEveryDoorOfTheGate() {
        handler.handle(GATE, null, Action.REPAIR);
        assertEquals(List.of(21, 22), repaired);
    }

    // ===== per-door rules as in game =====

    @Test
    void aDestroyedDoor_DoesNotOpen_Refused() {
        gates.getGate(21).setIsDestroyed(true);

        Result result = handler.handle(GATE, 21, Action.OPEN);

        assertEquals(Outcome.REFUSED, result.outcome());
        assertEquals("destroyed", result.doors().get(0).reason());
        assertEquals(AnimationState.CLOSED, state(21));
    }

    @Test
    void anInactiveDoor_IsSkipped_TheOthersStillRun() {
        gates.getGate(22).setIsActive(false);

        Result result = handler.handle(GATE, null, Action.OPEN);

        assertEquals(Outcome.DONE, result.outcome());
        assertEquals(AnimationState.OPENING, state(21));
        assertEquals(AnimationState.CLOSED, state(22));
        assertEquals("not active", result.doors().get(1).reason());
        assertTrue(result.message().contains("East skipped (not active)"));
    }

    // ===== refusals (A19, spec § 5 rule 11) =====

    @Test
    void underASiegeLockdown_IsRefusedAsSiege_AndNothingChanges() {
        lockedGates.add(GATE);
        gates.getGate(21).setIsDestroyed(true);

        for (Action action : Action.values()) {
            Result result = handler.handle(GATE, null, action);
            assertEquals(Outcome.SIEGE, result.outcome());
            assertEquals("Gate is in a running siege.", result.message());
        }
        assertEquals(AnimationState.CLOSED, state(21));
        assertEquals(AnimationState.CLOSED, state(22));
        assertTrue(repaired.isEmpty());
    }

    @Test
    void aSiegeOnAnotherGate_DoesNotBlock() {
        lockedGates.add(OTHER_GATE);
        assertEquals(Outcome.DONE, handler.handle(GATE, 21, Action.OPEN).outcome());
    }

    @Test
    void aDoorOfAnotherGate_OrAnUnknownDoor_IsRefused_NothingMoves() {
        Result foreign = handler.handle(GATE, 31, Action.OPEN);
        assertEquals(Outcome.REFUSED, foreign.outcome());
        assertEquals("Door 31 is not a loaded door of gate 12 on this server.", foreign.message());
        assertEquals(AnimationState.CLOSED, state(31));

        assertEquals(Outcome.REFUSED, handler.handle(GATE, 999, Action.OPEN).outcome());
    }

    @Test
    void aGateWithoutLoadedDoors_IsRefused() {
        Result result = handler.handle(77, null, Action.OPEN);
        assertEquals(Outcome.REFUSED, result.outcome());
        assertTrue(result.doors().isEmpty());
    }

    @Test
    void anUnknownAction_IsRefused() {
        assertEquals(Outcome.REFUSED, handler.handle(GATE, 21, null).outcome());
    }

    @Test
    void actionsParseFromTheWire() {
        assertEquals(Action.OPEN, Action.parse("open"));
        assertEquals(Action.REPAIR, Action.parse(" Repair "));
        assertNull(Action.parse("explode"));
        assertNull(Action.parse(null));
        assertEquals("toggle", Action.TOGGLE.wire());
        assertEquals("siege", Outcome.SIEGE.wire());
    }
}
