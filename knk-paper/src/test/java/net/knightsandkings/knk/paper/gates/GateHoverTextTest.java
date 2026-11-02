package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.paper.gates.GateDisplayManager.HoverState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Siege pre-game hover (PandiO/knk-workspace#217): non-members see the door's hover as it was before the match,
 * rendered by the same formatter as the live one.
 */
class GateHoverTextTest {

    private static String plain(Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A door in a siege right now: open, damaged, invincible, siege id set; health and status SIEGE_ONLY. */
    private static CachedGateDoor siegeDoor() {
        CachedGateDoor door = mock(CachedGateDoor.class);
        when(door.getStructureName()).thenReturn("North Gate");
        when(door.getName()).thenReturn("Main");
        when(door.getEffectiveGateNameDisplayMode()).thenReturn("ALWAYS");
        when(door.getDoorNameDisplayMode()).thenReturn("NEVER");
        when(door.getEffectiveStatusDisplayMode()).thenReturn("ALWAYS");
        when(door.isEffectivelyShowHealthDisplay()).thenReturn(true);
        when(door.getEffectiveHealthDisplayMode()).thenReturn("SIEGE_ONLY");
        when(door.getCurrentSiegeId()).thenReturn(42);
        when(door.getHealthCurrent()).thenReturn(300.0);
        when(door.getHealthMax()).thenReturn(1000.0);
        when(door.getCurrentState()).thenReturn(AnimationState.OPEN);
        when(door.isEffectivelyInvincible()).thenReturn(true);
        return door;
    }

    @Test
    void liveHoverShowsTheSiegeState() {
        assertEquals("North Gate\n300/1000 hp\nOPEN · INVINCIBLE",
                plain(GateDisplayManager.buildDisplayText(HoverState.live(siegeDoor()))));
    }

    @Test
    void preGameHoverShowsTheStateBeforeTheMatchWithoutSiegeOnlyLines() {
        HoverState preGame = HoverState.preGame(siegeDoor(), false, false, 1000.0, false);
        assertEquals("North Gate\nCLOSED", plain(GateDisplayManager.buildDisplayText(preGame)));
    }

    @Test
    void preGameHoverOfADoorDestroyedBeforeTheMatch() {
        HoverState preGame = HoverState.preGame(siegeDoor(), false, true, 0.0, false);
        assertEquals("North Gate\nDESTROYED", plain(GateDisplayManager.buildDisplayText(preGame)));
    }

    @Test
    void nothingVisibleGivesNull() {
        CachedGateDoor door = siegeDoor();
        when(door.getEffectiveGateNameDisplayMode()).thenReturn("SIEGE_ONLY");
        when(door.getEffectiveStatusDisplayMode()).thenReturn("SIEGE_ONLY");
        assertNull(GateDisplayManager.buildDisplayText(HoverState.preGame(door, true, false, 1000.0, false)));
    }
}
