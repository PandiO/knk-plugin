package net.knightsandkings.knk.paper.siege.survival;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetEligibilityRulesTest {

    @Test
    void ghostsAndDeadPlayersFailHardEligibility() {
        assertTrue(TargetEligibilityRules.player(true, true, true, false).eligible());
        assertEquals("ghost", TargetEligibilityRules.player(true, true, true, true).reason());
        assertEquals("dead", TargetEligibilityRules.player(true, true, false, false).reason());
        assertEquals("not-in-match", TargetEligibilityRules.player(true, false, true, false).reason());
        assertEquals("offline", TargetEligibilityRules.player(false, true, true, false).reason());
    }

    @Test
    void objectivesBehindAClosedGateOrNpcHeldOrDeniedAreOut() {
        assertTrue(TargetEligibilityRules.objective(TargetOverrideMode.STANDARD, false, Optional.of(false), true).eligible());
        assertTrue(TargetEligibilityRules.objective(TargetOverrideMode.STANDARD, false, Optional.empty(), true).eligible());
        assertEquals("behind-closed-gate", TargetEligibilityRules.objective(TargetOverrideMode.STANDARD, false, Optional.of(true), true).reason());
        assertEquals("npc-held", TargetEligibilityRules.objective(TargetOverrideMode.STANDARD, true, Optional.empty(), true).reason());
        assertEquals("denied", TargetEligibilityRules.objective(TargetOverrideMode.DENY, false, Optional.empty(), true).reason());
        assertEquals("behind-closed-gate", TargetEligibilityRules.objective(TargetOverrideMode.ALLOW, false, Optional.of(true), true).reason(),
            "allow doesn't grant physical access");
        assertEquals("no-capture-point", TargetEligibilityRules.objective(TargetOverrideMode.STANDARD, false, Optional.empty(), false).reason());
    }

    @Test
    void gatesFollowStandardAllowAndDenyIndependently() {
        assertTrue(TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, true, true, true, false, true).eligible());
        assertEquals("open", TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, false, true, true, false, true).reason());
        assertEquals("not-damageable", TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, true, false, true, false, true).reason());
        assertEquals("area-gate", TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, true, true, false, false, true).reason());
        assertTrue(TargetEligibilityRules.gate(TargetOverrideMode.ALLOW, true, true, false, false, true).eligible(), "allow: an area gate becomes a target");
        assertEquals("objective-npc-held", TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, true, true, true, true, true).reason());
        assertEquals("denied", TargetEligibilityRules.gate(TargetOverrideMode.DENY, true, true, true, false, true).reason());
        assertEquals("open", TargetEligibilityRules.gate(TargetOverrideMode.ALLOW, false, true, false, false, true).reason(),
            "allow doesn't make an open gate a target");
        assertFalse(TargetEligibilityRules.gate(TargetOverrideMode.STANDARD, true, true, true, false, false).eligible());
    }
}
