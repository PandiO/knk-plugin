package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.WORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RecoveryLadder.Report;
import net.knightsandkings.knk.core.npc.nav.RecoveryLadder.Reporter;
import net.knightsandkings.knk.core.npc.nav.RecoveryLadder.Step;

/** The recovery ladder's order, spacing, cap and counting on a fake clock (test plan item 16.4). */
class RecoveryLadderTest {

    private static final NavPoint SPOT = new NavPoint(WORLD, 10.5, 65, 0.5);
    private static final int WAIT = 20;

    private final RecoveryLadder ladder = new RecoveryLadder();

    @Test
    void theStepsComeInOrderOnePerTryThenTheReplan() {
        assertSame(Report.STARTED, ladder.report(0, SPOT, Reporter.STATIONARY, true, WAIT));
        List<Step> steps = new ArrayList<>();
        long tick = 0;
        for (int i = 0; i < 5; i++) {
            steps.add(ladder.nextStep(tick, 3, step -> true));
            assertSame(Report.TRY_FAILED, ladder.report(tick + 5, SPOT, Reporter.STATIONARY, true, WAIT));
            assertEquals(tick + 5 + WAIT, ladder.nextTryTick(), "the next try waits recovery-wait-ticks");
            tick = ladder.nextTryTick();
        }

        assertEquals(List.of(Step.BACK_OFF, Step.SIDE_STEP, Step.LOCAL_LEG, Step.REPLAN, Step.REPLAN), steps);
        assertEquals(3, ladder.used(), "the replans do not count against max-retries");
        assertEquals(5, ladder.taken());
    }

    @Test
    void reportsBetweenTwoTriesJoinTheSpell() {
        ladder.report(0, SPOT, Reporter.NO_PROGRESS, false, WAIT);
        ladder.nextStep(0, 3, step -> true);
        ladder.report(3, SPOT, Reporter.STATIONARY, false, WAIT);

        assertSame(Report.JOINED, ladder.report(4, SPOT, Reporter.STATIONARY, false, WAIT));
        assertSame(Reporter.NO_PROGRESS, ladder.reporter(), "counted by what reported it first");
    }

    @Test
    void withMaxRetriesOneOnlyTheBackOffThenTheReplan() {
        ladder.report(0, SPOT, Reporter.STATIONARY, false, WAIT);

        assertSame(Step.BACK_OFF, ladder.nextStep(0, 1, step -> true));
        ladder.tryFailed(40, WAIT);
        assertSame(Step.REPLAN, ladder.nextStep(60, 1, step -> true));
        assertEquals(1, RecoveryLadder.cap(1));
        assertEquals(3, RecoveryLadder.cap(10), "three steps there are");
        assertEquals(0, RecoveryLadder.cap(0));
    }

    @Test
    void stepsThatCannotBeTakenAreSkippedAndNotCounted() {
        ladder.report(0, SPOT, Reporter.STATIONARY, false, WAIT);

        Step first = ladder.nextStep(0, 3, step -> step == Step.LOCAL_LEG);

        assertSame(Step.LOCAL_LEG, first, "no footsteps, no wall: straight to the local leg");
        assertEquals(1, ladder.used());
        assertTrue(ladder.stepLine("npc", "stationary", SPOT, null, 3, null).contains("step 1 of 3 local leg"));
    }

    @Test
    void theLogsCountNeverPassesTheCap() {
        ladder.report(0, SPOT, Reporter.STATIONARY, true, WAIT);
        RecoveryMoves.Wall wall = new RecoveryMoves.Wall(11, 65, 0, 1, 0);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ladder.nextStep(i * 30L, 3, step -> true);
            lines.add(ladder.stepLine("npc-1", "stationary", SPOT, wall, 3, null));
            ladder.tryFailed(i * 30L + 10, WAIT);
        }

        assertEquals("npc-1 stuck (stationary) at 10 65 0, wall at 11 65 0 (east): step 1 of 3 back-off", lines.get(0));
        assertTrue(lines.get(2).endsWith("step 3 of 3 local leg"), lines.get(2));
        assertEquals("npc-1 not freed at 10 65 0: replanning (steps used up)", lines.get(3));
        assertTrue(lines.stream().noneMatch(line -> line.contains("of 3") && line.matches(".*step [4-9] of 3.*")));
    }

    @Test
    void aSpellEndsFreedByTheStepThatWorked() {
        ladder.report(0, SPOT, Reporter.STATIONARY, false, WAIT);
        ladder.nextStep(0, 3, step -> true);
        ladder.tryFailed(40, WAIT);
        ladder.nextStep(60, 3, step -> true);

        assertSame(Event.FREED_SIDE_STEP, ladder.freed(70, SPOT));
        assertFalse(ladder.isOpen());
        assertEquals("npc freed at 10 65 0 by side-step (2 steps, 3.5 s)",
            RecoveryLadder.freedLine("npc", SPOT, Step.SIDE_STEP, 2, 70));
    }

    @Test
    void aNewStuckAtTheSameSpotSoonContinuesTheLadderElsewhereItStartsAgain() {
        ladder.report(0, SPOT, Reporter.STATIONARY, false, WAIT);
        ladder.nextStep(0, 3, step -> true); // back-off
        ladder.freed(30, SPOT);

        ladder.report(100, new NavPoint(WORLD, 11.5, 65, 0.5), Reporter.STATIONARY, false, WAIT);
        assertSame(Step.SIDE_STEP, ladder.nextStep(100, 3, step -> true), "within 2 blocks and 30 s: where it stopped");
        ladder.freed(120, SPOT);

        ladder.report(120 + RecoveryLadder.CONTINUE_TICKS + 1, SPOT, Reporter.STATIONARY, false, WAIT);
        assertSame(Step.BACK_OFF, ladder.nextStep(800, 3, step -> true), "more than 30 s later: from the start");
        ladder.freed(820, SPOT);

        ladder.report(830, new NavPoint(WORLD, 20.5, 65, 0.5), Reporter.STATIONARY, false, WAIT);
        assertSame(Step.BACK_OFF, ladder.nextStep(830, 3, step -> true), "elsewhere: from the start");
    }
}
