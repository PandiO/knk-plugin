package net.knightsandkings.knk.core.menu;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every online player's {@link MenuSession}, keyed by player UUID.
 * {@link #close(UUID)} must be called on {@code PlayerQuitEvent} - this is
 * the actual fix for reconciliation gap #4 (v1's static per-player maps grew
 * without bound because nothing ever called the equivalent of this on quit).
 * <p>
 * {@code ConcurrentHashMap} because session lookups/creation can happen from
 * the async leg of {@link net.knightsandkings.knk.core.dataaccess.DataAccessExecutor}
 * fetches (menu-open triggers an async template fetch) as well as the main
 * thread's click handling - same reasoning as {@code GateManager}'s caches.
 */
public final class MenuSessionRegistry {

    private final ConcurrentHashMap<UUID, MenuSession> sessions = new ConcurrentHashMap<>();

    /** Returns the existing session for this player, creating one if none exists yet. */
    public MenuSession open(UUID playerId) {
        return sessions.computeIfAbsent(playerId, MenuSession::new);
    }

    public Optional<MenuSession> get(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    /** Removes this player's session entirely. Call on {@code PlayerQuitEvent}. */
    public void close(UUID playerId) {
        sessions.remove(playerId);
    }

    public int activeSessionCount() {
        return sessions.size();
    }
}
