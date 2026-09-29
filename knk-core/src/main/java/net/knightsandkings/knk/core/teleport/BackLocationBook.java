package net.knightsandkings.knk.core.teleport;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where each player last died, for {@code /back} (docs/specs/teleport/DESIGN.md §3.11, Phase 7;
 * developer decision Q5: death location only, available for a few minutes, single use per death).
 * <p>
 * Lifecycle of one death:
 * <ol>
 *   <li>{@link #recordDeath} stores it (a newer death replaces an older one).</li>
 *   <li>{@link #claim} hands it to one {@code /back} while it is unexpired and not already claimed -
 *       so two {@code /back}s can't both use it. Expiry is only checked here: a {@code /back} started
 *       in time still works when its warmup ends after the deadline.</li>
 *   <li>The claim ends with {@link #consume} (the player arrived - the death is used up) or
 *       {@link #release} (the warmup was cancelled, a guard refused it, the spot wasn't safe...) -
 *       then it can be claimed again until it expires.</li>
 * </ol>
 * Claims and releases name the {@link Entry} they're about, so a stale one never touches a newer
 * death. Time is passed in, so the whole thing is unit-testable. In memory only - a restart forgets
 * every death, which is fine for a 5-minute window.
 *
 * @param <L> the stored location (knk-paper keeps world + coordinates, not a Bukkit Location)
 */
public final class BackLocationBook<L> {

    /** One recorded death; {@code id} tells deaths of the same player apart. */
    public record Entry<L>(long id, UUID player, L location, long diedAtMillis, long expiresAtMillis) {

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        /** Whole seconds left to start a {@code /back}, rounded up; 0 once expired. */
        public int secondsLeft(long nowMillis) {
            long left = expiresAtMillis - nowMillis;
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }
    }

    private record Slot<L>(Entry<L> entry, boolean claimed) {
    }

    private final AtomicLong ids = new AtomicLong();
    private final Map<UUID, Slot<L>> slots = new ConcurrentHashMap<>();
    private volatile int expireSeconds;

    public BackLocationBook(int expireSeconds) {
        setExpireSeconds(expireSeconds);
    }

    /** Applies to deaths recorded from now on (config reload). */
    public void setExpireSeconds(int expireSeconds) {
        this.expireSeconds = Math.max(1, expireSeconds);
    }

    /** Store {@code player}'s death at {@code location}, replacing (and un-claiming) any earlier one. */
    public Entry<L> recordDeath(UUID player, L location, long nowMillis) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Entry<L> entry = new Entry<>(ids.incrementAndGet(), player, location, nowMillis,
            nowMillis + expireSeconds * 1000L);
        slots.put(player, new Slot<>(entry, false));
        return entry;
    }

    /** The player's last death while it can still be used (unexpired, not claimed). */
    public Optional<Entry<L>> available(UUID player, long nowMillis) {
        Slot<L> slot = slots.get(player);
        if (slot == null || slot.claimed() || slot.entry().isExpired(nowMillis)) {
            return Optional.empty();
        }
        return Optional.of(slot.entry());
    }

    /** Whether a {@code /back} of {@code player} is running right now. */
    public boolean isClaimed(UUID player) {
        Slot<L> slot = slots.get(player);
        return slot != null && slot.claimed();
    }

    /** Take the player's last death for one {@code /back}; empty when there's none to use. */
    public Optional<Entry<L>> claim(UUID player, long nowMillis) {
        Optional<Entry<L>> available = available(player, nowMillis);
        if (available.isEmpty()) {
            return Optional.empty();
        }
        Entry<L> entry = available.get();
        boolean claimed = slots.replace(player, new Slot<>(entry, false), new Slot<>(entry, true));
        return claimed ? available : Optional.empty();
    }

    /** The claimed {@code /back} didn't happen: {@code entry} may be claimed again until it expires. */
    public void release(Entry<L> entry) {
        slots.replace(entry.player(), new Slot<>(entry, true), new Slot<>(entry, false));
    }

    /** The player arrived: {@code entry} is used up (single use per death). */
    public void consume(Entry<L> entry) {
        slots.remove(entry.player(), new Slot<>(entry, true));
        slots.remove(entry.player(), new Slot<>(entry, false));
    }

    /** Forget the player's death entirely. */
    public void clear(UUID player) {
        slots.remove(player);
    }

    /** Drop expired, unclaimed deaths (called from the engine's periodic tick so the map can't grow forever). */
    public void purgeExpired(long nowMillis) {
        slots.values().removeIf(slot -> !slot.claimed() && slot.entry().isExpired(nowMillis));
    }

    public int size() {
        return slots.size();
    }
}
