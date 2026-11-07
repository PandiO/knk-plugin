package net.knightsandkings.knk.core.survival.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillBlowTest {

    @Test
    void headshotIsAHitWithinTheHeadBandBelowTheEyesOrAbove() {
        // A zombie: feet at 64, eyes at 1.74 → head band from 65.44 upwards.
        assertTrue(KillBlow.isHeadshot(65.5, 64, 1.74));
        assertTrue(KillBlow.isHeadshot(65.44, 64, 1.74));
        assertTrue(KillBlow.isHeadshot(66.2, 64, 1.74));
        assertFalse(KillBlow.isHeadshot(65.3, 64, 1.74));
        assertFalse(KillBlow.isHeadshot(64.2, 64, 1.74));
    }

    @Test
    void headshotNeedsAUsableEyeHeight() {
        assertFalse(KillBlow.isHeadshot(70, 64, 0));
        assertFalse(KillBlow.isHeadshot(70, 64, -1));
        assertFalse(KillBlow.isHeadshot(Double.NaN, 64, 1.7));
    }

    @Test
    void attackerIsRequired() {
        assertThrows(NullPointerException.class, () -> new KillBlow(null, false, false));
        KillBlow blow = new KillBlow(UUID.randomUUID(), true, false);
        assertTrue(blow.critical());
        assertFalse(blow.headshot());
    }
}
