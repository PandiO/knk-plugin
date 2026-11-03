package net.knightsandkings.knk.core.npc.cooldown;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownTrackerTest {
    private final UUID actor = UUID.randomUUID();

    @Test
    void firstFireIsAlwaysReadyThenTheCooldownGates() {
        CooldownTracker t = new CooldownTracker();
        assertTrue(t.isReady(actor, CooldownTracker.MELEE, 100));
        assertEquals(0, t.remaining(actor, CooldownTracker.MELEE, 100));
        assertTrue(t.tryFire(actor, CooldownTracker.MELEE, 100, 20));
        assertEquals(120, t.readyAt(actor, CooldownTracker.MELEE).getAsLong());
        assertFalse(t.tryFire(actor, CooldownTracker.MELEE, 119, 20));
        assertEquals(1, t.remaining(actor, CooldownTracker.MELEE, 119));
        assertEquals(120, t.readyAt(actor, CooldownTracker.MELEE).getAsLong(), "a refused fire does not re-arm");
        assertTrue(t.tryFire(actor, CooldownTracker.MELEE, 120, 10));
        assertEquals(130, t.readyAt(actor, CooldownTracker.MELEE).getAsLong());
    }

    @Test
    void channelsAndActorsAreIndependent() {
        CooldownTracker t = new CooldownTracker();
        UUID other = UUID.randomUUID();
        assertTrue(t.tryFire(actor, CooldownTracker.MELEE, 0, 20));
        assertTrue(t.tryFire(actor, CooldownTracker.RANGED, 0, 40));
        assertTrue(t.tryFire(other, CooldownTracker.MELEE, 0, 20));
        assertFalse(t.isReady(actor, CooldownTracker.MELEE, 19));
        assertTrue(t.isReady(actor, CooldownTracker.MELEE, 20));
        assertFalse(t.isReady(actor, CooldownTracker.RANGED, 20));
        assertTrue(t.isReady(actor, "custom", 1));
        assertEquals(2, t.trackedActors());
    }

    @Test
    void armClampsToAtLeastOneTickAndReturnsTheTicksApplied() {
        CooldownTracker t = new CooldownTracker();
        assertEquals(1, t.arm(actor, CooldownTracker.MELEE, 50, 0));
        assertEquals(51, t.readyAt(actor, CooldownTracker.MELEE).getAsLong());
        assertEquals(1, t.arm(actor, CooldownTracker.MELEE, 50, -7));
        assertEquals(25, t.arm(actor, CooldownTracker.MELEE, 50, 25));
    }

    @Test
    void resetAndForgetMakeTheChannelReadyAgain() {
        CooldownTracker t = new CooldownTracker();
        t.arm(actor, CooldownTracker.MELEE, 0, 100);
        t.reset(actor, CooldownTracker.MELEE);
        assertTrue(t.isReady(actor, CooldownTracker.MELEE, 1));
        assertTrue(t.readyAt(actor, CooldownTracker.MELEE).isEmpty());
        t.arm(actor, CooldownTracker.RANGED, 0, 100);
        t.forget(actor);
        assertTrue(t.isReady(actor, CooldownTracker.RANGED, 1));
        assertEquals(0, t.trackedActors());
        t.arm(actor, CooldownTracker.RANGED, 0, 100);
        t.clear();
        assertEquals(0, t.trackedActors());
    }

    @Test
    void jitterIsOffByDefaultAndNeverShortensACooldown() {
        assertFalse(CooldownJitter.OFF.enabled());
        assertEquals(0.1, CooldownJitter.DEFAULT_MAX_FRACTION);
        assertEquals(CooldownJitter.OFF, new CooldownTracker().jitter());
        CooldownJitter jitter = CooldownJitter.on(0.5);
        RandomGenerator random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            int applied = jitter.apply(20, random);
            assertTrue(applied >= 20 && applied <= 30, "applied " + applied);
        }
        assertEquals(20, CooldownJitter.OFF.apply(20, random));
        assertEquals(20, CooldownJitter.on(0.0).apply(20, random));
        assertEquals(0, jitter.apply(0, random));
    }

    @Test
    void jitterIsDeterministicForASeededRandom() {
        CooldownTracker a = new CooldownTracker(CooldownJitter.on(0.25), new Random(7));
        CooldownTracker b = new CooldownTracker(CooldownJitter.on(0.25), new Random(7));
        for (int i = 0; i < 20; i++) {
            assertEquals(a.arm(actor, CooldownTracker.MELEE, i, 20), b.arm(actor, CooldownTracker.MELEE, i, 20));
        }
        int applied = a.arm(actor, CooldownTracker.MELEE, 0, 40);
        assertTrue(applied >= 40 && applied <= 50);
        assertEquals(applied, a.readyAt(actor, CooldownTracker.MELEE).getAsLong());
    }

    @Test
    void jitterRejectsNegativeFractions() {
        assertThrows(IllegalArgumentException.class, () -> CooldownJitter.on(-0.1));
        assertThrows(IllegalArgumentException.class, () -> CooldownJitter.on(Double.NaN));
    }
}
