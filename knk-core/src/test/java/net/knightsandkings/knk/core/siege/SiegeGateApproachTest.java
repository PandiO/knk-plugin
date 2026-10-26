package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.SiegeGateApproach.Approach;
import net.knightsandkings.knk.core.siege.SiegeGateApproach.Door;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#176, with the alpha seed's Northern Gate (gate structure 8, world_KNK-DEV): the Outer Gate
 * from (1375, 44, -585) to (1378, 51, -588), the Inner Gate from (1380, 45, -581) to (1382, 50, -583), both diagonal;
 * the objective's capture point on the gatehouse at (1386.3, 52, -588.2); the attackers' North Spawn at
 * (1324.5, -622.2), South Spawn (1484.2, -400.5), East Spawn (1500.7, -636.1).
 */
class SiegeGateApproachTest {

    private static final double[] CAPTURE = {1386.3, 52, -588.2};
    private static final List<double[]> ATTACKER_SPAWNS = List.of(
        new double[] {1324.5, -622.2}, new double[] {1484.2, -400.5}, new double[] {1500.7, -636.1});

    /** A diagonal door: x from x0 going +1 while z goes -1, {@code width} blocks, y from y0 to y1. */
    private static List<int[]> diagonal(int x0, int z0, int width, int y0, int y1) {
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            for (int y = y0; y <= y1; y++) {
                cells.add(new int[] {x0 + i, y, z0 - i});
            }
        }
        return cells;
    }

    private static final Door OUTER = new Door("Outer Gate", diagonal(1375, -585, 4, 44, 51), List.of());
    private static final Door INNER = new Door("Inner Gate", diagonal(1380, -581, 3, 45, 50), List.of());

    @Test
    void attackersAreSentToTheOuterGateNotTheGatehouseTop() {
        Approach a = SiegeGateApproach.approach(List.of(OUTER, INNER), CAPTURE, ATTACKER_SPAWNS).orElseThrow();
        assertEquals("Outer Gate", a.door());
        assertEquals(44, a.y(), 1e-9, "at the door's bottom, not 8 blocks up");
        // in front of the outer door on the island's side (north-west: lower x, lower z than the door's centre 1377, -586)
        assertTrue(a.x() < 1377 && a.z() < -586, "x " + a.x() + " z " + a.z());
        assertEquals(1.5, Math.hypot(a.x() - 1377.0, a.z() - -586.0), 0.01);
    }

    @Test
    void withTheOuterGateDownTheCapturePointIsTheDestination() {
        // the capture point lies between the doors: the inner door does not stand in the attackers' way
        assertEquals(Optional.empty(), SiegeGateApproach.approach(List.of(INNER), CAPTURE, ATTACKER_SPAWNS));
    }

    @Test
    void anOpenGateIsNoObstacle() {
        assertEquals(Optional.empty(), SiegeGateApproach.approach(List.of(), CAPTURE, ATTACKER_SPAWNS));
    }

    @Test
    void aCapturePointInFrontOfTheGateIsTheDestination() {
        double[] outside = {1360, 45, -600};
        assertEquals(Optional.empty(), SiegeGateApproach.approach(List.of(OUTER, INNER), outside, ATTACKER_SPAWNS));
    }

    @Test
    void aLoweredDrawbridgeMovesTheApproachToTheBank() {
        // open position: lying flat 8 blocks out towards the island, at the hinge row's height
        List<int[]> lowered = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            for (int w = 0; w < 4; w++) {
                lowered.add(new int[] {1375 + w - i, 44, -585 - w - i});
            }
        }
        Door drawbridge = new Door("Outer Gate", OUTER.closed(), lowered);
        Approach a = SiegeGateApproach.approach(List.of(drawbridge, INNER), CAPTURE, ATTACKER_SPAWNS).orElseThrow();
        double out = Math.hypot(a.x() - 1377.0, a.z() - -586.0);
        assertTrue(out > 11, "beyond the bridge's far end, was " + out);
    }

    @Test
    void anAxisAlignedPortcullisAndANarrowDoor() {
        List<int[]> portcullis = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 64; y < 68; y++) {
                portcullis.add(new int[] {x, y, 10});
            }
        }
        double[] inside = {1.5, 64, 20};
        Approach a = SiegeGateApproach.approach(List.of(new Door("P", portcullis, null)), inside,
            List.<double[]>of(new double[] {1.5, -30})).orElseThrow();
        assertEquals(1.5, a.x(), 1e-9);
        assertEquals(10.5 - 1.5, a.z(), 1e-9);
        assertEquals(64, a.y(), 1e-9);

        List<int[]> narrow = List.of(new int[] {0, 64, 10}, new int[] {0, 65, 10});
        Approach n = SiegeGateApproach.approach(List.of(new Door("N", narrow, null)), inside,
            List.<double[]>of(new double[] {0.5, -30})).orElseThrow();
        assertEquals(0.5, n.x(), 1e-9);
        assertEquals(9.0, n.z(), 1e-9);
    }

    @Test
    void noSideNoApproach() {
        assertEquals(Optional.empty(), SiegeGateApproach.approach(List.of(OUTER), CAPTURE, List.of()));
    }
}
