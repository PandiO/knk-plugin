package net.knightsandkings.knk.core.production.terminal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one display refresh per container in {@code minIntervalMillis} (DESIGN § 6.2: "after changes (throttled)"). A
 * refresh asked for within the interval is remembered and due once the interval has passed, so the last change is never
 * lost.
 */
public final class DisplayThrottle {

    private final long minIntervalMillis;
    private final Map<Integer, Long> lastRun = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> pending = new ConcurrentHashMap<>();

    public DisplayThrottle(long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /** True when the container may refresh now (and records the run); false marks it pending. */
    public boolean tryRun(int containerId, long nowMillis) {
        Long last = lastRun.get(containerId);
        if (last != null && nowMillis - last < minIntervalMillis) {
            pending.put(containerId, Boolean.TRUE);
            return false;
        }
        lastRun.put(containerId, nowMillis);
        pending.remove(containerId);
        return true;
    }

    /** Pending containers whose interval has passed; they are marked as run. */
    public java.util.List<Integer> due(long nowMillis) {
        java.util.List<Integer> due = new java.util.ArrayList<>();
        for (Integer id : pending.keySet()) {
            Long last = lastRun.get(id);
            if (last == null || nowMillis - last >= minIntervalMillis) {
                pending.remove(id);
                lastRun.put(id, nowMillis);
                due.add(id);
            }
        }
        return due;
    }

    public void forget(int containerId) {
        lastRun.remove(containerId);
        pending.remove(containerId);
    }
}
