package net.knightsandkings.knk.core.survival.result;

import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §9 v1 (S32, S62): settle once, floor once, present players only, leavers and aborts 0. */
class SurvivalResultRulesTest {

    @Test
    void wireNamesRoundTrip() {
        for (SurvivalEndReason reason : SurvivalEndReason.values()) {
            String code = SurvivalResultRules.endReasonCode(reason);
            assertFalse(code.isBlank());
            assertEquals(reason, SurvivalResultRules.endReasonOf(code));
        }
        assertEquals("TeamWipe", SurvivalResultRules.endReasonCode(SurvivalEndReason.TEAM_WIPE));
        assertEquals("InstaLoseObjectiveCaptured", SurvivalResultRules.endReasonCode(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED));
        assertEquals("AllObjectivesCaptured", SurvivalResultRules.endReasonCode(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED));
        assertEquals("NoPlayersLeft", SurvivalResultRules.endReasonCode(SurvivalEndReason.NO_PLAYERS_LEFT));
        assertEquals("AdminAbort", SurvivalResultRules.endReasonCode(SurvivalEndReason.ADMIN_ABORT));
        assertEquals("ServerRestart", SurvivalResultRules.endReasonCode(SurvivalEndReason.SERVER_RESTART));
        assertEquals("TechnicalLimit", SurvivalResultRules.endReasonCode(SurvivalEndReason.TECHNICAL_LIMIT));
        assertNull(SurvivalResultRules.endReasonOf("Nope"));
        assertNull(SurvivalResultRules.endReasonOf(null));
    }

    @Test
    void onlyTheThreeGameplayEndsSettle() {
        assertTrue(SurvivalResultRules.settlesRewards(SurvivalEndReason.TEAM_WIPE));
        assertTrue(SurvivalResultRules.settlesRewards(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED));
        assertTrue(SurvivalResultRules.settlesRewards(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED));
        assertFalse(SurvivalResultRules.settlesRewards(SurvivalEndReason.NO_PLAYERS_LEFT));
        assertFalse(SurvivalResultRules.settlesRewards(SurvivalEndReason.ADMIN_ABORT));
        assertFalse(SurvivalResultRules.settlesRewards(SurvivalEndReason.SERVER_RESTART));
        assertFalse(SurvivalResultRules.settlesRewards(SurvivalEndReason.TECHNICAL_LIMIT));
        assertFalse(SurvivalResultRules.settlesRewards(null));
    }

    @Test
    void ledgerKeyOnlyWhenTheResultSettles() {
        assertEquals("siege-survival:42", SurvivalResultRules.ledgerKey(42, SurvivalEndReason.TEAM_WIPE));
        assertNull(SurvivalResultRules.ledgerKey(42, SurvivalEndReason.ADMIN_ABORT));
        assertNull(SurvivalResultRules.ledgerKey(42, SurvivalEndReason.NO_PLAYERS_LEFT));
    }

    @Test
    void settledIsTheFloorOfTheExactAccrualOnce() {
        assertEquals(9, SurvivalResultRules.settled(new BigDecimal("9.9"), SurvivalEndReason.TEAM_WIPE, true), "3 × 3.3 xp = 9.9 → 9, not 10 or 12");
        assertEquals(33, SurvivalResultRules.settled(new BigDecimal("33.0"), SurvivalEndReason.ALL_OBJECTIVES_CAPTURED, true));
        assertEquals(0, SurvivalResultRules.settled(new BigDecimal("0.9"), SurvivalEndReason.TEAM_WIPE, true));
        assertEquals(0, SurvivalResultRules.settled(BigDecimal.ZERO, SurvivalEndReason.TEAM_WIPE, true));
        assertEquals(0, SurvivalResultRules.settled(null, SurvivalEndReason.TEAM_WIPE, true));
    }

    @Test
    void leaversAndAbortsSettleNothing() {
        assertEquals(0, SurvivalResultRules.settled(new BigDecimal("99.9"), SurvivalEndReason.TEAM_WIPE, false), "leaver forfeits (S62)");
        assertEquals(0, SurvivalResultRules.settled(new BigDecimal("99.9"), SurvivalEndReason.ADMIN_ABORT, true));
        assertEquals(0, SurvivalResultRules.settled(new BigDecimal("99.9"), SurvivalEndReason.SERVER_RESTART, true));
        assertEquals(0, SurvivalResultRules.settled(new BigDecimal("99.9"), SurvivalEndReason.NO_PLAYERS_LEFT, true));
    }
}
