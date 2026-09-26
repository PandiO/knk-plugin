package net.knightsandkings.knk.core.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InventoryMenu Phase 9 (E4): Bukkit-free bookkeeping for live repaint - which
 * open menus are due for a re-render this tick. knk-paper's single
 * {@code MenuAutoRefreshTask} asks {@link #due} once per tick and re-renders
 * everything it returns in that same tick (batched), then reports back via
 * {@link #completed}.
 * <ul>
 *   <li>A menu with {@code AutoRefreshTicks = N} is due every N ticks after its
 *       last completed render.</li>
 *   <li>{@link #markStale} (event-driven refresh -
 *       {@code MenuService.refreshOpenMenus}) makes a menu due on the next tick
 *       regardless of its period, including menus without auto-refresh.</li>
 *   <li>A menu whose render is still in flight (an async content fetch) is
 *       never returned again until it completes - renders never overlap; a
 *       {@link #markStale} during the flight is kept and honoured right after.</li>
 * </ul>
 * Ticks are whatever monotonic tick clock the caller uses consistently (the
 * menu engine's {@code System.currentTimeMillis() / 50}).
 */
public final class MenuRefreshSchedule {

    private static final class Entry {
        final int periodTicks;
        long lastRenderTick;
        boolean inFlight;
        boolean stale;

        Entry(int periodTicks, long lastRenderTick) {
            this.periodTicks = periodTicks;
            this.lastRenderTick = lastRenderTick;
        }
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Starts (or restarts) tracking {@code playerId}'s open menu after it was
     * rendered at {@code nowTick}. {@code periodTicks <= 0} tracks it for
     * {@link #markStale} only.
     */
    public void track(UUID playerId, int periodTicks, long nowTick) {
        entries.put(playerId, new Entry(Math.max(0, periodTicks), nowTick));
    }

    public void untrack(UUID playerId) {
        entries.remove(playerId);
    }

    public boolean isTracked(UUID playerId) {
        return entries.containsKey(playerId);
    }

    /** Requests a re-render on the next {@link #due} call (event-driven refresh). No-op if not tracked. */
    public void markStale(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry != null) {
            synchronized (entry) {
                entry.stale = true;
            }
        }
    }

    /**
     * The menus due at {@code nowTick}; each returned one is marked in flight
     * until {@link #completed} is called for it.
     */
    public List<UUID> due(long nowTick) {
        List<UUID> due = new ArrayList<>();
        entries.forEach((playerId, entry) -> {
            synchronized (entry) {
                if (entry.inFlight) {
                    return;
                }
                boolean periodElapsed = entry.periodTicks > 0 && nowTick - entry.lastRenderTick >= entry.periodTicks;
                if (periodElapsed || entry.stale) {
                    entry.inFlight = true;
                    entry.stale = false;
                    due.add(playerId);
                }
            }
        });
        return due;
    }

    /** Records that a refresh render finished (successfully or not) at {@code nowTick}. */
    public void completed(UUID playerId, long nowTick) {
        Entry entry = entries.get(playerId);
        if (entry != null) {
            synchronized (entry) {
                entry.inFlight = false;
                entry.lastRenderTick = nowTick;
            }
        }
    }

    public int trackedCount() {
        return entries.size();
    }
}
