package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeGatePlan.Control;
import net.knightsandkings.knk.core.siege.SiegeGatePlan.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege Phase 7a: gate roles and permissions (DESIGN §8.1). Teams: Defender 1 (alliance 1),
 * Attackers 2 and 3 (alliance 2), Mercenaries 4 (alliance 3). Gate 10 = selected objective gate
 * (objective 2, closes on capture), gate 11 = selected, not damageable, starts open, owned by 4;
 * gates 20/21 = other gates in the area. The area list also repeats 10, to prove the selected role wins.
 */
class SiegeGatePlanTest {

    private static KnkSiegeScenario scenario() {
        var teams = List.of(team(1, SiegeTeamRole.DEFENDER, 1, "Garrison"), team(2, SiegeTeamRole.ATTACKER, 2, "Raiders"),
                team(3, SiegeTeamRole.ATTACKER, 2, "Allies"), team(4, SiegeTeamRole.ATTACKER, 3, "Mercs"));
        var objectives = List.of(
                new KnkSiegeObjective(1, 0, "Keep", null, null, 500, 2.5, true, 1, true, SiegeGateState.OPEN),
                new KnkSiegeObjective(2, 1, "Gatehouse", null, 10, 500, 2.5, false, 1, true, SiegeGateState.CLOSED));
        var gates = List.of(
                new KnkSiegeGate(10, "Main gate", 1, SiegeGateState.CLOSED, true, true),
                new KnkSiegeGate(11, "Postern", 4, SiegeGateState.OPEN, false, false));
        return new KnkSiegeScenario(100, "S", null, 1, "Town", null, List.of(), null, 2, 20, null, null,
                KnkSiegeMatchLength.DEFAULT, null, true, true, true, teams, objectives, gates, List.of(20, 21, 10));
    }

    private final KnkSiegeScenario scenario = scenario();
    private final AllianceResolver alliances = AllianceResolver.of(scenario);

    @Test
    void rolesAndLockdownState() {
        SiegeGatePlan plan = SiegeGatePlan.of(scenario);

        assertEquals(List.of(10, 11, 20, 21), plan.entries().stream().map(SiegeGatePlan.Entry::gateStructureId).toList());
        var main = plan.entry(10).orElseThrow();
        assertEquals(Role.SELECTED, main.role());
        assertTrue(main.objectiveGate());
        assertFalse(main.invincible());
        assertFalse(main.initialOpen());
        var postern = plan.entry(11).orElseThrow();
        assertTrue(postern.invincible());   // not damageable
        assertTrue(postern.initialOpen());
        var area = plan.entry(20).orElseThrow();
        assertEquals(Role.AREA, area.role());
        assertTrue(area.invincible());
        assertTrue(area.forcedOpen());
        assertTrue(area.initialOpen());
        assertEquals(OptionalInt.empty(), plan.owner(20));
        assertFalse(plan.contains(99));
    }

    @Test
    void control_ownerAllianceOnly_areaGatesNobody() {
        SiegeGatePlan plan = SiegeGatePlan.of(scenario);

        assertEquals(Control.ALLOWED, plan.canControl(10, 1, alliances));
        assertEquals(Control.NOT_OWNER, plan.canControl(10, 2, alliances));
        assertEquals(Control.NOT_IN_MATCH, plan.canControl(10, null, alliances));
        assertEquals(Control.ALLOWED, plan.canControl(11, 4, alliances));
        assertEquals(Control.FORCED_OPEN, plan.canControl(20, 1, alliances));
        assertEquals(Control.NOT_IN_PLAN, plan.canControl(99, 1, alliances));
    }

    @Test
    void damage_enemiesOfTheOwner_onDamageableSelectedGatesOnly() {
        SiegeGatePlan plan = SiegeGatePlan.of(scenario);

        assertTrue(plan.canDamage(10, 2, alliances));
        assertTrue(plan.canDamage(10, 4, alliances));
        assertFalse(plan.canDamage(10, 1, alliances));    // the owner
        assertFalse(plan.canDamage(10, null, alliances)); // non-member / no player
        assertFalse(plan.canDamage(11, 1, alliances));    // not damageable
        assertFalse(plan.canDamage(20, 2, alliances));    // area gate
    }

    @Test
    void capture_handsTheObjectiveGateOver_everyTime() {
        SiegeGatePlan plan = SiegeGatePlan.of(scenario);

        var transfer = plan.onCapture(2, 3).orElseThrow();
        assertEquals(new SiegeGatePlan.Transfer(10, 3, false), transfer); // GateStateOnCapture = CLOSED
        assertEquals(OptionalInt.of(3), plan.owner(10));
        assertEquals(Control.ALLOWED, plan.canControl(10, 2, alliances));  // ally of the new owner
        assertEquals(Control.NOT_OWNER, plan.canControl(10, 1, alliances));
        assertTrue(plan.canDamage(10, 1, alliances));                      // now the defenders are the enemy

        assertEquals(new SiegeGatePlan.Transfer(10, 1, false), plan.onCapture(2, 1).orElseThrow()); // recapture
        assertTrue(plan.onCapture(1, 2).isEmpty());   // objective without a gate
        assertTrue(plan.onCapture(99, 2).isEmpty());  // unknown objective
    }
}
