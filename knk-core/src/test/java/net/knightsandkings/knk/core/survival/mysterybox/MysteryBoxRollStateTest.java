package net.knightsandkings.knk.core.survival.mysterybox;

import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.ClaimOutcome;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.ForfeitReason;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxRollState.Phase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysteryBoxRollStateTest {

    private final UUID roller = UUID.randomUUID();
    private final UUID mate = UUID.randomUUID();
    private final MysteryBoxRollState roll = new MysteryBoxRollState("m1-p2-x", roller, 10_000);

    @Test
    void onlyTheRollerClaimsAndOnlyOnceTheReelStopped() {
        assertEquals(ClaimOutcome.NOT_SHOWN_YET, roll.claim(roller, 1_000));
        assertTrue(roll.animationDone(5_000));
        assertFalse(roll.animationDone(5_001));
        assertEquals(ClaimOutcome.NOT_ROLLER, roll.claim(mate, 6_000));
        assertEquals(4_000, roll.remainingClaimMillis(11_000));
        assertEquals(ClaimOutcome.CLAIMED, roll.claim(roller, 6_000));
        assertEquals(Phase.CLAIMED, roll.phase());
        assertTrue(roll.over());
        assertEquals(ClaimOutcome.ALREADY_OVER, roll.claim(roller, 6_001));
        assertFalse(roll.forfeit(ForfeitReason.TIMEOUT));
        assertTrue(roll.forfeitReason().isEmpty());
    }

    @Test
    void unclaimedItemsTimeOut() {
        roll.animationDone(5_000);
        assertTrue(roll.tick(14_999).isEmpty());
        assertEquals(ForfeitReason.TIMEOUT, roll.tick(15_000).orElseThrow());
        assertEquals(Phase.FORFEITED, roll.phase());
        assertEquals(ClaimOutcome.ALREADY_OVER, roll.claim(roller, 15_001));
        assertEquals(0, roll.remainingClaimMillis(15_001));
    }

    @Test
    void aLateClaimAtTheTimeoutForfeits() {
        roll.animationDone(5_000);
        assertEquals(ClaimOutcome.ALREADY_OVER, roll.claim(roller, 15_000));
        assertEquals(ForfeitReason.TIMEOUT, roll.forfeitReason().orElseThrow());
    }

    @Test
    void deathLeaveAndMatchEndForfeitDuringTheAnimationAndWhileShown() {
        assertTrue(roll.forfeit(ForfeitReason.ROLLER_DIED));
        assertEquals(ForfeitReason.ROLLER_DIED, roll.forfeitReason().orElseThrow());
        assertFalse(roll.animationDone(2_000));
        MysteryBoxRollState shown = new MysteryBoxRollState("r2", roller, 10_000);
        shown.animationDone(1_000);
        assertTrue(shown.forfeit(ForfeitReason.MATCH_ENDED));
        assertFalse(shown.forfeit(ForfeitReason.ROLLER_LEFT));
        assertEquals(ForfeitReason.MATCH_ENDED, shown.forfeitReason().orElseThrow());
        assertTrue(shown.tick(999_999).isEmpty());
    }

    @Test
    void timeoutDefaultsAndConcern() {
        MysteryBoxRollState defaults = new MysteryBoxRollState("r", roller, 0);
        assertEquals(MysteryBoxSettings.DEFAULT_CLAIM_TIMEOUT_MILLIS, defaults.claimTimeoutMillis());
        assertTrue(defaults.concerns(roller));
        assertFalse(defaults.concerns(mate));
    }
}
