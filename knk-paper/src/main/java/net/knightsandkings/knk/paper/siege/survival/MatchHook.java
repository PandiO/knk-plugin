package net.knightsandkings.knk.paper.siege.survival;

import java.util.List;
import java.util.UUID;

/**
 * What the phase 4 features (shops, Mystery Boxes, kits; link S7b) need to hear from a running {@link SurvivalMatch}.
 * Registered per match by the runtime; every method has a default so a feature implements what it needs.
 */
public interface MatchHook {

    /** Every server tick, with the match clock. */
    default void ticked(long nowMillis) { }

    /** A member died and became a ghost (S20: no purchases, rolls or transfers; a pending roll is forfeited, S59). */
    default void playerBecameGhost(UUID playerId) { }

    /** Ghosts respawned at a round transition. */
    default void ghostsReleased(List<UUID> playerIds) { }

    /** A member left the match (leave, quit, kick). */
    default void memberLeft(UUID playerId) { }

    /** The match is over (any reason) or is being cleaned up: remove everything, forfeit pending rolls. Idempotent. */
    default void ended() { }

    /** Lines for {@code /knk siege survival status}. */
    default List<String> statusLines() {
        return List.of();
    }
}
