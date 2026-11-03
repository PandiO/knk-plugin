package net.knightsandkings.knk.core.survival.wave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S27: configurable round delay, default 10 s. */
class IntermissionTest {

    @Test
    void defaultIsTenSeconds() {
        Intermission i = Intermission.startingAt(1_000, Intermission.DEFAULT_DURATION_MILLIS);
        assertEquals(11_000, i.endMillis());
        assertEquals(10, i.remainingSecondsCeil(1_000));
        assertEquals(1, i.remainingSecondsCeil(10_001));
        assertFalse(i.isOver(10_999));
        assertTrue(i.isOver(11_000));
        assertEquals(0, i.remainingMillis(20_000));
    }
}
