package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Optional;

/**
 * The engine's side of one NPC that moves (DESIGN §2 platform-adapter boundary): K&K decides where to go, the mover
 * moves the body. On Paper the adapter is Citizens ({@code paper.npc.citizens.CitizensNpcMover}). Main thread unless
 * an implementation says otherwise.
 */
public interface NpcMover {

    /** Stable for the mover's lifetime. */
    int id();

    String name();

    /** False while the NPC's chunk is unloaded (the entity is gone; the mover keeps its identity). */
    boolean isSpawned();

    /** Feet position, empty while not spawned. */
    Optional<NavPoint> position();

    boolean onGround();

    /**
     * Walk the given points in order without the engine's own path search (NAV-15 trail mode). The engine's block
     * rules are not consulted, so K&K's recheck keeps access rules binding (NAV-02).
     */
    void follow(List<NavPoint> waypoints);

    /** Walk to {@code target} with the engine's own local path search (off-road legs, stuck recovery). */
    void walkTo(NavPoint target);

    /** Stop moving; fires no {@link MoverListener#stuck}. */
    void stop();

    /** Moving under a {@link #follow} or {@link #walkTo} order. */
    boolean isMoving();

    /** Remove the NPC for good. */
    void remove();

    void setListener(MoverListener listener);

    /** What the engine reports about a mover. Main thread. */
    interface MoverListener {

        /** The last point of a {@link #follow} or the target of a {@link #walkTo} was reached. */
        default void arrived(NpcMover mover) {
        }

        /** The engine gave up moving (its stuck detection). Waypoint lists may not report this: see NAV-17. */
        default void stuck(NpcMover mover) {
        }

        /** The entity left the world: {@code chunkUnload} when its chunk unloaded (NAV-11). */
        default void despawned(NpcMover mover, boolean chunkUnload) {
        }

        /** The entity is back (its chunk loaded again). */
        default void respawned(NpcMover mover) {
        }
    }
}
