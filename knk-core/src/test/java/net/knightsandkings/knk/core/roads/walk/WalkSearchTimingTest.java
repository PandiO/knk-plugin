package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KNG-51 §8/§12 96×96 open-field measurement. Prints the numbers (they are recorded in the
 * Phase A status block); asserts only a loose bound so a slow CI machine cannot make it flaky.
 */
class WalkSearchTimingTest {

    private static final int SIZE = 96;
    private static final int RUNS = 20;

    private static WalkFixture openField() {
        return new WalkFixture().floor(0, 0, SIZE - 1, SIZE - 1, 64, GridFixture.GRASS);
    }

    private static double medianMillis(WalkRequest request, int runs) {
        WalkSearch search = new WalkSearch();
        for (int i = 0; i < 5; i++) {
            search.find(request); // warm-up
        }
        long[] nanos = new long[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            search.find(request);
            nanos[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(nanos);
        return nanos[runs / 2] / 1e6;
    }

    @Test
    void openField96AcrossIsFastAndStraight() {
        WalkFixture f = openField();
        WalkRequest across = WalkRequest.toPoint(f.terrain(), 0.5, 65, 48.5, 95.5, 64, 48.5, 0.25);
        WalkResult result = new WalkSearch().find(across);

        assertTrue(result.isFound(), result.toString());
        assertEquals(95.0, result.path().orElseThrow().length(), 1e-9);
        double millis = medianMillis(across, RUNS);
        System.out.printf("WALK-TIMING open-field 96x96 across (95 blocks): median %.3f ms, %d expansions%n",
            millis, result.expansions());
        assertTrue(millis < 500, "far below the routing executor's budget: " + millis + " ms");
    }

    @Test
    void openField96WorstCaseExpandsTheWholeField() {
        // the target stands on a pillar nobody can climb: the whole reachable field is expanded
        WalkFixture f = openField();
        f.column(80, 48, 65, 68, GridFixture.STONE);
        WalkRequest unreachable = WalkRequest.toPoint(f.terrain(), 0.5, 65, 48.5, 80.5, 68, 48.5, 0.25)
            .withBudget(new WalkBudget(20_000, 10.0, 400.0, 0.0, 2, 3));
        WalkResult result = new WalkSearch().find(unreachable);

        assertEquals(WalkResult.Status.NO_PATH, result.status(), result.toString());
        assertEquals(SIZE * SIZE - 1, result.expansions(), "every field cell but the pillar's");
        double millis = medianMillis(unreachable, 10);
        System.out.printf("WALK-TIMING open-field 96x96 worst case: median %.3f ms, %d expansions%n",
            millis, result.expansions());
        assertTrue(millis < 2_000, "worst case stays bounded: " + millis + " ms");
    }

    @Test
    void theDefaultBudgetCapsTheWorstCase() {
        WalkFixture f = openField();
        f.column(80, 48, 65, 68, GridFixture.STONE);
        WalkRequest unreachable = WalkRequest.toPoint(f.terrain(), 0.5, 65, 48.5, 80.5, 68, 48.5, 0.25);
        WalkResult result = new WalkSearch().find(unreachable);

        assertTrue(result.status() != WalkResult.Status.FOUND);
        assertTrue(result.expansions() <= WalkBudget.DEFAULTS.maxExpansions());
        double millis = medianMillis(unreachable, 10);
        System.out.printf("WALK-TIMING open-field 96x96 unreachable, default budget: median %.3f ms, %d expansions, %s%n",
            millis, result.expansions(), result.status());
    }
}
