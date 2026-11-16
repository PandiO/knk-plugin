package net.knightsandkings.knk.core.npc.nav;

/**
 * Who pushes whom (implementation plan 4c.4 item 7, link M7; research report 2026-11-15 § 6.4): the engine's side of
 * NPC bodies with {@code collidable} on. NPCs push and block each other; players push them only when
 * {@code players-push-npcs} is on, and even then not a player that was exempted after repeated pushes. The core asks,
 * the engine answers (on Paper: per-entity collidable exemptions, chosen by the {@code npc-collision-policy}
 * scenario). Main thread.
 */
public interface NpcCollisionPolicy {

    /** Does nothing: bodies collide the way the engine's own settings say. */
    NpcCollisionPolicy NONE = new NpcCollisionPolicy() {
        @Override
        public void enroll(NpcMover mover, boolean playersPush) {
        }

        @Override
        public void release(int moverId) {
        }

        @Override
        public void exempt(int moverId, String playerId, boolean exempt) {
        }
    };

    /**
     * Puts a spawned NPC with collision on under the policy (again after a respawn: the entity is new): it collides
     * with other NPCs; with players only when {@code playersPush}.
     */
    void enroll(NpcMover mover, boolean playersPush);

    /** Takes an NPC out of the policy (unregistered or removed). */
    void release(int moverId);

    /**
     * Turns one player's collision with one NPC off ({@code exempt}) or back to the NPC's rule: repeated pushes by that
     * player (4c.4 item 7). {@code playerId} is the engine's player id ({@link NpcMover.Contact#by()}).
     */
    void exempt(int moverId, String playerId, boolean exempt);
}
