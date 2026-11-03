package net.knightsandkings.knk.core.survival.repair;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §5, S07–S09, S48, S49: P(t+dt) = min(H, P + r·n·dt), ETA, carry-over, exactly-once completion. */
class DoorRepairWorkTest {
    private static final double EPS = 1e-9;

    @Test
    void designExampleTwentySecondsAloneTenWithTwo() {
        DoorRepairWork work = DoorRepairWork.destroyed("gate-a", 1000, 50);
        assertEquals(OptionalDouble.of(20), work.etaSeconds(1));
        assertEquals(OptionalDouble.of(10), work.etaSeconds(2));
        assertEquals(OptionalDouble.empty(), work.etaSeconds(0), "zero contributors: no ETA");
        for (int tick = 0; tick < 19; tick++) assertTrue(work.advance(1, 1).isEmpty());
        assertEquals(950, work.progress(), EPS);
        assertEquals(OptionalDouble.of(1), work.etaSeconds(1));
        RepairCompletion completion = work.advance(1, 1).orElseThrow();
        assertEquals(new RepairCompletion("gate-a", RepairCompletion.Kind.REBUILD_DESTROYED_DOOR, 1000), completion);
        assertTrue(work.awaitingRebuild());
        assertFalse(work.canRepair());
    }

    @Test
    void additiveContributorsAndZeroContributorsHoldProgress() {
        DoorRepairWork work = DoorRepairWork.destroyed("d", 1000, 50);
        work.advance(2, 4);
        assertEquals(400, work.progress(), EPS);
        work.advance(0, 100);
        assertEquals(400, work.progress(), EPS, "interruption retains work");
        assertEquals(0.4, work.fraction(), EPS);
        assertEquals(600, work.remaining(), EPS);
    }

    @Test
    void completionIsConsumedExactlyOnceAndRebuildStartsTheNextCycleAtZero() {
        DoorRepairWork work = DoorRepairWork.destroyed("d", 100, 10);
        assertTrue(work.advance(1, 10).isPresent());
        assertTrue(work.advance(1, 10).isEmpty(), "no second completion");
        assertEquals(100, work.progress(), EPS);
        work.rebuildComplete();
        assertEquals(100, work.currentHealth(), EPS);
        assertFalse(work.canRepair(), "full-health door cannot be repaired");
        work.doorDestroyed();
        assertEquals(0, work.progress(), EPS, "a new destruction starts at zero");
        assertTrue(work.canRepair());
    }

    @Test
    void damagedStandingDoorTargetIsMissingHealthRecomputedLive() {
        DoorRepairWork work = new DoorRepairWork("d", 1000, 50, 600);
        assertFalse(work.isDestroyed());
        assertEquals(400, work.target(), EPS);
        work.advance(1, 4);
        assertEquals(200, work.progress(), EPS);
        work.doorHealthChanged(500);
        assertEquals(500, work.target(), EPS, "damage during the repair adds to the remaining work");
        assertEquals(300, work.remaining(), EPS);
        assertEquals(OptionalDouble.of(6), work.etaSeconds(1));
        RepairCompletion completion = work.advance(1, 6).orElseThrow();
        assertEquals(RepairCompletion.Kind.RESTORE_DAMAGED_DOOR, completion.kind());
        assertEquals(1000, work.currentHealth(), EPS, "full health in one step");
        assertEquals(0, work.progress(), EPS);
        assertFalse(work.canRepair());
        work.doorHealthChanged(900);
        assertEquals(100, work.target(), EPS);
        assertEquals(0, work.progress(), EPS, "new damage: new cycle");
    }

    @Test
    void destroyedDuringRepairCarriesWorkOver() {
        DoorRepairWork work = new DoorRepairWork("d", 1000, 50, 700);
        work.advance(1, 4);
        assertEquals(200, work.progress(), EPS);
        work.doorHealthChanged(0);
        assertTrue(work.isDestroyed());
        assertEquals(1000, work.target(), EPS);
        assertEquals(200, work.progress(), EPS, "P carries over to the destroyed-door repair");
        assertEquals(800, work.remaining(), EPS);
    }

    @Test
    void objectiveLossStopsRepairAndInvalidatesWorkCapturePressureDoesNot() {
        DoorRepairWork work = DoorRepairWork.destroyed("d", 1000, 50);
        work.advance(2, 5);
        assertEquals(500, work.progress(), EPS);
        // Capture pressure is not an input: nothing to call, progress continues.
        work.advance(1, 1);
        assertEquals(550, work.progress(), EPS);
        work.objectiveOwnershipChanged(false);
        assertFalse(work.canRepair());
        assertEquals(0, work.progress(), EPS);
        assertTrue(work.advance(3, 100).isEmpty());
        assertEquals(OptionalDouble.empty(), work.etaSeconds(1));
        work.objectiveOwnershipChanged(true);
        assertTrue(work.canRepair());
        assertEquals(0, work.progress(), EPS, "a recaptured objective starts a new cycle from zero");
    }

    @Test
    void fullHealthDoorHasNothingToRepair() {
        DoorRepairWork work = new DoorRepairWork("d", 500, 10, 500);
        assertFalse(work.canRepair());
        assertEquals(0, work.target(), EPS);
        assertEquals(1, work.fraction(), EPS);
        assertEquals(Optional.empty(), work.advance(5, 5));
        assertEquals(0, work.progress(), EPS);
    }

    @Test
    void progressNeverOvershootsTheTarget() {
        DoorRepairWork work = DoorRepairWork.destroyed("d", 100, 1000);
        work.advance(4, 1);
        assertEquals(100, work.progress(), EPS);
    }
}
