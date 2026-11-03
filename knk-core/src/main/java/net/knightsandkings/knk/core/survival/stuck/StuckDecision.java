package net.knightsandkings.knk.core.survival.stuck;

/** What to do with an enemy after a progress sample (DESIGN §3c rules 1 and 3). */
public sealed interface StuckDecision {

    /** Nothing to do. */
    record None() implements StuckDecision {
        public static final None INSTANCE = new None();
    }

    /**
     * Remove the entity and respawn the same planned enemy (same id, same round stats) at an eligible spawnpoint of
     * an active spawnzone, preferring zones nearest the players. Not a kill, not counted twice.
     *
     * @param respawnCount how often this enemy has now been respawned, this one included
     */
    record Respawn(long enemyId, int respawnCount) implements StuckDecision { }

    /** Kill it as the system (respawn limit reached): dead for completion, no reward, currency or statistics. */
    record SystemKill(long enemyId, StuckEvent.Kind reason) implements StuckDecision { }
}
