package net.knightsandkings.knk.core.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who has other players' name tags hidden in their own view, and until when (PandiO/knk-workspace#169, the
 * flash_chaos blackout). A second hit while one runs only moves the end further out (never closer), so overlapping
 * effects give one blackout that lasts until the last of them ends. No Bukkit; the Paper side shows the board.
 */
public final class ViewerBlackoutLedger {

    private final Map<UUID, Long> endsAtMs = new ConcurrentHashMap<>();

    /**
     * Starts or extends the viewer's blackout to {@code nowMs + durationMs}.
     *
     * @return true when this starts a new blackout (the board has to be put up), false when one was already running
     *         or the duration is 0 or less
     */
    public boolean start(UUID viewerId, long durationMs, long nowMs) {
        if (viewerId == null || durationMs <= 0L) {
            return false;
        }
        long until = nowMs + durationMs;
        Long previous = endsAtMs.get(viewerId);
        if (previous != null && previous > nowMs) {
            if (until > previous) {
                endsAtMs.put(viewerId, until);
            }
            return false;
        }
        endsAtMs.put(viewerId, until);
        return true;
    }

    /** @return true when the viewer had a blackout (the board has to come down) */
    public boolean end(UUID viewerId) {
        return viewerId != null && endsAtMs.remove(viewerId) != null;
    }

    public boolean isActive(UUID viewerId) {
        return viewerId != null && endsAtMs.containsKey(viewerId);
    }

    /** Remaining time, 0 without a blackout. */
    public long remainingMs(UUID viewerId, long nowMs) {
        Long until = viewerId == null ? null : endsAtMs.get(viewerId);
        return until == null ? 0L : Math.max(0L, until - nowMs);
    }

    /** Ends and returns the blackouts that have run out at {@code nowMs}. */
    public List<UUID> expire(long nowMs) {
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : endsAtMs.entrySet()) {
            if (e.getValue() <= nowMs && endsAtMs.remove(e.getKey(), e.getValue())) {
                expired.add(e.getKey());
            }
        }
        return expired;
    }

    public Set<UUID> viewers() {
        return Set.copyOf(endsAtMs.keySet());
    }
}
