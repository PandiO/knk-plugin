package net.knightsandkings.knk.core.npc.nav;

import java.util.List;
import java.util.Optional;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

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
     * How far the body has fallen since it last stood on the ground, in blocks, as the engine counts it (it resets on
     * landing and on every stair step), so a fall can be told from walking down stairs (implementation plan 4b.2
     * item 12). NaN when the engine cannot tell.
     */
    default double fallDistance() {
        return Double.NaN;
    }

    /**
     * The body's vertical speed in blocks per tick, negative while it falls (PandiO/knk-workspace#592: a Citizens
     * player NPC's {@link #fallDistance()} stays near 0, so a fall is told from a stair step by its speed). NaN when
     * the engine cannot tell.
     */
    default double verticalSpeed() {
        return Double.NaN;
    }

    /**
     * How many of the engine's jumps the mover held back since it was spawned because the body walks on instead (link
     * J1, PandiO/knk-workspace#695): the hop on a stair's lower half, and a jump toward a waypoint a block up when the
     * next cell toward it is at most a step up. 0 for an engine that does not hold them back.
     */
    default long stepHopsHeld() {
        return 0;
    }

    /**
     * Walk the given points in order without the engine's own path search (NAV-15 trail mode). The engine's block
     * rules are not consulted, so K&K's recheck keeps access rules binding (NAV-02).
     */
    void follow(List<NavPoint> waypoints);

    /**
     * As {@link #follow(List)}, moving on to the next waypoint only within {@code margin} blocks of the current one
     * (implementation plan 4b.2 item 2: turns are walked through the corner point, not cut). The margin applies to
     * this list only; local legs keep the engine's own. An engine without such a margin ignores it.
     */
    default void follow(List<NavPoint> waypoints, double margin) {
        follow(waypoints);
    }

    /** Walk to {@code target} with the engine's own local path search and its own block rules only (test NPCs). */
    void walkTo(NavPoint target);

    /**
     * Walk to {@code target} with the engine's local path search under K&K's rules (off-road legs, stuck recovery):
     * {@code leg}'s cell access is asked before the engine's own block rules, and cells outside the leg's box are
     * blocked. A block rule the engine adds on its own is removed and reported
     * ({@link MoverListener#engineRuleRemoved}).
     */
    void walkTo(NavPoint target, LocalLeg leg);

    /**
     * The rest of the path the engine's own search found for the running {@link #walkTo(NavPoint, LocalLeg)} order,
     * as feet positions from the NPC onwards, so K&K can recheck it against the current rules (NAV-02). Empty when
     * the engine has no path yet, for waypoint lists and when the engine cannot tell.
     */
    default List<NavPoint> pathAhead() {
        return List.of();
    }

    /**
     * Walking speed as a factor of the NPC's normal speed, for the orders given from now on and the one running
     * (a group's follower catching up with its slot, NAV-05). An engine that cannot change speed ignores it.
     */
    default void setSpeedFactor(double factor) {
    }

    /**
     * Puts the body at {@code feet} at once (a pushed NPC's teleport home, the last step of its return, 4c.4 item 7);
     * false when the engine cannot. The running order, if any, is stopped.
     */
    default boolean teleport(NavPoint feet) {
        return false;
    }

    /** Stop moving; fires no {@link MoverListener#stuck}. */
    void stop();

    /** Moving under a {@link #follow} or {@link #walkTo} order. */
    boolean isMoving();

    /** Remove the NPC for good. */
    void remove();

    void setListener(MoverListener listener);

    /** Why the engine gave up moving. */
    enum StuckKind {
        /** The body stood in one block for the engine's stationary ticks. */
        STATIONARY,
        /** The engine's path search ended without a path (an unreachable target), before the body moved. */
        NO_PATH
    }

    /** What touched the body (4c.4 item 7): a player, another NPC, an attack, or something else. */
    enum ContactKind {
        /** A player's body pushed it. */
        PLAYER,
        /** Another NPC's body pushed it. */
        NPC,
        /** An attack knocked it back (on Paper {@code EntityPushedByEntityAttackEvent}); it is not cancelled. */
        ATTACK,
        /** Any other entity. */
        OTHER
    }

    /**
     * A contact the engine saw: what pushed the body and who ({@code by}: the engine's id of the other entity, for a
     * player the id {@link NpcCollisionPolicy#exempt} takes; null when unknown; {@code player}: whether {@code by} is a
     * player, which an {@link ContactKind#ATTACK} needs to say).
     */
    record Contact(ContactKind kind, String by, boolean player) {
        public Contact {
            java.util.Objects.requireNonNull(kind, "kind");
            player = player || kind == ContactKind.PLAYER;
        }

        public Contact(ContactKind kind, String by) {
            this(kind, by, kind == ContactKind.PLAYER);
        }
    }

    /** What the engine reports about a mover. Main thread. */
    interface MoverListener {

        /** The last point of a {@link #follow} or the target of a {@link #walkTo} was reached. */
        default void arrived(NpcMover mover) {
        }

        /** The engine gave up moving (its stuck detection). Waypoint lists may not report this: see NAV-17. */
        default void stuck(NpcMover mover) {
        }

        /**
         * As {@link #stuck(NpcMover)}, saying what made the engine give up (implementation plan 4b.2 item 7.3). The
         * default hands it to {@link #stuck(NpcMover)}, so a listener that does not care keeps working.
         */
        default void stuck(NpcMover mover, StuckKind kind) {
            stuck(mover);
        }

        /** The entity left the world: {@code chunkUnload} when its chunk unloaded (NAV-11). */
        default void despawned(NpcMover mover, boolean chunkUnload) {
        }

        /** The entity is back (its chunk loaded again). */
        default void respawned(NpcMover mover) {
        }

        /**
         * Something touched or knocked the body (4c.4 item 7): a hint for push detection, which still judges the
         * displacement itself. Engines may report one contact every tick while bodies overlap.
         */
        default void contact(NpcMover mover, Contact contact) {
        }

        /** The engine had added block rules of its own to a local search; they were removed. {@code names} for logs. */
        default void engineRuleRemoved(NpcMover mover, String names) {
        }
    }
}
