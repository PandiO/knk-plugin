package net.knightsandkings.knk.core.effects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The effects a player is shown a boss bar for (PandiO/knk-workspace#170): one entry per effect key (for example
 * {@code freeze}, {@code poison}, {@code staff_freeze}), either <b>timed</b>, counting down to its end, or
 * <b>static</b> (no end, such as a staff {@code /freeze}). A new timed entry under a key that is already shown
 * replaces it, so a re-applied debuff restarts its bar instead of adding a second one.
 * <p>
 * Pure bookkeeping, no Bukkit: the Paper side draws one boss bar per {@link View} and calls {@link #sync} with the
 * real remaining time of the effect when it knows it better (a potion effect cut short by milk, a freeze ended by
 * death). Main thread only, apart from the concurrent maps that make a stray read safe.
 */
public final class TimedEffectBoard {

    /** One effect's bar as it should be drawn now. */
    public record View(String key, String label, boolean timed, long remainingMs, float progress) {
        /** The bar text: {@code Poison · 4s}, {@code Frozen · 1:05}, or the bare label of a static bar. */
        public String text() {
            return timed ? label + " · " + clock(remainingMs) : label;
        }
    }

    private static final class Entry {
        final String label;
        final boolean timed;
        long totalMs;
        long endsAtMs;

        Entry(String label, boolean timed, long totalMs, long endsAtMs) {
            this.label = label;
            this.timed = timed;
            this.totalMs = totalMs;
            this.endsAtMs = endsAtMs;
        }
    }

    private final Map<UUID, Map<String, Entry>> entries = new ConcurrentHashMap<>();

    /** Shows a bar counting down {@code durationMs} from {@code nowMs}; nothing for a duration of 0 or less. */
    public void startTimed(UUID playerId, String key, String label, long durationMs, long nowMs) {
        if (playerId == null || key == null || durationMs <= 0L) {
            return;
        }
        of(playerId).put(key, new Entry(label(label, key), true, durationMs, nowMs + durationMs));
    }

    /** Shows a full bar with just the label until {@link #end} (for an effect without an end time). */
    public void startStatic(UUID playerId, String key, String label) {
        if (playerId == null || key == null) {
            return;
        }
        of(playerId).put(key, new Entry(label(label, key), false, 0L, Long.MAX_VALUE));
    }

    /**
     * Corrects a timed bar to the effect's real remaining time. A longer time than the bar started with (the effect
     * was topped up elsewhere) becomes the new full bar; 0 or less ends the bar. Static bars ignore it.
     */
    public void sync(UUID playerId, String key, long remainingMs, long nowMs) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        Entry entry = own == null ? null : own.get(key);
        if (entry == null || !entry.timed) {
            return;
        }
        if (remainingMs <= 0L) {
            end(playerId, key);
            return;
        }
        entry.endsAtMs = nowMs + remainingMs;
        if (remainingMs > entry.totalMs) {
            entry.totalMs = remainingMs;
        }
    }

    public void end(UUID playerId, String key) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        if (own == null) {
            return;
        }
        own.remove(key);
        if (own.isEmpty()) {
            entries.remove(playerId, own);
        }
    }

    /** Ends every timed bar of the player (death); static bars stay. */
    public void endTimed(UUID playerId) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        if (own == null) {
            return;
        }
        own.values().removeIf(e -> e.timed);
        if (own.isEmpty()) {
            entries.remove(playerId, own);
        }
    }

    /** Forgets the player (quit). */
    public void clear(UUID playerId) {
        if (playerId != null) {
            entries.remove(playerId);
        }
    }

    public boolean has(UUID playerId, String key) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        return own != null && own.containsKey(key);
    }

    public Set<UUID> players() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public Set<String> keys(UUID playerId) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        return own == null ? Set.of() : Set.copyOf(own.keySet());
    }

    /**
     * The player's bars at {@code nowMs}, in the order they started. Timed bars that have run out are ended here and
     * left out.
     */
    public List<View> views(UUID playerId, long nowMs) {
        Map<String, Entry> own = playerId == null ? null : entries.get(playerId);
        if (own == null) {
            return List.of();
        }
        List<View> views = new ArrayList<>();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Entry> e : own.entrySet()) {
            Entry entry = e.getValue();
            if (!entry.timed) {
                views.add(new View(e.getKey(), entry.label, false, 0L, 1.0f));
                continue;
            }
            long remaining = entry.endsAtMs - nowMs;
            if (remaining <= 0L) {
                expired.add(e.getKey());
                continue;
            }
            float progress = (float) Math.max(0.0d, Math.min(1.0d, remaining / (double) Math.max(1L, entry.totalMs)));
            views.add(new View(e.getKey(), entry.label, true, remaining, progress));
        }
        expired.forEach(key -> end(playerId, key));
        return views;
    }

    /** {@code 4s} under a minute (rounded up, so the bar never shows 0s while it is still on), else {@code m:ss}. */
    public static String clock(long remainingMs) {
        long seconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        return (seconds / 60L) + ":" + String.format("%02d", seconds % 60L);
    }

    private Map<String, Entry> of(UUID playerId) {
        return entries.computeIfAbsent(playerId, id -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static String label(String label, String key) {
        return label == null || label.isBlank() ? Objects.toString(key, "") : label;
    }
}
