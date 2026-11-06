package net.knightsandkings.knk.core.targeting;

import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Action;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Decision;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.Observation;
import net.knightsandkings.knk.core.targeting.EnemyGoalMachine.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnemyGoalMachineTest {

    private static final TargetCandidate GATE = TargetCandidate.eligible("gate:7", TargetKind.GATE, 20, 64, 0);
    private static final TargetCandidate OBJECTIVE = TargetCandidate.eligible("objective:2", TargetKind.OBJECTIVE, 40, 64, 0);

    private static Observation at(double x, List<TargetCandidate> candidates, boolean arrived, boolean blocked, long now) {
        return new Observation(x, 64, 0, candidates, arrived, blocked, false, now);
    }

    @Test
    void approachesTheGateThenBreachesOnArrival() {
        EnemyGoalMachine m = new EnemyGoalMachine(TargetPolicy.defaults(), 0, 64, 0);
        Decision d = m.observe(at(0, List.of(GATE, OBJECTIVE), false, false, 0));
        assertEquals(State.APPROACHING, d.state());
        assertInstanceOf(Action.MoveTo.class, d.action());
        assertTrue(d.targetChanged());
        assertEquals("gate:7", d.target().orElseThrow().id());

        d = m.observe(at(19, List.of(GATE, OBJECTIVE), true, false, 500));
        assertEquals(State.BREACHING, d.state());
        assertInstanceOf(Action.HitGate.class, d.action());
        assertFalse(d.targetChanged());
    }

    @Test
    void capturesWhenTheGateIsGoneAndTheObjectiveBecomesTheTarget() {
        EnemyGoalMachine m = new EnemyGoalMachine(TargetPolicy.defaults(), 0, 64, 0);
        m.observe(at(0, List.of(GATE, OBJECTIVE), false, false, 0));
        // The door is destroyed: the gate is no longer eligible, the objective behind it is.
        TargetCandidate open = TargetCandidate.denied("gate:7", TargetKind.GATE, 20, 64, 0, "open");
        Decision d = m.observe(at(19, List.of(open, OBJECTIVE), true, false, 1_000));
        assertEquals("objective:2", d.target().orElseThrow().id());
        assertTrue(d.targetChanged());
        assertInstanceOf(Action.MoveTo.class, d.action(), "arrival at the old target doesn't count for the new one");
        d = m.observe(at(40, List.of(open, OBJECTIVE), true, false, 2_000));
        assertEquals(State.CAPTURING, d.state());
        assertInstanceOf(Action.Capture.class, d.action());
    }

    @Test
    void playersInAggroRangeDivertAndTheMachineAttacks() {
        EnemyGoalMachine m = new EnemyGoalMachine(TargetPolicy.defaults().withAggroRadius(10), 0, 64, 0);
        m.observe(at(0, List.of(GATE), false, false, 0));
        TargetCandidate player = TargetCandidate.eligible("player:p", TargetKind.PLAYER, 3, 64, 0);
        Decision d = m.observe(at(0, List.of(GATE, player), false, false, 10_000));
        assertEquals(State.ATTACKING, d.state());
        assertInstanceOf(Action.AttackPlayer.class, d.action());
        // The player became a ghost: released at once, back to the gate.
        TargetCandidate ghost = TargetCandidate.denied("player:p", TargetKind.PLAYER, 3, 64, 0, "ghost");
        d = m.observe(at(0, List.of(GATE, ghost), false, false, 10_100));
        assertEquals("gate:7", d.target().orElseThrow().id());
        assertTrue(d.targetChanged());
    }

    @Test
    void boundedRetriesThenTheBlockedTargetIsPutAsideForACoolDown() {
        EnemyGoalMachine m = new EnemyGoalMachine(TargetPolicy.defaults(), 0, 64, 0, 2, 5_000);
        m.observe(at(0, List.of(GATE, OBJECTIVE), false, false, 0));
        assertEquals("gate:7", m.observe(at(0, List.of(GATE, OBJECTIVE), false, true, 100)).target().orElseThrow().id());
        assertEquals(1, m.blockedRetries());
        assertEquals("gate:7", m.observe(at(0, List.of(GATE, OBJECTIVE), false, true, 200)).target().orElseThrow().id());
        assertEquals(2, m.blockedRetries());
        Decision d = m.observe(at(0, List.of(GATE, OBJECTIVE), false, true, 300));
        assertEquals("objective:2", d.target().orElseThrow().id(), "third failure: the gate is put aside");
        assertTrue(m.isBlocked("gate:7", 1_000));
        assertFalse(m.isBlocked("gate:7", 6_000));
        d = m.observe(at(0, List.of(GATE, OBJECTIVE), false, false, 1_000));
        assertEquals("objective:2", d.target().orElseThrow().id(), "the gate stays aside during the cool-down");
        d = m.observe(at(0, List.of(GATE, OBJECTIVE), false, false, 6_000));
        assertEquals("gate:7", d.target().orElseThrow().id(),
            "cool-down over and commitment expired: the much closer gate beats the switch margin again");
    }

    @Test
    void idleWhenNothingIsEligibleAndTheAnchorFollowsStructuralTargets() {
        EnemyGoalMachine m = new EnemyGoalMachine(TargetPolicy.defaults(), 5, 64, 5);
        assertEquals(5, m.anchor()[0]);
        Decision d = m.observe(at(0, List.of(), false, false, 0));
        assertEquals(State.IDLE, d.state());
        assertInstanceOf(Action.Idle.class, d.action());
        m.observe(at(0, List.of(GATE), false, false, 10));
        assertEquals(20, m.anchor()[0]);
        m.release();
        assertEquals(State.SELECTING, m.state());
        assertTrue(m.target().isEmpty());
    }
}
