package net.knightsandkings.knk.core.teleport;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-teleport cooldowns per (player, kind) (docs/specs/teleport/DESIGN.md §3.4 step 4, §4 D4).
 * Only player-initiated kinds are ever started; staff teleports have no cooldown.
 * <p>
 * Kept separate from knk-paper's {@code CommandCooldownManager}: that one reads the wall clock
 * itself and lives in knk-paper, while this takes the time as an argument so the engine's timing
 * is unit-testable here, and it stores an expiry instead of a start time so the remaining time is
 * right even if the configured cooldown changes on reload.
 */
public final class TeleportCooldowns {

    private final Map<Key, Long> expiresAtMillis = new ConcurrentHashMap<>();

    /** Start (or restart) a cooldown of {@code seconds} for {@code player}'s {@code kind} teleports. */
    public void start(UUID player, TeleportKind kind, long nowMillis, int seconds) {
        if (seconds <= 0) {
            expiresAtMillis.remove(new Key(player, kind));
            return;
        }
        expiresAtMillis.put(new Key(player, kind), nowMillis + seconds * 1000L);
    }

    /** Whole seconds left, rounded up (so "1 s" is shown until it's really over); 0 when none. */
    public int remainingSeconds(UUID player, TeleportKind kind, long nowMillis) {
        Long expiry = expiresAtMillis.get(new Key(player, kind));
        if (expiry == null) {
            return 0;
        }
        long left = expiry - nowMillis;
        if (left <= 0) {
            expiresAtMillis.remove(new Key(player, kind), expiry);
            return 0;
        }
        return (int) ((left + 999) / 1000);
    }

    public boolean isCoolingDown(UUID player, TeleportKind kind, long nowMillis) {
        return remainingSeconds(player, kind, nowMillis) > 0;
    }

    public void clear(UUID player) {
        expiresAtMillis.keySet().removeIf(key -> key.player().equals(player));
    }

    /** Drop expired entries (called from the engine's periodic tick so the map can't grow forever). */
    public void purgeExpired(long nowMillis) {
        expiresAtMillis.values().removeIf(expiry -> expiry <= nowMillis);
    }

    private record Key(UUID player, TeleportKind kind) {
        Key {
            Objects.requireNonNull(player, "player must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }
}
