package net.knightsandkings.knk.core.realtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Merges bursts of the same "this changed" event into one refresh (PandiO/knk-workspace#182): one save in the web app
 * can send several events for one section (a gate structure and its doors, a scenario and its spawnpoints, a road
 * survey's tiles), and each would otherwise re-read it. The first request for a key schedules its action after
 * {@code delay}; further requests for that key until it runs are dropped. A request while the action runs schedules
 * it again, so a change made during a re-read is never missed.
 */
public final class Coalescer {

    private final BiConsumer<Duration, Runnable> scheduler;
    private final Duration delay;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * @param scheduler runs a task after a delay (the plugin: the Bukkit scheduler, so actions run on the main thread)
     * @param delay     how long requests are gathered
     */
    public Coalescer(BiConsumer<Duration, Runnable> scheduler, Duration delay) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /** Runs {@code action} once after the delay, unless a request for {@code key} is already waiting. */
    public boolean request(String key, Runnable action) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        if (!pending.add(key)) {
            return false;
        }
        scheduler.accept(delay, () -> {
            pending.remove(key);
            action.run();
        });
        return true;
    }

    /** Whether a request for {@code key} is waiting. */
    public boolean isPending(String key) {
        return pending.contains(key);
    }
}
