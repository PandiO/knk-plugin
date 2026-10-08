package net.knightsandkings.knk.core.teleport;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The places each player may go back to with {@code /back} (docs/specs/teleport/DESIGN.md §3.11,
 * Phase 7; Linear KNG-42): one entry per {@link BackKind} - their last death, and where they stood
 * before their last warp, teleport and {@code /spawn}. Each entry is available for a few minutes and
 * is single use.
 * <p>
 * Lifecycle of one entry:
 * <ol>
 *   <li>{@link #record} stores it (a newer entry of the same kind replaces the older one).</li>
 *   <li>{@link #claim} hands the <b>latest</b> unexpired entry among the kinds the player may use to one
 *       {@code /back} - one {@code /back} per player at a time. Expiry is only checked here: a
 *       {@code /back} started in time still works when its warmup ends after the deadline.</li>
 *   <li>The claim ends with {@link #consume} (the player arrived - the entry is used up) or
 *       {@link #release} (the warmup was cancelled, a guard refused it, the spot wasn't safe...) -
 *       then it can be claimed again until it expires.</li>
 * </ol>
 * Claims and releases name the {@link Entry} they're about, so a stale one never touches a newer
 * entry. Time is passed in, so the whole thing is unit-testable. In memory only - a restart forgets
 * every entry, which is fine for windows of a few minutes. Thread-safe (synchronized).
 *
 * @param <L> the stored location (knk-paper keeps world + coordinates, not a Bukkit Location)
 */
public final class BackLocationBook<L> {

    /** One recorded place; {@code id} tells entries apart and orders them (higher = recorded later). */
    public record Entry<L>(long id, UUID player, BackKind kind, L location, long recordedAtMillis, long expiresAtMillis) {

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        /** Whole seconds left to start a {@code /back}, rounded up; 0 once expired. */
        public int secondsLeft(long nowMillis) {
            long left = expiresAtMillis - nowMillis;
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<UUID, Map<BackKind, Entry<L>>> entries = new HashMap<>();
    /** The entry each player's running {@code /back} claimed. */
    private final Map<UUID, Entry<L>> claims = new HashMap<>();

    /**
     * Store where {@code player} was for {@code kind}, replacing an older entry of that kind (a claim
     * on the older one then finds nothing to consume or release).
     *
     * @param expireSeconds how long it can be used; at least 1
     */
    public synchronized Entry<L> record(UUID player, BackKind kind, L location, long nowMillis, int expireSeconds) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Entry<L> entry = new Entry<>(ids.incrementAndGet(), player, kind, location, nowMillis,
            nowMillis + Math.max(1, expireSeconds) * 1000L);
        entries.computeIfAbsent(player, id -> new EnumMap<>(BackKind.class)).put(kind, entry);
        return entry;
    }

    /** The latest entry of {@code kinds} the player can use now (unexpired, not claimed). */
    public synchronized Optional<Entry<L>> available(UUID player, Set<BackKind> kinds, long nowMillis) {
        Map<BackKind, Entry<L>> own = entries.get(player);
        if (own == null || kinds == null || kinds.isEmpty()) {
            return Optional.empty();
        }
        Entry<L> claimed = claims.get(player);
        Entry<L> latest = null;
        for (BackKind kind : kinds) {
            Entry<L> entry = own.get(kind);
            if (entry == null || entry.isExpired(nowMillis) || entry.equals(claimed)) {
                continue;
            }
            if (latest == null || entry.id() > latest.id()) {
                latest = entry;
            }
        }
        return Optional.ofNullable(latest);
    }

    /** Whether a {@code /back} of {@code player} is running right now. */
    public synchronized boolean isClaimed(UUID player) {
        return claims.containsKey(player);
    }

    /**
     * Take the latest usable entry of {@code kinds} for one {@code /back}; empty when there's none, or
     * when another {@code /back} of the player is still running.
     */
    public synchronized Optional<Entry<L>> claim(UUID player, Set<BackKind> kinds, long nowMillis) {
        if (claims.containsKey(player)) {
            return Optional.empty();
        }
        Optional<Entry<L>> latest = available(player, kinds, nowMillis);
        latest.ifPresent(entry -> claims.put(player, entry));
        return latest;
    }

    /** The claimed {@code /back} didn't happen: {@code entry} may be claimed again until it expires. */
    public synchronized void release(Entry<L> entry) {
        claims.remove(entry.player(), entry);
    }

    /** The player arrived: {@code entry} is used up (single use). */
    public synchronized void consume(Entry<L> entry) {
        claims.remove(entry.player(), entry);
        Map<BackKind, Entry<L>> own = entries.get(entry.player());
        if (own != null) {
            own.remove(entry.kind(), entry);
            if (own.isEmpty()) {
                entries.remove(entry.player());
            }
        }
    }

    /** Forget every entry of the player. */
    public synchronized void clear(UUID player) {
        entries.remove(player);
        claims.remove(player);
    }

    /** Drop expired, unclaimed entries (called from the engine's periodic tick so the map can't grow forever). */
    public synchronized void purgeExpired(long nowMillis) {
        entries.entrySet().removeIf(perPlayer -> {
            Entry<L> claimed = claims.get(perPlayer.getKey());
            perPlayer.getValue().values().removeIf(entry -> !entry.equals(claimed) && entry.isExpired(nowMillis));
            return perPlayer.getValue().isEmpty();
        });
    }

    /** Entries held, over all players and kinds. */
    public synchronized int size() {
        return entries.values().stream().mapToInt(Map::size).sum();
    }
}
