package net.knightsandkings.knk.core.duel;

import net.knightsandkings.knk.core.duel.DuelCombatRules.Combatant;
import net.knightsandkings.knk.core.duel.DuelCombatRules.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelCombatRulesTest {

    private static Combatant in(String token, DuelPhase phase) {
        return new Combatant(token, phase, phase != DuelPhase.FIGHT);
    }

    @Test
    void theFightingPairIsAllowed() {
        assertEquals(Outcome.ALLOW, DuelCombatRules.decide(in("m1", DuelPhase.FIGHT), in("m1", DuelPhase.FIGHT)));
        assertTrue(DuelCombatRules.overrideWorldGuardPvpBlock(in("m1", DuelPhase.FIGHT), in("m1", DuelPhase.FIGHT)));
    }

    @ParameterizedTest
    @EnumSource(value = DuelPhase.class, names = {"LOCKING", "TRAVELLING", "WARMUP", "BATTLE_COUNTDOWN", "ENDING"})
    void theSameMatchOutsideFightIsDenied(DuelPhase phase) {
        assertEquals(Outcome.DENY_NOT_FIGHTING, DuelCombatRules.decide(in("m1", phase), in("m1", phase)));
        assertFalse(DuelCombatRules.overrideWorldGuardPvpBlock(in("m1", phase), in("m1", phase)));
    }

    @Test
    void aLockedFightIsDenied() {
        Combatant locked = new Combatant("m1", DuelPhase.FIGHT, true);
        assertEquals(Outcome.DENY_NOT_FIGHTING, DuelCombatRules.decide(locked, in("m1", DuelPhase.FIGHT)));
    }

    @ParameterizedTest
    @EnumSource(value = DuelPhase.class, names = "CLOSED", mode = EnumSource.Mode.EXCLUDE)
    void aParticipantAndAnOutsiderAreDeniedBothWays(DuelPhase phase) {
        assertEquals(Outcome.DENY_OUTSIDE_MATCH, DuelCombatRules.decide(in("m1", phase), null));
        assertEquals(Outcome.DENY_OUTSIDE_MATCH, DuelCombatRules.decide(null, in("m1", phase)));
        assertEquals(Outcome.DENY_OUTSIDE_MATCH, DuelCombatRules.decide(in("m1", phase), in("m2", DuelPhase.FIGHT)));
    }

    @Test
    void neitherInAMatchIsUntouched() {
        assertEquals(Outcome.NOT_DUEL, DuelCombatRules.decide(null, null));
        assertEquals(Outcome.NOT_DUEL, DuelCombatRules.decide(in("m1", DuelPhase.CLOSED), null));
        assertFalse(Outcome.NOT_DUEL.denied());
    }

    @Test
    void combatantFromStateMachine() {
        DuelStateMachine machine = new DuelStateMachine(new DuelTimings(0, 0, 0, 0, 15), GearMode.OWN_GEAR_KEEP, null, java.util.List.of());
        Combatant c = Combatant.of("m1", machine);
        assertEquals(DuelPhase.LOCKING, c.phase());
        assertTrue(c.damageLocked());
    }
}
