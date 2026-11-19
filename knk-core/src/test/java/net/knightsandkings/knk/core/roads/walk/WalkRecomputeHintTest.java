package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#771 item 1: a recompute keeps the way its leg's found path took (capture bounds and length cap).
 */
class WalkRecomputeHintTest {

    private static final String S = GridFixture.STONE;

    @Test
    void theHintIsThePathFromThePlayersNearestPointToItsEnd() {
        List<double[]> path = List.of(new double[] {0, 64, 0}, new double[] {10, 64, 0}, new double[] {10, 64, 20});
        WalkRecomputeHint hint = WalkRecomputeHint.of(path, 4, 64, 3).orElseThrow();

        assertArrayEquals(new double[] {4, 64, 0}, hint.remaining().get(0), 1e-9);
        assertEquals(3, hint.remaining().size());
        // 6 + 20 left, 3 off the path (twice), the slack
        assertEquals(26 + 6 + WalkRecomputeHint.SLACK, hint.minLength(), 1e-9);
        assertArrayEquals(new int[] {4, 64, 0, 10, 64, 20}, hint.bounds());
    }

    @Test
    void noPathNoHint() {
        assertTrue(WalkRecomputeHint.of(null, 0, 64, 0).isEmpty());
        assertTrue(WalkRecomputeHint.of(List.of(new double[] {0, 64, 0}), 0, 64, 0).isEmpty());
    }

    @Test
    void theBudgetIsRaisedOnlyWhenItsCapIsShort() {
        WalkBudget defaults = WalkBudget.DEFAULTS;
        assertSame(defaults, defaults.withLengthAtLeast(10, 0, 50), "58 already allowed");

        WalkBudget raised = defaults.withLengthAtLeast(17, 13, 300);
        assertTrue(raised.lengthCap(17, 13) >= 300, String.valueOf(raised.lengthCap(17, 13)));
        assertEquals(defaults.maxExpansions(), raised.maxExpansions());
        assertEquals(defaults.climbAllowance(), raised.climbAllowance());
        assertEquals(defaults.maxLengthFactor(), raised.maxLengthFactor());
    }

    /**
     * The keep's scene 1 in small: the target is 13 blocks above a dead end, and the only way up is a long corridor
     * back. Today's cap for the short straight distance cuts it (FALLBACK, length); the found path's hint allows it.
     */
    @Test
    void aRecomputeWithTheHintRefindsTheLongWayTheFirstPathTook() {
        // a dead end at y 64 (x 0..2, z 0) and a corridor south (x 2, z 0..61); a ladder up at its far end
        // (2, 65..77, 61) onto a corridor back north at y 77 (x 2..4 at z 62, then x 4, z 0..62)
        WalkFixture f = new WalkFixture().floor(0, 0, 2, 0, 64, S).floor(2, 0, 2, 61, 64, S)
            .column(2, 61, 65, 77, WalkFixture.LADDER)
            .floor(2, 62, 4, 62, 77, S).floor(4, 0, 4, 62, 77, S);
        WalkRequest base = f.request(0, 64, 0, 4, 77, 0).withBudget(WalkBudget.DEFAULTS);
        WalkResult today = new WalkSearch().find(base);
        assertTrue(today.status() != WalkResult.Status.FOUND, today.toString());

        WalkResult first = new WalkSearch().find(base.withBudget(new WalkBudget(20_000, 1.75, 1000, 600, 5, 2, 3)));
        assertEquals(WalkResult.Status.FOUND, first.status(), first.toString());
        WalkRecomputeHint hint = WalkRecomputeHint.of(first.path().orElseThrow().points(), 0.5, 64, 0.5).orElseThrow();

        WalkResult again = new WalkSearch().find(base.withBudget(
            hint.widen(WalkBudget.DEFAULTS, base.straightDistance(), base.heightDifference())));
        assertEquals(WalkResult.Status.FOUND, again.status(), again.toString());
    }
}
