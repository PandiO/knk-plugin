package net.knightsandkings.knk.core.roads.build;

import java.util.Arrays;

/**
 * Distance transform over a {@link RoadMask} (DESIGN §5.5 step 1): for every span, the Chebyshev
 * distance in spans to the nearest non-road cell, by multi-source BFS from the border spans over the
 * mask's 8-neighbour table. A border span (some neighbour missing) has distance 1, its road-side
 * neighbours 2, and so on; the road's local width is {@link #width(int)} {@code = 2·dt − 1}
 * (exact for odd widths: a 1-wide path is 1, a 5-wide road's centre row is 5).
 */
public final class DistanceTransform {
    private DistanceTransform() {
    }

    /** Distance per span index; 1 for border spans and for spans without any neighbour. */
    public static int[] compute(RoadMask mask) {
        int n = mask.size();
        int[] dt = new int[n];
        Arrays.fill(dt, 0);
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            if (mask.isBorder(i)) {
                dt[i] = 1;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int next = dt[i] + 1;
            for (int d = 0; d < SpanGrid.DIRECTIONS; d++) {
                int nb = mask.neighbour(i, d);
                if (nb != RoadMask.NONE && dt[nb] == 0) {
                    dt[nb] = next;
                    queue[tail++] = nb;
                }
            }
        }
        // Unreachable spans would be an interior without any border - impossible on a finite mask,
        // but keep the invariant "dt >= 1" whatever happens.
        for (int i = 0; i < n; i++) {
            if (dt[i] == 0) {
                dt[i] = 1;
            }
        }
        return dt;
    }

    /** Local road width implied by a distance value: {@code 2·dt − 1}. */
    public static int width(int dt) {
        return 2 * dt - 1;
    }

    /** Half the local width, measured from the span's centre to the road's edge: {@code dt − 0.5}. */
    public static double halfWidth(int dt) {
        return dt - 0.5;
    }
}
