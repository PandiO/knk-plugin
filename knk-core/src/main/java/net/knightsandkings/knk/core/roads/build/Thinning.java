package net.knightsandkings.knk.core.roads.build;

import java.util.Arrays;

/**
 * Zhang-Suen thinning on a {@link RoadMask} (DESIGN §5.5 step 2): reduces the mask to a 1-span-wide,
 * 8-connected centreline that follows the middle of wide roads and lies exactly on 1-wide paths.
 * Because the mask's 8-neighbourhood is the span grid's, the same code thins a tunnel under a road,
 * a bridge over it or a spiral ramp without any layer handling.
 *
 * <p>P2 … P9 are the mask neighbours N, NE, E, SE, S, SW, W, NW ({@link SpanGrid#N} … {@link SpanGrid#NW}).
 * Both sub-passes run alternately until neither deletes anything. One guard is added to the
 * textbook rule: a span with exactly two neighbours that touch each other (a corner end) is kept,
 * because plain Zhang-Suen eats a 4-connected staircase - a 1-wide diagonal path - from its ends. A final pass removes the
 * "staircase" corners Zhang-Suen is known to leave (Holt's templates), so a diagonal line is a
 * clean diagonal and no span with three skeleton neighbours is left where the road only bends.
 */
public final class Thinning {
    private Thinning() {
    }

    /** Skeleton flags per span index: true = on the centreline. */
    public static boolean[] thin(RoadMask mask) {
        int n = mask.size();
        boolean[] on = new boolean[n];
        Arrays.fill(on, true);
        int[] toDelete = new int[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (on[i] && deletable(mask, on, i, pass)) {
                        toDelete[count++] = i;
                    }
                }
                for (int k = 0; k < count; k++) {
                    on[toDelete[k]] = false;
                }
                changed |= count > 0;
            }
        }
        removeStaircaseCorners(mask, on);
        return on;
    }

    private static boolean deletable(RoadMask mask, boolean[] on, int i, int pass) {
        // p[d] = 1 when the neighbour in direction d is a skeleton span.
        int b = 0;
        int bits = 0;
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int nb = mask.neighbour(i, d);
            if (nb != RoadMask.NONE && on[nb]) {
                bits |= 1 << d;
                b++;
            }
        }
        if (b < 2 || b > 6) {
            return false;
        }
        if (b == 2 && adjacentPair(bits)) {
            // A corner end: its two neighbours touch each other (e.g. S and SW). Plain Zhang-Suen
            // deletes it and then the next cell, eating a 4-connected staircase - a 1-wide diagonal
            // path - from its ends until nothing is left. Keeping corner ends preserves such paths;
            // Holt's pass straightens the remaining staircase afterwards.
            return false;
        }
        // A(P1): 0→1 transitions around P2..P9,P2.
        int a = 0;
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int here = (bits >> d) & 1;
            int next = (bits >> ((d + 1) & 7)) & 1;
            if (here == 0 && next == 1) {
                a++;
            }
        }
        if (a != 1) {
            return false;
        }
        boolean p2 = (bits & (1 << SpanGrid.N)) != 0;
        boolean p4 = (bits & (1 << SpanGrid.E)) != 0;
        boolean p6 = (bits & (1 << SpanGrid.S)) != 0;
        boolean p8 = (bits & (1 << SpanGrid.W)) != 0;
        if (pass == 0) {
            return !(p2 && p4 && p6) && !(p4 && p6 && p8);
        }
        return !(p2 && p4 && p8) && !(p2 && p6 && p8);
    }

    /** Exactly two bits set and they are cyclically consecutive directions (45° apart). */
    private static boolean adjacentPair(int bits) {
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int pair = (1 << d) | (1 << ((d + 1) & 7));
            if (bits == pair) {
                return true;
            }
        }
        return false;
    }

    /**
     * Holt's staircase removal (Holt, Stewart, Clint &amp; Perrott 1987), the standard post-pass for
     * Zhang-Suen: a skeleton span is a redundant staircase corner when it matches one of the two
     * templates (and their mirror images), which are built so that connectivity and line ends are
     * preserved. With the neighbours N, NE, E, SE, S, SW, W, NW of the span:
     * <pre>
     *   north pass: N &amp;&amp; ( (E &amp;&amp; !NE &amp;&amp; !SW &amp;&amp; (!W || !S)) || (W &amp;&amp; !NW &amp;&amp; !SE &amp;&amp; (!E || !S)) )
     *   south pass: S &amp;&amp; ( (E &amp;&amp; !SE &amp;&amp; !NW &amp;&amp; (!W || !N)) || (W &amp;&amp; !SW &amp;&amp; !NE &amp;&amp; (!E || !N)) )
     * </pre>
     * Applied sequentially on the current skeleton and repeated until nothing changes, so a
     * staircase becomes a clean diagonal without moving its ends.
     */
    static void removeStaircaseCorners(RoadMask mask, boolean[] on) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < on.length; i++) {
                    if (on[i] && staircaseCorner(mask, on, i, pass == 0)) {
                        on[i] = false;
                        changed = true;
                    }
                }
            }
        }
    }

    private static boolean staircaseCorner(RoadMask mask, boolean[] on, int i, boolean northPass) {
        boolean n = skeletonAt(mask, on, i, SpanGrid.N);
        boolean ne = skeletonAt(mask, on, i, SpanGrid.NE);
        boolean e = skeletonAt(mask, on, i, SpanGrid.E);
        boolean se = skeletonAt(mask, on, i, SpanGrid.SE);
        boolean s = skeletonAt(mask, on, i, SpanGrid.S);
        boolean sw = skeletonAt(mask, on, i, SpanGrid.SW);
        boolean w = skeletonAt(mask, on, i, SpanGrid.W);
        boolean nw = skeletonAt(mask, on, i, SpanGrid.NW);
        if (northPass) {
            return n && ((e && !ne && !sw && (!w || !s)) || (w && !nw && !se && (!e || !s)));
        }
        return s && ((e && !se && !nw && (!w || !n)) || (w && !sw && !ne && (!e || !n)));
    }

    private static boolean skeletonAt(RoadMask mask, boolean[] on, int i, int dir) {
        int nb = mask.neighbour(i, dir);
        return nb != RoadMask.NONE && on[nb];
    }

    /** Whether two spans are neighbours in the mask. */
    static boolean linked(RoadMask mask, int a, int b) {
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            if (mask.neighbour(a, d) == b) {
                return true;
            }
        }
        return false;
    }

    /** Number of skeleton neighbours of a span. */
    public static int degree(RoadMask mask, boolean[] skeleton, int i) {
        int degree = 0;
        for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
            int nb = mask.neighbour(i, d);
            if (nb != RoadMask.NONE && skeleton[nb]) {
                degree++;
            }
        }
        return degree;
    }
}
