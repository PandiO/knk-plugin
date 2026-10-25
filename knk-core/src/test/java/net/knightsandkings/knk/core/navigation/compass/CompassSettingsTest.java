package net.knightsandkings.knk.core.navigation.compass;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompassSettingsTest {

    @Test
    void defaultsAreTheNamedCompassForSiegesOnly() {
        CompassSettings defaults = CompassSettings.defaults();
        assertTrue(defaults.siegeEnabled());
        assertFalse(defaults.generalEnabled());
        assertTrue(defaults.item().matches("COMPASS", "Navigation Compass"));
    }

    @Test
    void targetsAreOnePerLineWithoutBlanksOrComments() {
        assertEquals(List.of("town:Kardenna", "Market Square spawn"),
            CompassSettings.parseTargets("town:Kardenna\n\n  # not this\n Market Square spawn \r\n"));
        assertEquals(List.of(), CompassSettings.parseTargets(null));
    }

    @Test
    void duplicateTargetsCollapse() {
        assertEquals(List.of("a", "b"), new CompassSettings(null, true, true, List.of("a", " ", "b", "a")).generalTargets());
    }
}
