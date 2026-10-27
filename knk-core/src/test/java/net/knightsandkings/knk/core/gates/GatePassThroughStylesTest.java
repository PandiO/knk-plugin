package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static net.knightsandkings.knk.core.domain.users.GatePassThroughMethod.DEFAULT;
import static net.knightsandkings.knk.core.domain.users.GatePassThroughMethod.INSTANT_OPEN;
import static net.knightsandkings.knk.core.domain.users.GatePassThroughMethod.SNAP;
import static net.knightsandkings.knk.core.domain.users.GatePassThroughMethod.TELEPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#167: per-door pass-through styles, the player's preference and the door default. */
class GatePassThroughStylesTest {

    private static CachedGateDoor door() {
        return new CachedGateDoor(1, 1, "Door", "SLIDING", "VERTICAL", "PLANE_GRID", 60, 1,
            new Vector(0, 64, 0), 3, 3, 1, 500.0, 500.0, true, false, true, 90, "north");
    }

    @Test
    void aDoorWithoutStyleSettingsBehavesAsBefore167() {
        CachedGateDoor door = door();
        assertEquals(EnumSet.of(DEFAULT, INSTANT_OPEN, TELEPORT), door.getEffectiveAllowedPassThroughMethods());
        assertEquals(DEFAULT, door.getEffectiveDefaultPassThroughMethod());
        assertEquals(TELEPORT, GatePassThroughStyles.resolve(door, TELEPORT, false));
        assertEquals(INSTANT_OPEN, GatePassThroughStyles.resolve(door, INSTANT_OPEN, true));
        assertEquals(DEFAULT, GatePassThroughStyles.resolve(door, INSTANT_OPEN, false));
        // Snap is new and not offered by a migrated door: the door default (animated) is used.
        assertEquals(DEFAULT, GatePassThroughStyles.resolve(door, SNAP, true));
    }

    @Test
    void thePreferenceIsUsedWhenTheDoorOffersIt() {
        assertEquals(SNAP, GatePassThroughStyles.resolve(SNAP, EnumSet.of(DEFAULT, SNAP), DEFAULT, false));
    }

    @Test
    void theDoorDefaultIsUsedWhenThePreferenceIsNotOffered() {
        assertEquals(SNAP, GatePassThroughStyles.resolve(TELEPORT, EnumSet.of(SNAP), SNAP, true));
    }

    @Test
    void walkPathAsDoorDefaultNeedsTheInstantNodeElseTheFirstOtherOfferedStyle() {
        Set<GatePassThroughMethod> allowed = EnumSet.of(INSTANT_OPEN, TELEPORT);
        assertEquals(INSTANT_OPEN, GatePassThroughStyles.resolve(DEFAULT, allowed, INSTANT_OPEN, true));
        assertEquals(TELEPORT, GatePassThroughStyles.resolve(DEFAULT, allowed, INSTANT_OPEN, false));
        // Nothing else offered: animated, as before #167 when the instant node was missing.
        assertEquals(DEFAULT, GatePassThroughStyles.resolve(INSTANT_OPEN, EnumSet.of(INSTANT_OPEN), INSTANT_OPEN, false));
    }

    @Test
    void theDefaultStyleIsAlwaysOffered() {
        CachedGateDoor door = door();
        door.setAllowedPassThroughMethods(EnumSet.noneOf(GatePassThroughMethod.class));
        door.setDefaultPassThroughMethod(SNAP);
        assertEquals(EnumSet.of(SNAP), door.getEffectiveAllowedPassThroughMethods());
        assertEquals(SNAP, GatePassThroughStyles.resolve(door, TELEPORT, true));
    }

    @Test
    void structureOverridesWinPerStyleAndForTheDefault() {
        CachedGateDoor door = door();
        CachedGateStructure structure = new CachedGateStructure(1, "Gate");
        door.setStructure(structure);

        structure.setAllowPassThroughStyleOverride(TELEPORT, false);
        structure.setAllowPassThroughStyleOverride(SNAP, true);
        structure.setDefaultPassThroughMethodOverride(SNAP);
        assertEquals(EnumSet.of(DEFAULT, SNAP, INSTANT_OPEN), door.getEffectiveAllowedPassThroughMethods());
        assertEquals(SNAP, GatePassThroughStyles.resolve(door, TELEPORT, true));

        structure.setAllowPassThroughStyleOverride(TELEPORT, null);
        structure.setDefaultPassThroughMethodOverride(null);
        assertNull(structure.getAllowPassThroughStyleOverride(TELEPORT));
        assertEquals(TELEPORT, GatePassThroughStyles.resolve(door, TELEPORT, false));
        assertEquals(DEFAULT, door.getEffectiveDefaultPassThroughMethod());
    }

    @Test
    void snapRoundTripsOnTheWire() {
        assertEquals("Snap", SNAP.toWireValue());
        assertEquals(SNAP, GatePassThroughMethod.fromWireValue("Snap"));
    }
}
