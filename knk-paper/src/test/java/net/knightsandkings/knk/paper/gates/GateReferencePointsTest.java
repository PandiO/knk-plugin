package net.knightsandkings.knk.paper.gates;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-132: ReferencePoint2 must be straight above the anchor, at right angles to anchor -> ReferencePoint1. */
class GateReferencePointsTest {
    private static final Vector ANCHOR = new Vector(-972, 114, 1653);
    private static final Vector REF1 = new Vector(-972, 114, 1651);

    @Test
    void ref2AboveRef1IsRefusedWithThePointItShouldBe() {
        String problem = GateReferencePoints.referencePoint2Problem(ANCHOR, REF1, new Vector(-972, 121, 1651));

        assertTrue(problem.contains("straight above the anchor"), problem);
        assertTrue(problem.contains("set it to (-972, 121, 1653)"), problem);
    }

    @Test
    void ref2StraightAboveTheAnchorIsFine() {
        assertNull(GateReferencePoints.referencePoint2Problem(ANCHOR, REF1, new Vector(-972, 121, 1653)));
    }

    @Test
    void aDiagonalDoorWithAVerticalHeightIsFine() {
        assertNull(GateReferencePoints.referencePoint2Problem(
            new Vector(1375, 44, -585), new Vector(1378, 44, -588), new Vector(1375, 50, -585)));
    }

    @Test
    void anotherShearIsRefusedWithoutASuggestion() {
        String problem = GateReferencePoints.referencePoint2Problem(ANCHOR, REF1, new Vector(-972, 121, 1652));

        assertTrue(problem.endsWith("Fix it in the gate door form and scan again."), problem);
    }

    @Test
    void missingPointsAreNotItsConcern() {
        assertNull(GateReferencePoints.referencePoint2Problem(ANCHOR, null, new Vector(-972, 121, 1651)));
        assertNull(GateReferencePoints.referencePoint2Problem(ANCHOR, ANCHOR.clone(), new Vector(-972, 121, 1651)));
    }
}
