package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiegeGateStanceTest {

    @Test
    void anUnlockedGateIsLeftToTheNormalRules() {
        assertEquals(new SiegeGateStance(false, false, false), SiegeGateStance.of(false, true, true, true));
        assertEquals(new SiegeGateStance(false, false, false), SiegeGateStance.of(false, false, false, false));
    }

    @Test
    void aMemberOpposingTheGatesOwnersIsBlockedByItWhileItIsClosed() {
        // the attacker's route to the main objective behind a closed gate objective must not pass it
        assertEquals(new SiegeGateStance(true, false, false), SiegeGateStance.of(true, true, false, true));
    }

    @Test
    void aMemberIsNeverCarriedThroughEvenWhenTheNonMemberRuleWouldCarry() {
        assertEquals(false, SiegeGateStance.of(true, true, false, true).siegeCarries());
    }

    @Test
    void theOwnersOfALockedGateCanOpenItThemselves() {
        assertEquals(new SiegeGateStance(false, false, true), SiegeGateStance.of(true, true, true, false));
    }

    @Test
    void aNonMemberKeepsTheSiegesCarryRule() {
        assertEquals(new SiegeGateStance(true, true, false), SiegeGateStance.of(true, false, false, true));
        assertEquals(new SiegeGateStance(true, false, false), SiegeGateStance.of(true, false, false, false));
    }
}
