package net.knightsandkings.knk.core.scenario;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioRunTest {

    /** Ticks the run until it finishes (at most 10 000 ticks) and returns how many ticks that took. */
    private static int runToEnd(ScenarioRun run) {
        for (int tick = 1; tick <= 10_000; tick++) {
            if (run.tick()) {
                return tick;
            }
        }
        fail("run did not finish");
        return -1;
    }

    @Test
    void actsAndExpectationsRunInTheSameTick() {
        List<String> log = new ArrayList<>();
        ScenarioPlan plan = new ScenarioPlan()
            .act("one", () -> log.add("one"))
            .expect("one ran", () -> log.contains("one"))
            .act("two", () -> log.add("two"));
        ScenarioRun run = new ScenarioRun("same-tick", plan);

        assertTrue(run.tick());
        assertEquals(List.of("one", "two"), log);
        assertEquals(ScenarioResult.pass("same-tick", 1), run.result());
    }

    @Test
    void waitTicksResumesThatManyTicksLater() {
        AtomicInteger tick = new AtomicInteger();
        List<Integer> ranAt = new ArrayList<>();
        ScenarioPlan plan = new ScenarioPlan()
            .act("first", () -> ranAt.add(tick.get()))
            .waitTicks(5)
            .act("second", () -> ranAt.add(tick.get()));
        ScenarioRun run = new ScenarioRun("wait", plan);

        boolean done = false;
        while (!done) {
            tick.incrementAndGet();
            done = run.tick();
        }
        assertEquals(List.of(1, 6), ranAt);
        assertTrue(run.result().passed());
        assertEquals(6, run.result().ticks());
    }

    @Test
    void awaitPassesAsSoonAsTheConditionHolds() {
        AtomicInteger counter = new AtomicInteger();
        ScenarioPlan plan = new ScenarioPlan()
            .act("count", counter::incrementAndGet)
            .await("counter reaches 1", 10, () -> counter.get() >= 1);
        ScenarioRun run = new ScenarioRun("await-now", plan);

        assertEquals(1, runToEnd(run));
        assertTrue(run.result().passed());
    }

    @Test
    void awaitChecksEveryTickUntilTheConditionHolds() {
        AtomicInteger checks = new AtomicInteger();
        ScenarioPlan plan = new ScenarioPlan().await("third check", 10, () -> checks.incrementAndGet() >= 3);
        ScenarioRun run = new ScenarioRun("await-later", plan);

        assertEquals(3, runToEnd(run));
        assertEquals(3, checks.get());
        assertTrue(run.result().passed());
    }

    @Test
    void awaitFailsAfterItsTimeout() {
        ScenarioPlan plan = new ScenarioPlan().await("never", 4, () -> false);
        ScenarioRun run = new ScenarioRun("await-timeout", plan);

        assertEquals(5, runToEnd(run));
        assertFalse(run.result().passed());
        assertEquals("timed out after 4 ticks waiting for: never", run.result().reason());
    }

    @Test
    void aFailedExpectationStopsTheRun() {
        List<String> log = new ArrayList<>();
        ScenarioPlan plan = new ScenarioPlan()
            .expect("the moon is cheese", () -> false)
            .act("after", () -> log.add("after"));
        ScenarioRun run = new ScenarioRun("expect", plan);

        runToEnd(run);
        assertEquals("expected: the moon is cheese", run.result().reason());
        assertTrue(log.isEmpty());
    }

    @Test
    void expectEqualsNamesBothValues() {
        ScenarioPlan plan = new ScenarioPlan().expectEquals("door state", () -> "CLOSED", "OPEN");
        ScenarioRun run = new ScenarioRun("equals", plan);

        runToEnd(run);
        assertEquals("door state: expected OPEN, was CLOSED", run.result().reason());
    }

    @Test
    void checkUsesTheReasonItReturns() {
        ScenarioPlan plan = new ScenarioPlan().check("cells", () -> "2 of 9 cells are stone");
        ScenarioRun run = new ScenarioRun("check", plan);

        runToEnd(run);
        assertEquals("2 of 9 cells are stone", run.result().reason());
    }

    @Test
    void anExceptionFailsTheRunWithTheStepName() {
        ScenarioPlan plan = new ScenarioPlan().act("explode", () -> {
            throw new IllegalStateException("boom");
        });
        ScenarioRun run = new ScenarioRun("throws", plan);

        runToEnd(run);
        assertEquals("step 'explode' threw IllegalStateException: boom", run.result().reason());
    }

    @Test
    void anAssertionErrorKeepsItsOwnReason() {
        ScenarioPlan plan = new ScenarioPlan().act("assert", () -> {
            throw new ScenarioAssertionError("pig is inside the door");
        });
        ScenarioRun run = new ScenarioRun("assertion", plan);

        runToEnd(run);
        assertEquals("step 'assert' threw pig is inside the door", run.result().reason());
    }

    @Test
    void cleanupsRunLastAddedFirstAlsoAfterAFailure() {
        List<String> log = new ArrayList<>();
        ScenarioPlan plan = new ScenarioPlan()
            .cleanup("first", () -> log.add("first"))
            .cleanup("second", () -> log.add("second"))
            .expect("fails", () -> false);
        ScenarioRun run = new ScenarioRun("cleanups", plan);

        runToEnd(run);
        assertEquals(List.of("second", "first"), log);
        assertFalse(run.result().passed());
    }

    @Test
    void aThrowingCleanupFailsAPassedRunAndTheOthersStillRun() {
        List<String> log = new ArrayList<>();
        ScenarioPlan plan = new ScenarioPlan()
            .cleanup("kept", () -> log.add("kept"))
            .cleanup("broken", () -> {
                throw new IllegalStateException("no world");
            });
        ScenarioRun run = new ScenarioRun("cleanup-fails", plan);

        runToEnd(run);
        assertEquals(List.of("kept"), log);
        assertEquals("cleanup 'broken' threw IllegalStateException: no world", run.result().reason());
    }

    @Test
    void theFirstFailureWinsOverACleanupFailure() {
        ScenarioPlan plan = new ScenarioPlan()
            .cleanup("broken", () -> {
                throw new IllegalStateException("x");
            })
            .expect("step", () -> false);
        ScenarioRun run = new ScenarioRun("first-failure", plan);

        runToEnd(run);
        assertEquals("expected: step", run.result().reason());
    }

    @Test
    void tickAfterTheEndKeepsTheResult() {
        ScenarioRun run = new ScenarioRun("done", new ScenarioPlan());
        assertTrue(run.tick());
        assertTrue(run.tick());
        assertEquals(ScenarioResult.pass("done", 1), run.result());
    }

    @Test
    void planRejectsBadSteps() {
        ScenarioPlan plan = new ScenarioPlan();
        assertThrows(IllegalArgumentException.class, () -> plan.waitTicks(0));
        assertThrows(IllegalArgumentException.class, () -> plan.await("x", 0, () -> true));
        assertThrows(IllegalArgumentException.class, () -> plan.act(" ", () -> { }));
    }

    @Test
    void maxTicksAddsWaitsAndAwaitTimeouts() {
        ScenarioPlan plan = new ScenarioPlan().waitTicks(5).await("x", 40, () -> true).act("a", () -> { });
        assertEquals(45, plan.maxTicks());
    }
}
