package net.knightsandkings.knk.core.gates.target;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.gates.GateCommandKeywords;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.DoorCandidate;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.DoorRegion;
import net.knightsandkings.knk.core.gates.target.GateTargetMath.StructureCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KNG-77/78/79: the geometry and toggle rules behind {@code /gate ... here}, look-at targets and
 * {@code toggle}.
 */
class GateTargetMathTest {

    // A portcullis opening 3 wide (x 10..13), 4 high (y 64..68), 1 deep (z 20..21).
    private static final GateBox PORTCULLIS = new GateBox(10, 64, 20, 13, 68, 21);

    @Test
    void distanceIsMeasuredToTheClosestPointNotTheCentre() {
        // 5 blocks in front of the face (z = 26): the centre is 5.5 away, the face 5.
        assertEquals(5.0, PORTCULLIS.distanceTo(11.5, 65, 26), 1e-9);
        // Off to the side and below: clamp on every axis.
        assertEquals(Math.sqrt(4 + 9 + 1), PORTCULLIS.distanceTo(15, 61, 22), 1e-9);
    }

    @Test
    void standingInsideTheRegionIsDistanceZero() {
        assertEquals(0.0, PORTCULLIS.distanceTo(11.5, 64, 20.5), 1e-9);
        assertEquals(0.0, PORTCULLIS.distanceTo(10, 64, 20), 1e-9); // on the corner
    }

    @Test
    void unionCoversBothBoxes() {
        GateBox union = GateBox.ofBlock(0, 0, 0).union(GateBox.ofBlock(4, -2, 7));
        assertEquals(new GateBox(0, -2, 0, 5, 1, 8), union);
        assertSame(PORTCULLIS, PORTCULLIS.union(null));
    }

    @Test
    void rayEntersTheBoxAtItsNearFace() {
        // From z = 30 looking straight at the gate (-z): enters at z = 21, 9 blocks away.
        assertEquals(9.0, PORTCULLIS.rayEntry(11.5, 65.6, 30, 0, 0, -1).orElseThrow(), 1e-9);
        // Looking away misses (box behind the origin).
        assertTrue(PORTCULLIS.rayEntry(11.5, 65.6, 30, 0, 0, 1).isEmpty());
        // Looking past it misses.
        assertTrue(PORTCULLIS.rayEntry(11.5, 65.6, 30, 1, 0, 0).isEmpty());
        // From inside: enters at 0.
        assertEquals(0.0, PORTCULLIS.rayEntry(11.5, 65, 20.5, 1, 0, 0).orElseThrow(), 1e-9);
    }

    @Test
    void doorsWithinRadiusAreSortedByDistanceAndFilteredByWorld() {
        List<DoorRegion> regions = List.of(
            new DoorRegion(1, 100, "world", PORTCULLIS),                       // 5 away
            new DoorRegion(2, 100, "world", new GateBox(20, 64, 20, 22, 68, 21)), // farther
            new DoorRegion(3, 200, "world", new GateBox(0, 64, 40, 2, 68, 41)),  // out of range
            new DoorRegion(4, 300, "nether", PORTCULLIS));                     // other world

        List<DoorCandidate> found = GateTargetMath.doorsWithin(regions, "world", 11.5, 65, 26, 15);

        assertEquals(List.of(1, 2), found.stream().map(DoorCandidate::doorId).toList());
        assertEquals(5.0, found.get(0).distance(), 1e-9);
        assertTrue(GateTargetMath.doorsWithin(regions, "world", 11.5, 65, 26, 4.9).isEmpty());
    }

    @Test
    void severalDoorsOfOneStructureAreOneStructureCandidate() {
        List<DoorRegion> regions = List.of(
            new DoorRegion(1, 100, "world", PORTCULLIS),
            new DoorRegion(2, 100, "world", new GateBox(13, 64, 20, 16, 68, 21)),
            new DoorRegion(3, 200, "world", new GateBox(11, 64, 34, 12, 68, 35)));

        List<StructureCandidate> found = GateTargetMath.structuresWithin(regions, "world", 11.5, 65, 26, 15);

        assertEquals(2, found.size());
        assertEquals(100, found.get(0).structureId());
        assertEquals(List.of(1, 2), found.get(0).doorIds());
        assertEquals(5.0, found.get(0).distance(), 1e-9);
        assertEquals(200, found.get(1).structureId());

        List<StructureCandidate> onlyOne = GateTargetMath.structuresWithin(regions.subList(0, 2), "world", 11.5, 65, 26, 15);
        assertEquals(1, onlyOne.size());
    }

    @Test
    void lookingAtAClosedDoorFindsIt() {
        // The ray hits the door's own block at z = 21 (9 away): the region is entered there too.
        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(List.of(new DoorRegion(1, 100, "world", PORTCULLIS)),
            "world", new double[]{11.5, 65.6, 30}, new double[]{0, 0, -1}, 12, 9.0);

        assertEquals(1, hit.orElseThrow().doorId());
    }

