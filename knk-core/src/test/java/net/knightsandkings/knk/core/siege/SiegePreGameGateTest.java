package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Click;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Pre;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.Real;
import net.knightsandkings.knk.core.siege.SiegePreGameGate.View;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §8.5 (PandiO/knk-workspace#213): pre-game gate view, click and crossing rules for non-members. */
class SiegePreGameGateTest {

    // ==================== view ====================

    @Test
    void preClosedDoorThatIsReallyClosedNeedsNoFakes() {
        assertEquals(View.NONE, SiegePreGameGate.viewOf(Pre.CLOSED, Real.CLOSED));
    }

    @Test
    void preClosedDoorHeldOpenLooksClosedAndHidesTheOpenFrame() {
        View view = SiegePreGameGate.viewOf(Pre.CLOSED, Real.OPEN);
        assertTrue(view.fakeClosedFrame());
        assertTrue(view.hideOpenOnly());
        assertFalse(view.hideClosedOnly());
        assertFalse(view.carryOnSolid());
    }

    @Test
    void preClosedDoorDestroyedInTheSiegeLooksClosed() {
        View view = SiegePreGameGate.viewOf(Pre.CLOSED, Real.DESTROYED);
        assertTrue(view.fakeClosedFrame());
        assertFalse(view.hideOpenOnly()); // nothing of the open frame is there to hide
    }

    @Test
    void preOpenDoorClosedBySiegeIsHiddenAndCarries() {
        View view = SiegePreGameGate.viewOf(Pre.OPEN, Real.CLOSED);
        assertFalse(view.fakeClosedFrame());
        assertTrue(view.hideClosedOnly());
        assertTrue(view.carryOnSolid());
        assertTrue(SiegePreGameGate.viewOf(Pre.OPEN, Real.MOVING).carryOnSolid());
    }

    @Test
    void preOpenDoorThatIsOpenOrDestroyedNeedsNoFakes() {
        assertEquals(View.NONE, SiegePreGameGate.viewOf(Pre.OPEN, Real.OPEN));
        assertEquals(View.NONE, SiegePreGameGate.viewOf(Pre.OPEN, Real.DESTROYED));
    }

    @Test
    void doorDestroyedBeforeTheMatchShowsNothingOfTheDoor() {
        View view = SiegePreGameGate.viewOf(Pre.DESTROYED, Real.CLOSED);
        assertTrue(view.hideClosedOnly());
        assertTrue(view.hideOpenOnly());
        assertFalse(view.fakeClosedFrame());
        assertTrue(view.carryOnSolid());
        assertEquals(View.NONE, SiegePreGameGate.viewOf(Pre.DESTROYED, Real.DESTROYED));
    }

    @Test
    void onlyPreClosedDoorsBlock() {
        assertTrue(SiegePreGameGate.blocks(Pre.CLOSED));
        assertFalse(SiegePreGameGate.blocks(Pre.OPEN));
        assertFalse(SiegePreGameGate.blocks(Pre.DESTROYED));
    }

    @Test
    void preOpenWithDestroyedFlagParsesAsDestroyed() {
        assertEquals(Pre.DESTROYED, Pre.of(true, true));
        assertEquals(Pre.OPEN, Pre.of(true, false));
        assertEquals(Pre.CLOSED, Pre.of(false, false));
    }

    // ==================== click ====================

    @Test
    void preClosedDoorRefusesPlayersWithoutPreGameRights() {
        assertEquals(Click.DENY, SiegePreGameGate.onClick(Pre.CLOSED, Real.OPEN, false));
        assertEquals(Click.DENY, SiegePreGameGate.onClick(Pre.CLOSED, Real.CLOSED, false));
    }

    @Test
    void preClosedDoorOpensPerPlayerWhenReallyOpenOtherwiseCarries() {
        assertEquals(Click.OPEN_FOR_PLAYER, SiegePreGameGate.onClick(Pre.CLOSED, Real.OPEN, true));
        assertEquals(Click.OPEN_FOR_PLAYER, SiegePreGameGate.onClick(Pre.CLOSED, Real.DESTROYED, true));
        assertEquals(Click.CARRY, SiegePreGameGate.onClick(Pre.CLOSED, Real.CLOSED, true));
        assertEquals(Click.CARRY, SiegePreGameGate.onClick(Pre.CLOSED, Real.MOVING, true));
    }

    @Test
    void preOpenDoorCarriesOnlyWhenReallySolid() {
        assertEquals(Click.CARRY, SiegePreGameGate.onClick(Pre.OPEN, Real.CLOSED, false));
        assertEquals(Click.IGNORE, SiegePreGameGate.onClick(Pre.OPEN, Real.OPEN, false));
        assertEquals(Click.IGNORE, SiegePreGameGate.onClick(Pre.DESTROYED, Real.DESTROYED, false));
    }

    // ==================== crossing ====================

    /** A 1-block-thick wall at x = 10, y 64..67, z 0..3 (a closed door frame). */
    private static LongPredicate wall() {
        Set<Long> cells = new HashSet<>();
        for (int y = 64; y <= 67; y++) {
            for (int z = 0; z <= 3; z++) cells.add(GateSpatialIndex.packCell(10, y, z));
        }
        return cells::contains;
    }

    @Test
    void walkingIntoTheFrameCrosses() {
        assertTrue(SiegePreGameGate.crosses(9.5, 64, 1.5, 9.8, 64, 1.5, wall()));
    }

    @Test
    void walkingUpToTheFaceDoesNot() {
        // Hitbox edge at x = 9.7 + 0.3 = 10.0 touches the face only.
        assertFalse(SiegePreGameGate.crosses(9.5, 64, 1.5, 9.7, 64, 1.5, wall()));
    }

    @Test
    void aFastMoveThatSkipsTheFrameStillCrosses() {
        assertTrue(SiegePreGameGate.crosses(8.0, 64, 1.5, 12.0, 64, 1.5, wall()));
    }

    @Test
    void movingAlongsideOrAboveTheFrameDoesNot() {
        assertFalse(SiegePreGameGate.crosses(9.5, 64, -2.0, 9.5, 64, 6.0, wall()));
        assertFalse(SiegePreGameGate.crosses(8.0, 68, 1.5, 12.0, 68, 1.5, wall())); // over the top
    }

    @Test
    void aDiagonalMoveThroughTheFrameCrosses() {
        assertTrue(SiegePreGameGate.crosses(9.0, 64, 0.5, 11.0, 64, 2.5, wall()));
    }

    @Test
    void aPlayerAlreadyInsideTheFrameCanStepOut() {
        assertFalse(SiegePreGameGate.crosses(10.5, 64, 1.5, 9.0, 64, 1.5, wall()));
    }

    @Test
    void aTeleportAcrossTheFrameCrosses() {
        assertTrue(SiegePreGameGate.crosses(0.5, 64, 1.5, 30.5, 64, 1.5, wall()));
        assertFalse(SiegePreGameGate.crosses(0.5, 64, 1.5, 9.0, 64, 1.5, wall()));
    }

    @Test
    void overlapsReportsAPlayerStandingInTheFrame() {
        assertTrue(SiegePreGameGate.overlaps(10.5, 64, 1.5, wall()));
        assertFalse(SiegePreGameGate.overlaps(8.5, 64, 1.5, wall()));
    }
}
