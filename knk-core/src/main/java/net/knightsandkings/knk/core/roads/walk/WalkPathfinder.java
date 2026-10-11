package net.knightsandkings.knk.core.roads.walk;

/**
 * The one method navigation depends on for a walkable leg (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §3), so another engine (a Pathetic adapter, §11 item 8) can be swapped in and tests can fake it.
 * Implementations are pure and thread-safe: they run on the routing executor, never the main thread.
 */
@FunctionalInterface
public interface WalkPathfinder {

    /** Searches one leg. Never null; never throws for an unwalkable world (that is {@code NO_PATH}). */
    WalkResult find(WalkRequest request);
}