    @Test
    void lookingThroughAnOpenGateStillFindsIt() {
        // Open: no blocks in the opening, the ray hits the courtyard wall 20 blocks away (capped
        // at the 12-block reach, so: no block hit), yet the door region is entered at 9.
        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(List.of(new DoorRegion(1, 100, "world", PORTCULLIS)),
            "world", new double[]{11.5, 65.6, 30}, new double[]{0, 0, -1}, 12, null);

        assertEquals(1, hit.orElseThrow().doorId());
        assertEquals(9.0, hit.get().distance(), 1e-9);
    }

    @Test
    void aWallInFrontHidesTheDoor() {
        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(List.of(new DoorRegion(1, 100, "world", PORTCULLIS)),
            "world", new double[]{11.5, 65.6, 30}, new double[]{0, 0, -1}, 12, 3.0);

        assertTrue(hit.isEmpty());
    }

    @Test
    void lookAtRespectsMaxDistanceAndWorld() {
        List<DoorRegion> regions = List.of(new DoorRegion(1, 100, "world", PORTCULLIS));
        assertTrue(GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 30},
            new double[]{0, 0, -1}, 8, null).isEmpty());
        assertTrue(GateTargetMath.lookedAt(regions, "nether", new double[]{11.5, 65.6, 30},
            new double[]{0, 0, -1}, 12, null).isEmpty());
        assertTrue(GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 30},
            new double[]{0, 0, 0}, 12, null).isEmpty());
    }

    @Test
    void lookAtPicksTheNearestRegionAlongTheRay() {
        List<DoorRegion> regions = List.of(
            new DoorRegion(2, 100, "world", new GateBox(10, 64, 10, 13, 68, 11)), // behind
            new DoorRegion(1, 100, "world", PORTCULLIS));                        // in front

        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 30},
            new double[]{0, 0, -2}, 30, null);

        assertEquals(1, hit.orElseThrow().doorId());
    }

    @Test
    void standingInsideOneDoorRegionAndLookingAtAnotherPicksTheOther() {
        // Eye inside an open portcullis opening (A); 6 blocks further north stands door B, whose
        // closed block the ray hits.
        GateBox doorA = new GateBox(10, 64, 20, 13, 68, 21);
        GateBox doorB = new GateBox(10, 64, 13, 13, 68, 14);
        List<DoorRegion> regions = List.of(new DoorRegion(1, 100, "world", doorA), new DoorRegion(2, 200, "world", doorB));

        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 20.5},
            new double[]{0, 0, -1}, 12, 6.5);

        assertEquals(2, hit.orElseThrow().doorId());
    }

    @Test
    void standingInsideADoorRegionDoesNotPickItWhenLookingElsewhere() {
        GateBox doorA = new GateBox(10, 64, 20, 13, 68, 21);
        List<DoorRegion> regions = List.of(new DoorRegion(1, 100, "world", doorA));

        // Looking down and ahead: the ray hits the ground in front of the opening, outside it.
        assertTrue(GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 20.5},
            new double[]{0, -1, 1}, 12, 3.0).isEmpty());
        // Looking into the sky: nothing hit.
        assertTrue(GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 20.5},
            new double[]{0, 1, 0}, 12, null).isEmpty());
    }

    @Test
    void standingOnALoweredDrawbridgeAndLookingAtItPicksIt() {
        // The drawbridge's region covers the deck the player stands on; the ray hits the deck.
        GateBox drawbridge = new GateBox(10, 63, 14, 13, 68, 21);
        List<DoorRegion> regions = List.of(new DoorRegion(1, 100, "world", drawbridge));

        Optional<DoorCandidate> hit = GateTargetMath.lookedAt(regions, "world", new double[]{11.5, 65.6, 17.5},
            new double[]{0, -1, -1}, 12, Math.sqrt(2) * 1.6);

        assertEquals(1, hit.orElseThrow().doorId());
    }

    @Test
    void doorToggleFlipsTheTargetState() {
        assertTrue(GateToggle.doorOpens(AnimationState.CLOSED));
        assertTrue(GateToggle.doorOpens(AnimationState.CLOSING));
        assertTrue(GateToggle.doorOpens(null));
        assertFalse(GateToggle.doorOpens(AnimationState.OPEN));
        assertFalse(GateToggle.doorOpens(AnimationState.OPENING));
    }

    @Test
    void structureToggleClosesAllWhenAnyDoorIsOpen() {
        assertFalse(GateToggle.structureOpens(List.of(AnimationState.CLOSED, AnimationState.OPEN)));
        assertFalse(GateToggle.structureOpens(List.of(AnimationState.CLOSED, AnimationState.OPENING)));
        assertTrue(GateToggle.structureOpens(List.of(AnimationState.CLOSED, AnimationState.CLOSING)));
        assertTrue(GateToggle.structureOpens(List.of()));
    }

    @Test
    void hereIsAReservedKeyword() {
        assertTrue(GateCommandKeywords.isReserved("here"));
        assertTrue(GateCommandKeywords.isReserved(" HERE "));
        assertFalse(GateCommandKeywords.isReserved("Herewood"));
        assertFalse(GateCommandKeywords.isReserved(null));
        assertTrue(GateCommandKeywords.isHere("Here"));
    }
}
