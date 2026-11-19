package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import net.knightsandkings.knk.core.util.BlockKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#771 item 2: an open trapdoor hatch over a ladder (the Garrison Watchtower Roof) is passed up and
 * down; a closed one stays a floor that is never passed.
 */
class WalkTrapdoorHatchTest {

    private static final String S = GridFixture.STONE;

    /**
     * A room with its floor at 64 (x 0..4, z 0..2) under a stone roof at 68 (feet on the roof at 69); the only way
     * between them is the hatch at (2, 68, 1) with a ladder below it at (2, 65..67, 1).
     */
    private static WalkFixture watchtower(boolean hatchOpen) {
        return new WalkFixture().floor(0, 0, 4, 2, 64, S).floor(0, 0, 4, 2, 68, S)
            .column(2, 1, 65, 67, WalkFixture.LADDER)
            .trapdoor(2, 68, 1, hatchOpen);
    }

    private static List<String> cells(WalkPath path) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            long k = path.cell(i);
            out.add((path.isLadder(i) ? "L" : "") + BlockKey.x(k) + "," + BlockKey.y(k) + "," + BlockKey.z(k));
        }
        return out;
    }

    private static WalkPath found(WalkResult result) {
        assertEquals(WalkResult.Status.FOUND, result.status(), result.toString());
        return result.path().orElseThrow();
    }

    @Test
    void anOpenHatchOverALadderIsClimbedUpOntoTheRoof() {
        WalkPath path = found(watchtower(true).walk(0, 64, 0, 4, 68, 2));

        List<String> cells = cells(path);
        assertTrue(cells.containsAll(List.of("L2,65,1", "L2,66,1", "L2,67,1", "L2,68,1")), cells.toString());
        int hatch = cells.indexOf("L2,68,1");
        assertEquals(68, BlockKey.y(path.cell(hatch + 1)), "off the hatch onto the roof: " + cells);
        assertFalse(path.isLadder(hatch + 1), cells.toString());
    }

    @Test
    void anOpenHatchOverALadderIsClimbedDownFromTheRoof() {
        WalkPath path = found(watchtower(true).walk(4, 68, 2, 0, 64, 0));

        List<String> cells = cells(path);
        assertTrue(cells.containsAll(List.of("L2,68,1", "L2,67,1", "L2,65,1")), cells.toString());
        assertTrue(cells.indexOf("L2,68,1") < cells.indexOf("L2,65,1"), "down, not up: " + cells);
    }

    @Test
    void aClosedHatchIsNotPassedEitherWay() {
        assertEquals(WalkResult.Status.NO_PATH, watchtower(false).walk(0, 64, 0, 4, 68, 2).status());
        assertEquals(WalkResult.Status.NO_PATH, watchtower(false).walk(4, 68, 2, 0, 64, 0).status());
    }

    @Test
    void aClosedHatchIsStillAFloorOnTheRoof() {
        // as before #771: a closed trapdoor is a solid block, walked over
        WalkPath path = found(watchtower(false).walk(1, 68, 1, 3, 68, 1));

        assertTrue(cells(path).contains("2,68,1"), cells(path).toString());
    }

    @Test
    void anOpenTrapdoorWithNoLadderBelowIsNotAWayUp() {
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 2, 64, S).floor(0, 0, 4, 2, 68, S).trapdoor(2, 68, 1, true);

        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 64, 0, 4, 68, 2).status());
    }

    @Test
    void anOpenTrapdoorAtTheTopOfALadderThatReachesTheRoofIsPassed() {
        // the other build: the ladder runs up into the roof's hole (65..68) and the open hatch lies at 69, feet level
        WalkFixture f = new WalkFixture().floor(0, 0, 4, 2, 64, S).floor(0, 0, 4, 2, 68, S)
            .column(2, 1, 65, 68, WalkFixture.LADDER)
            .trapdoor(2, 69, 1, true);

        WalkPath up = found(f.walk(0, 64, 0, 4, 68, 2));
        assertTrue(cells(up).contains("L2,68,1"), cells(up).toString());
        WalkPath down = found(f.walk(4, 68, 2, 0, 64, 0));
        assertTrue(cells(down).contains("L2,65,1"), cells(down).toString());

        f.trapdoor(2, 69, 1, false);
        assertEquals(WalkResult.Status.NO_PATH, f.walk(0, 64, 0, 4, 68, 2).status(), "a closed hatch shuts it");
    }

    @Test
    void theHatchRuleNeedsAnOpenTrapdoorOverAClimbable() {
        Set<String> ladder = Set.of("LADDER");
        assertTrue(WalkCells.isClimbableHatch("OAK_TRAPDOOR", true, "LADDER", ladder));
        assertTrue(WalkCells.isClimbableHatch("SPRUCE_TRAPDOOR", true, "LADDER", ladder));
        assertFalse(WalkCells.isClimbableHatch("OAK_TRAPDOOR", false, "LADDER", ladder), "closed");
        assertFalse(WalkCells.isClimbableHatch("OAK_TRAPDOOR", true, "STONE", ladder), "no climbable below");
        assertFalse(WalkCells.isClimbableHatch("OAK_TRAPDOOR", true, null, ladder), "below the world");
        assertFalse(WalkCells.isClimbableHatch("OAK_DOOR", true, "LADDER", ladder), "not a trapdoor");
        assertTrue(WalkCells.isClimbableHatch("OAK_TRAPDOOR", true, "VINE", Set.of("LADDER", "VINE")),
            "the profile's climbables decide");
    }

    @Test
    void withoutOpenStatesTheCellsAreAsBefore() {
        WalkFixture f = watchtower(true);
        WalkCells materialsOnly = WalkCells.ofMaterials(f.grid::material, Set.of("LADDER"));

        assertFalse(materialsOnly.isClimbable(2, 68, 1), "material names alone never open a trapdoor");
        assertTrue(f.terrain().cells().isClimbable(2, 68, 1));
        assertTrue(f.terrain().cells().isClimbable(2, 67, 1));
    }
}
