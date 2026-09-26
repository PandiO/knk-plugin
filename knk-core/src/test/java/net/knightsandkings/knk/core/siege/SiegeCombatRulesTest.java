package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Combatant;
import net.knightsandkings.knk.core.siege.SiegeCombatRules.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.knightsandkings.knk.core.siege.SiegeTestData.objective;
import static net.knightsandkings.knk.core.siege.SiegeTestData.team;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: PvP rules (DESIGN §6.7) - alliances, safe zones, member vs non-member, headshots. */
class SiegeCombatRulesTest {

    // Defenders 1 (alliance 1), attackers 2 and 3 allied (alliance 2), rival attackers 4 (alliance 3).
    private final KnkSiegeScenario scenario = SiegeTestData.scenario(1, 5,
            List.of(team(1, SiegeTeamRole.DEFENDER, 1, "D"), team(2, SiegeTeamRole.ATTACKER, 2, "A"),
                    team(3, SiegeTeamRole.ATTACKER, 2, "A2"), team(4, SiegeTeamRole.ATTACKER, 3, "R")),
            List.of(objective(1, true, 1)), false);
    private final AllianceResolver alliances = AllianceResolver.of(scenario);

    private static Combatant in(int lobby, int team) {
        return new Combatant(lobby, true, team, false);
    }

    @Test
    void nobodyInASiegeIsNotOurBusiness() {
        assertEquals(Outcome.NOT_SIEGE, SiegeCombatRules.decide(null, null, null));
    }

    @Test
    void enemiesInTheSameRunningMatchMayFight() {
        assertEquals(Outcome.ALLOW, SiegeCombatRules.decide(in(1, 2), in(1, 1), alliances));
        assertEquals(Outcome.ALLOW, SiegeCombatRules.decide(in(1, 4), in(1, 2), alliances), "rival attackers are enemies too");
    }

    @Test
    void alliesAndTeammatesCannot() {
        assertEquals(Outcome.DENY_ALLY, SiegeCombatRules.decide(in(1, 2), in(1, 3), alliances));
        assertEquals(Outcome.DENY_ALLY, SiegeCombatRules.decide(in(1, 1), in(1, 1), alliances));
        assertEquals(Outcome.DENY_ALLY, SiegeCombatRules.decide(in(1, 1), in(1, 2), null), "no alliances known: deny");
    }

    @Test
    void memberAndNonMemberCanNeverHurtEachOther() {
        assertEquals(Outcome.DENY_MEMBER_VS_NON_MEMBER, SiegeCombatRules.decide(null, in(1, 1), alliances));
        assertEquals(Outcome.DENY_MEMBER_VS_NON_MEMBER, SiegeCombatRules.decide(in(1, 1), null, alliances));
        assertEquals(Outcome.DENY_MEMBER_VS_NON_MEMBER, SiegeCombatRules.decide(in(1, 2), in(2, 1), alliances),
                "members of different lobbies' matches");
    }

    @Test
    void safeZonesProtectBothWays() {
        Combatant safeDefender = new Combatant(1, true, 1, true);
        Combatant safeAttacker = new Combatant(1, true, 2, true);
        assertEquals(Outcome.DENY_VICTIM_SAFE, SiegeCombatRules.decide(in(1, 2), safeDefender, alliances));
        assertEquals(Outcome.DENY_ATTACKER_SAFE, SiegeCombatRules.decide(safeAttacker, in(1, 1), alliances));
    }

    @Test
    void noFightingInTheHub() {
        Combatant hub = new Combatant(1, false, -1, false);
        assertEquals(Outcome.DENY_NOT_STARTED, SiegeCombatRules.decide(hub, hub, alliances));
        assertTrue(Outcome.DENY_NOT_STARTED.denied());
        assertFalse(Outcome.ALLOW.denied());
        assertFalse(Outcome.NOT_SIEGE.denied());
    }

    @Test
    void headshotIsAboveTheBodyLineAndTheMultiplierCanBeDisabled() {
        assertTrue(SiegeCombatRules.isHeadshot(65.5, 64.0));
        assertFalse(SiegeCombatRules.isHeadshot(65.33, 64.0));
        assertFalse(SiegeCombatRules.isHeadshot(64.9, 64.0));
        assertEquals(9.0, SiegeCombatRules.headshotDamage(6.0, 1.5), 1e-9);
        assertEquals(6.0, SiegeCombatRules.headshotDamage(6.0, 1.0), 1e-9);
        assertEquals(6.0, SiegeCombatRules.headshotDamage(6.0, Double.NaN), 1e-9);
    }

    @Test
    void safeZoneRadiusIsASphereAndZeroMeansNone() {
        assertTrue(SiegeCombatRules.withinRadius(3, 0, 0, 4));
        assertTrue(SiegeCombatRules.withinRadius(0, 0, 4, 4));
        assertFalse(SiegeCombatRules.withinRadius(3, 3, 0, 4));
        assertFalse(SiegeCombatRules.withinRadius(0, 0, 0, 0));
    }
}
