package net.knightsandkings.knk.core.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.DomainInteraction;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MenuStep;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MovementCell;

/**
 * The current world-analytics aggregation window (KNG-34 link 7, IMPLEMENTATION_PLAN.md §3.4): movement
 * cells, menu funnel steps and domain interactions since the last flush. A window <b>never crosses a
 * local midnight</b> of the statistics time zone (the catalogue's {@code timeZone}): the first record of
 * a new local day closes the old window first, and the day's distinct-player sets start empty. Closed
 * windows wait in a bounded queue until {@link #drain()} hands them to the sender; a batch the API could
 * not take goes back with {@link #requeue(List)} (oldest dropped beyond the bound - analytics are never
 * spooled to disk). Thread-safe (every method synchronizes; calls come from the main thread and the
 * flush task).
 */
public final class WorldAnalyticsWindow {

    /** The API's per-batch row limit is 20,000; stay well below. */
    public static final int DEFAULT_MAX_ROWS_PER_BATCH = 10_000;

    private final Clock clock;
    private final int maxPendingBatches;
    private final int maxRowsPerBatch;
    private final MovementCellGrid cells;
    private final MenuFunnelCounter menus = new MenuFunnelCounter();
    private final DomainInteractionCounter domains = new DomainInteractionCounter();
    private final Deque<WorldAnalyticsBatch> pending = new ArrayDeque<>();

    private ZoneId zone;
    private Instant windowStart;
    private LocalDate windowDay;
    private long droppedBatches;

    public WorldAnalyticsWindow(int cellSize, Clock clock, ZoneId zone, int maxPendingBatches) {
        this(cellSize, clock, zone, maxPendingBatches, DEFAULT_MAX_ROWS_PER_BATCH);
    }

    public WorldAnalyticsWindow(int cellSize, Clock clock, ZoneId zone, int maxPendingBatches, int maxRowsPerBatch) {
        this.cells = new MovementCellGrid(cellSize);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.maxPendingBatches = Math.max(1, maxPendingBatches);
        this.maxRowsPerBatch = Math.max(1, maxRowsPerBatch);
        this.windowStart = clock.instant();
        this.windowDay = LocalDate.ofInstant(windowStart, zone);
    }

    /** The statistics time zone, once the API's catalogue answered. Applies from the next record. */
    public synchronized void setZone(ZoneId zone) {
        if (zone != null) {
            this.zone = zone;
        }
    }

    public synchronized ZoneId zone() {
        return zone;
    }

    // ===== records (main thread) =====

    public synchronized void sample(String world, int blockX, int blockZ) {
        roll();
        cells.sample(world, blockX, blockZ);
    }

    public synchronized void menuOpened(String menuKey) {
        roll();
        menus.opened(menuKey);
    }

    public synchronized void menuBack(String menuKey) {
        roll();
        menus.back(menuKey);
    }

    public synchronized void menuClosed(String menuKey) {
        roll();
        menus.closed(menuKey);
    }

    public synchronized void menuAction(String menuKey, String actionTypeId, String outcome) {
        roll();
        menus.action(menuKey, actionTypeId, outcome);
    }

    public synchronized void regionEntered(String regionId, UUID player) {
        roll();
        domains.region(regionId, DomainInteractionCounter.ENTER, player);
    }

    public synchronized void regionLeft(String regionId, UUID player) {
        roll();
        domains.region(regionId, DomainInteractionCounter.LEAVE, player);
    }

    public synchronized void domainDiscovered(int domainId, UUID player) {
        roll();
        domains.discovered(domainId, player);
    }

    // ===== flush =====

    /** Closes the current window (if anything was recorded) and returns every closed batch, oldest first. */
    public synchronized List<WorldAnalyticsBatch> drain() {
        roll();
        closeWindow(clock.instant());
        List<WorldAnalyticsBatch> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** Puts batches the API could not take back in front of the queue (they keep their ids). */
    public synchronized void requeue(List<WorldAnalyticsBatch> batches) {
        for (int i = batches.size() - 1; i >= 0; i--) {
            pending.addFirst(batches.get(i));
        }
        trim();
    }

    /** Closed windows dropped because the queue was full (the API was unreachable for long). */
    public synchronized long droppedBatches() {
        return droppedBatches;
    }

    public synchronized int pendingBatches() {
        return pending.size();
    }

    public synchronized boolean currentWindowIsEmpty() {
        return cells.isEmpty() && menus.isEmpty() && domains.isEmpty();
    }

    /** A new local day closes the window of the previous day before anything of the new day is counted. */
    private void roll() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (today.equals(windowDay)) {
            return;
        }
        closeWindow(now);
        domains.newDay();
        windowDay = today;
    }

    private void closeWindow(Instant now) {
        if (!currentWindowIsEmpty()) {
            List<MovementCell> movement = cells.drain();
            List<MenuStep> steps = menus.drain();
            List<DomainInteraction> interactions = domains.drain();
            split(windowStart, movement, steps, interactions);
        }
        windowStart = now;
        windowDay = LocalDate.ofInstant(now, zone);
    }

    private void split(Instant start, List<MovementCell> movement, List<MenuStep> steps, List<DomainInteraction> interactions) {
        int total = movement.size() + steps.size() + interactions.size();
        if (total <= maxRowsPerBatch) {
            enqueue(new WorldAnalyticsBatch(UUID.randomUUID(), start, movement, steps, interactions));
            return;
        }
        // Rare: more rows than one batch may carry. Same window, several batches (each its own id).
        List<Object> rows = new ArrayList<>(total);
        rows.addAll(steps);
        rows.addAll(interactions);
        rows.addAll(movement);
        for (int from = 0; from < total; from += maxRowsPerBatch) {
            List<MovementCell> m = new ArrayList<>();
            List<MenuStep> s = new ArrayList<>();
            List<DomainInteraction> d = new ArrayList<>();
            for (Object row : rows.subList(from, Math.min(total, from + maxRowsPerBatch))) {
                if (row instanceof MovementCell cell) {
                    m.add(cell);
                } else if (row instanceof MenuStep step) {
                    s.add(step);
                } else {
                    d.add((DomainInteraction) row);
                }
            }
            enqueue(new WorldAnalyticsBatch(UUID.randomUUID(), start, m, s, d));
        }
    }

    private void enqueue(WorldAnalyticsBatch batch) {
        pending.addLast(batch);
        trim();
    }

    private void trim() {
        while (pending.size() > maxPendingBatches) {
            pending.removeFirst();
            droppedBatches++;
        }
    }
}
