package net.knightsandkings.knk.core.realtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#182 (S3) and #31: the pure pieces behind the config/entity handlers and the startup retry. */
class ConfigSyncCoreTest {

    private final List<Runnable> scheduled = new ArrayList<>();
    private final Coalescer coalescer = new Coalescer((delay, task) -> scheduled.add(task), Duration.ofMillis(500));

    private void runScheduled() {
        List<Runnable> now = List.copyOf(scheduled);
        scheduled.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void coalescer_mergesABurst_perKey() {
        AtomicInteger gate = new AtomicInteger();
        AtomicInteger roads = new AtomicInteger();
        assertTrue(coalescer.request("gate:3", gate::incrementAndGet));
        assertFalse(coalescer.request("gate:3", gate::incrementAndGet));
        assertTrue(coalescer.request("roads", roads::incrementAndGet));
        assertTrue(coalescer.isPending("gate:3"));
        assertEquals(0, gate.get());

        runScheduled();

        assertEquals(1, gate.get());
        assertEquals(1, roads.get());
        assertFalse(coalescer.isPending("gate:3"));
    }

    @Test
    void coalescer_aRequestWhileTheActionRuns_isScheduledAgain() {
        AtomicInteger runs = new AtomicInteger();
        Runnable[] action = new Runnable[1];
        action[0] = () -> {
            if (runs.incrementAndGet() == 1) {
                coalescer.request("game", action[0]); // a change arrives during the re-read
            }
        };
        coalescer.request("game", action[0]);
        runScheduled();
        assertEquals(1, runs.get());
        assertEquals(1, scheduled.size());
        runScheduled();
        assertEquals(2, runs.get());
    }

    @Test
    void router_handsTheChangeToEveryHandlerOfTheType_andSurvivesAFailingOne() {
        List<String> seen = new ArrayList<>();
        EntityChangeRouter router = new EntityChangeRouter()
            .on("Town", (id, key, op) -> {
                throw new IllegalStateException("boom");
            })
            .on("Town", (id, key, op) -> seen.add("town " + id + " " + key + " " + op))
            .on("Kit", (id, key, op) -> seen.add("kit " + id));

        assertTrue(router.route("Town", 4, "town_4", "Updated"));
        assertTrue(router.route("Kit", null, null, "Updated"));
        assertFalse(router.route("Street", 1, null, "Updated"));

        assertEquals(List.of("town 4 town_4 Updated", "kit null"), seen);
        assertEquals(java.util.Set.of("Town", "Kit"), router.types());
        assertThrows(IllegalArgumentException.class, () -> router.on(" ", (id, key, op) -> { }));
    }

    @Test
    void startupWork_failedStepStaysPending_succeededStepIsNotRepeated() {
        PendingStartupWork work = new PendingStartupWork();
        List<String> order = new ArrayList<>();
        AtomicInteger abortCalls = new AtomicInteger();
        boolean[] apiUp = {false};
        PendingStartupWork.Step abort = work.register("abort unfinished matches", () -> {
            abortCalls.incrementAndGet();
            order.add("abort");
            return CompletableFuture.completedFuture(apiUp[0]);
        });
        PendingStartupWork.Step gates = work.register("restore stale gates", () -> {
            order.add("gates");
            return apiUp[0] ? CompletableFuture.completedFuture(true) : CompletableFuture.failedFuture(new RuntimeException("down"));
        });
        PendingStartupWork.Step config = work.register("siege config", () -> {
            order.add("config");
            return CompletableFuture.completedFuture(true);
        });

        // Startup with the API down: each step runs once where it ran before.
        assertFalse(abort.run().join());
        assertFalse(gates.run().join());
        assertTrue(config.run().join());
        assertEquals(List.of("abort unfinished matches", "restore stale gates"), work.pending());

        // First reconnect, API still flaky: everything pending runs again, in order; config is not repeated.
        order.clear();
        assertEquals(List.of("abort unfinished matches", "restore stale gates"), work.runPending().join());
        assertEquals(List.of("abort", "gates"), order);

        // API up: both succeed and are never run again.
        apiUp[0] = true;
        order.clear();
        assertEquals(List.of(), work.runPending().join());
        assertEquals(List.of("abort", "gates"), order);
        order.clear();
        work.runPending().join();
        assertEquals(List.of(), order);
        assertEquals(3, abortCalls.get());
    }

    @Test
    void startupWork_aRunningStepIsNotStartedTwice_andAThrowingBodyKeepsItPending() {
        PendingStartupWork work = new PendingStartupWork();
        CompletableFuture<Boolean> slow = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        PendingStartupWork.Step step = work.register("slow", () -> {
            calls.incrementAndGet();
            return slow;
        });
        CompletableFuture<Boolean> first = step.run();
        assertSame(first, step.run());
        assertEquals(1, calls.get());
        slow.complete(true);
        assertTrue(first.join());

        PendingStartupWork.Step broken = work.register("broken", () -> {
            throw new IllegalStateException("not ready");
        });
        assertFalse(broken.run().join());
        assertEquals(List.of("broken"), work.pending());
        broken.markDone();
        assertEquals(List.of(), work.pending());
        assertThrows(IllegalArgumentException.class, () -> work.register("slow", () -> null));
    }
}
