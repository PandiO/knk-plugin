package net.knightsandkings.knk.core.roads.build;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RdpTest {
    private static final double EPS = BuildParameters.DEFAULT_RDP_EPSILON;

    private static int[] p(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    @Test
    void straightLineKeepsOnlyItsEnds() {
        List<int[]> line = new ArrayList<>();
        for (int x = 0; x <= 20; x++) line.add(p(x, 64, 5));
        List<int[]> out = Rdp.simplify(line, EPS);
        assertEquals(2, out.size());
        assertSame(line.get(0), out.get(0));
        assertSame(line.get(20), out.get(1));
    }

    @Test
    void cornerIsKept() {
        List<int[]> l = List.of(p(0, 64, 0), p(1, 64, 0), p(2, 64, 0), p(3, 64, 0), p(3, 64, 1), p(3, 64, 2), p(3, 64, 3));
        List<int[]> out = Rdp.simplify(l, EPS);
        assertEquals(3, out.size());
        assertArrayEquals(p(3, 64, 0), out.get(1));
    }

    @Test
    void smallWobbleIsSmoothedLargerOneIsKept() {
        List<int[]> wobble = List.of(p(0, 64, 0), p(1, 64, 0), p(2, 64, 1), p(3, 64, 0), p(4, 64, 0));
        assertEquals(2, Rdp.simplify(wobble, 1.0).size(), "1 block off the line is within ε = 1");
        assertEquals(3, Rdp.simplify(wobble, EPS).size(), "1 block off the line exceeds ε = 0.75");
    }

    @Test
    void heightChangesCountInThreeD() {
        List<int[]> ramp = List.of(p(0, 64, 0), p(1, 64, 0), p(2, 64, 0), p(3, 65, 0), p(4, 66, 0), p(5, 67, 0));
        List<int[]> out = Rdp.simplify(ramp, EPS);
        assertEquals(3, out.size());
        assertArrayEquals(p(2, 64, 0), out.get(1), "the foot of the ramp");
    }

    @Test
    void oneAndTwoPointsAreReturnedAsIs() {
        assertEquals(1, Rdp.simplify(List.of(p(0, 64, 0)), EPS).size());
        assertEquals(2, Rdp.simplify(List.of(p(0, 64, 0), p(1, 64, 0)), EPS).size());
        assertEquals(0, Rdp.simplify(List.of(), EPS).size());
        assertThrows(IllegalArgumentException.class, () -> Rdp.simplify(List.of(), -1));
    }

    @Test
    void epsilonZeroKeepsEveryPointOffTheLine() {
        List<int[]> l = List.of(p(0, 64, 0), p(1, 64, 0), p(2, 64, 0), p(2, 64, 1));
        assertEquals(3, Rdp.simplify(l, 0).size(), "collinear (1,64,0) still goes");
    }

    @Test
    void distanceToSegmentClampsToTheEnds() {
        assertEquals(1.0, Rdp.distanceToSegment(p(1, 65, 0), p(0, 64, 0), p(2, 64, 0)), 1e-9);
        assertEquals(2.0, Rdp.distanceToSegment(p(4, 64, 0), p(0, 64, 0), p(2, 64, 0)), 1e-9, "beyond the end");
        assertEquals(Math.sqrt(3), Rdp.distanceToSegment(p(1, 65, 1), p(0, 64, 0), p(0, 64, 0)), 1e-9, "degenerate segment");
    }

    @Test
    void lengthSumsSegments() {
        assertEquals(0, Rdp.length(List.of(p(0, 64, 0))), 1e-9);
        assertEquals(2 + Math.sqrt(2), Rdp.length(List.of(p(0, 64, 0), p(2, 64, 0), p(3, 65, 0))), 1e-9);
    }
}
