package net.knightsandkings.knk.core.siege.weapons;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A02: facing pre-filled from the look direction, overridable, and the size class follows it. */
class SpotDraftTest {

    private static final SpotBounds SEVEN_BY_NINE = new SpotBounds(0, 64, 0, 6, 68, 8);
    private static final SpotSiteCheck.Result CLEAR = new SpotSiteCheck.Result(63, 0, true, 0);
    private static final float LOOKING_NORTH = 180f;

    @Test
    void capture_PreFillsTheFacingFromTheLookDirection() {
        SpotDraft draft = SpotDraft.capture(SEVEN_BY_NINE, null, LOOKING_NORTH, null, CLEAR);
        assertEquals(SpotFacing.NORTH, draft.facing());
        assertFalse(draft.facingOverridden());
        assertEquals(Optional.of(SpotSizeClass.SMALL), draft.sizeClass());
        assertTrue(draft.refusal().isEmpty());
        assertTrue(draft.summary().contains("Facing: North (your look direction)"), draft.summary().toString());
        assertTrue(draft.summary().contains("Size class: Small (needs 7 wide × 9 long × 5 high)"), draft.summary().toString());
    }

    @Test
    void overridingTheFacing_WorksTheSizeClassOutAgain() {
        SpotDraft east = SpotDraft.capture(SEVEN_BY_NINE, null, LOOKING_NORTH, null, CLEAR).withFacing(SpotFacing.EAST);
        assertEquals(SpotFacing.EAST, east.facing());
        assertTrue(east.facingOverridden());
        assertTrue(east.sizeClass().isEmpty());
        assertTrue(east.refusal().orElseThrow().contains("It fits facing north or south"));

        SpotDraft south = east.withFacing(SpotFacing.SOUTH);
        assertEquals(Optional.of(SpotSizeClass.SMALL), south.sizeClass());
    }

    @Test
    void aFacingSetBeforeTheCapture_IsKeptInsteadOfTheLookDirection() {
        SpotDraft draft = SpotDraft.capture(SEVEN_BY_NINE, "spot_north_wall", LOOKING_NORTH, SpotFacing.SOUTH, CLEAR);
        assertEquals(SpotFacing.SOUTH, draft.facing());
        assertTrue(draft.facingOverridden());
        assertTrue(draft.summary().getFirst().endsWith("(region spot_north_wall)"));
    }
}
