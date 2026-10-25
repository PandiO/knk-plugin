package net.knightsandkings.knk.core.navigation.compass;

import net.knightsandkings.knk.core.navigation.compass.CompassUse.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompassUseTest {

    @Test
    void aPlainClickAlwaysCycles() {
        assertEquals(Action.CYCLE, CompassUse.onClick(false, false, false));
        assertEquals(Action.CYCLE, CompassUse.onClick(false, true, true));
    }

    @Test
    void shiftClickStopsARunningNavigation() {
        assertEquals(Action.STOP, CompassUse.onClick(true, true, true));
        assertEquals(Action.STOP, CompassUse.onClick(true, true, false));
    }

    @Test
    void shiftClickWithoutNavigationBringsBackTheLastTarget() {
        assertEquals(Action.RESUME, CompassUse.onClick(true, false, true));
    }

    @Test
    void shiftClickWithoutAnyTargetStartsTheFirst() {
        assertEquals(Action.CYCLE, CompassUse.onClick(true, false, false));
    }
}
