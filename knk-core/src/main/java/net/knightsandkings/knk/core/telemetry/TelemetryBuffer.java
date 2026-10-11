package net.knightsandkings.knk.core.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, thread-safe holding area for diagnostic events between flushes (KNG-34 link 6,
 * IMPLEMENTATION_PLAN.md §5.2/§7: ≤ {@code telemetry.max-buffer-events}, default 5,000). When full,
 * the <b>oldest</b> event is dropped and counted, so the newest context around a problem survives
 * and the main thread never waits. Telemetry is never spooled (L1-23).
 */
public final class TelemetryBuffer {

    private final int capacity;
    private final ArrayDeque<TelemetryEvent> events;
    private long droppedSinceStart;
    private long droppedUnreported;

    public TelemetryBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.events = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    public int capacity() {
        return capacity;
    }

    /** Adds an event; drops (and counts) the oldest when full. */
    public synchronized void add(TelemetryEvent event) {
        if (event == null) {
            return;
        }
        if (events.size() >= capacity) {
            events.pollFirst();
            droppedSinceStart++;
            droppedUnreported++;
        }
        events.addLast(event);
    }

    /** Takes up to {@code max} events, oldest first. */
    public synchronized List<TelemetryEvent> drain(int max) {
        int n = Math.min(Math.max(0, max), events.size());
        List<TelemetryEvent> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(events.pollFirst());
        }
        return out;
    }

    /** Counts events lost after they left the buffer (e.g. a failed send); reported like overflow drops. */
    public synchronized void recordDropped(long count) {
        if (count > 0) {
            droppedSinceStart += count;
            droppedUnreported += count;
        }
    }

    /** Drops since the last call (the flush turns them into one {@code telemetry.dropped} event). */
    public synchronized long takeUnreportedDrops() {
        long dropped = droppedUnreported;
        droppedUnreported = 0;
        return dropped;
    }

    public synchronized long droppedSinceStart() {
        return droppedSinceStart;
    }

    public synchronized int size() {
        return events.size();
    }
}
