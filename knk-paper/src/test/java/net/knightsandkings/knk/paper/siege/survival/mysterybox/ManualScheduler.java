package net.knightsandkings.knk.paper.siege.survival.mysterybox;

import java.util.ArrayList;
import java.util.List;

/** A tick scheduler driven by the test. */
final class ManualScheduler implements MysteryBoxAnimation.TickScheduler {

    record Scheduled(long ticks, Runnable task) { }

    final List<Scheduled> pending = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();

    @Override
    public Cancellable later(long ticks, Runnable task) {
        Scheduled s = new Scheduled(ticks, task);
        pending.add(s);
        delays.add(ticks);
        return () -> pending.remove(s);
    }

    /** Runs the next pending task; false when nothing is pending. */
    boolean runNext() {
        if (pending.isEmpty()) return false;
        Scheduled s = pending.remove(0);
        s.task().run();
        return true;
    }

    int runAll() {
        int ran = 0;
        while (runNext()) ran++;
        return ran;
    }
}
