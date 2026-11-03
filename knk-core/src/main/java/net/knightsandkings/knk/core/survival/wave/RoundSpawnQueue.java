package net.knightsandkings.knk.core.survival.wave;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One round's planned spawn queue, alive set and kill accounting (DESIGN §3 steps 3–4, §3c, §7 "max enemies alive
 * at once"). The adapter asks {@link #nextSpawn()} whenever it may spawn; the queue hands out the next planned enemy
 * only while fewer than {@code maxAlive} enemies are alive, so a large late-wave population spawns as others die.
 * <p>
 * The round is complete only when the queue is exhausted AND no enemy is alive ({@link #isComplete()}); a
 * temporarily empty combat area or a spawn delay cannot finish it. Deaths are recorded with their {@link KillCause}:
 * every cause removes the enemy from the alive set, only {@link KillCause#PLAYER} counts as an earned kill.
 */
public final class RoundSpawnQueue {
    private final int round;
    private final int maxAlive;
    private final int planned;
    private final Deque<PlannedEnemy> queue = new ArrayDeque<>();
    private final Map<Long, PlannedEnemy> alive = new LinkedHashMap<>();
    private final Set<Long> dead = new LinkedHashSet<>();
    private int earnedKills;
    private int systemKills;
    private int otherDeaths;
    private long lastPlannedSpawnMillis = -1;
    private int spawnedCount;

    /**
     * @param round    the round number (1-based)
     * @param planned  the round's population in spawn order
     * @param maxAlive maximum enemies alive at once; 0 or less = unlimited
     */
    public RoundSpawnQueue(int round, List<PlannedEnemy> planned, int maxAlive) {
        if (round < 1) throw new IllegalArgumentException("round must be >= 1: " + round);
        Set<Long> ids = new LinkedHashSet<>();
        for (PlannedEnemy enemy : planned) {
            if (enemy.round() != round) {
                throw new IllegalArgumentException("enemy " + enemy.id() + " belongs to round " + enemy.round() + ", not " + round);
            }
            if (!ids.add(enemy.id())) throw new IllegalArgumentException("duplicate enemy id " + enemy.id());
        }
        this.round = round;
        this.maxAlive = maxAlive <= 0 ? Integer.MAX_VALUE : maxAlive;
        this.planned = planned.size();
        this.queue.addAll(planned);
    }

    public int round() {
        return round;
    }

    /** How many enemies the round planned in total. */
    public int plannedCount() {
        return planned;
    }

    /** Enemies still waiting in the queue. */
    public int queuedCount() {
        return queue.size();
    }

    public int aliveCount() {
        return alive.size();
    }

    public Set<Long> aliveIds() {
        return Set.copyOf(alive.keySet());
    }

    public Collection<PlannedEnemy> aliveEnemies() {
        return List.copyOf(alive.values());
    }

    public boolean isAlive(long enemyId) {
        return alive.containsKey(enemyId);
    }

    /** Kills by a player's killing blow (reward-eligible). */
    public int earnedKills() {
        return earnedKills;
    }

    /** Deaths caused by stuck handling (§3c); counted for completion only. */
    public int systemKills() {
        return systemKills;
    }

    /** Environment and NPC deaths; counted for completion only. */
    public int otherDeaths() {
        return otherDeaths;
    }

    /** Enemies that have spawned so far (first spawns, not stuck respawns). */
    public int spawnedCount() {
        return spawnedCount;
    }

    /** True once every planned enemy has spawned at least once (the §3c backstop's reference point). */
    public boolean isQueueExhausted() {
        return queue.isEmpty();
    }

    /** When the last planned enemy spawned; empty until the queue is exhausted. */
    public Optional<Long> lastPlannedSpawnMillis() {
        return isQueueExhausted() && lastPlannedSpawnMillis >= 0 ? Optional.of(lastPlannedSpawnMillis) : Optional.empty();
    }

    /** Whether the pacing limit allows another spawn right now. */
    public boolean canSpawn() {
        return !queue.isEmpty() && alive.size() < maxAlive;
    }

    /**
     * Hands out the next planned enemy if the queue is not exhausted and fewer than {@code maxAlive} are alive;
     * the enemy is alive from this moment. Empty when nothing may spawn now (the caller tries again later).
     */
    public Optional<PlannedEnemy> nextSpawn(long nowMillis) {
        if (!canSpawn()) return Optional.empty();
        PlannedEnemy next = queue.pollFirst();
        alive.put(next.id(), next);
        spawnedCount++;
        if (queue.isEmpty()) lastPlannedSpawnMillis = nowMillis;
        return Optional.of(next);
    }

    /**
     * Records an enemy's death. Unknown or already dead ids are ignored (a stuck respawn removal is not a death;
     * the adapter must not report it here).
     *
     * @return true when the enemy was alive and is now dead
     */
    public boolean recordDeath(long enemyId, KillCause cause) {
        if (alive.remove(enemyId) == null) return false;
        dead.add(enemyId);
        switch (cause) {
            case PLAYER -> earnedKills++;
            case SYSTEM -> systemKills++;
            case ENVIRONMENT, NPC -> otherDeaths++;
        }
        return true;
    }

    /** Round completion: queue exhausted and no enemy alive (§3 step 4). */
    public boolean isComplete() {
        return queue.isEmpty() && alive.isEmpty();
    }

    /** Enemies not yet dead: alive plus queued (what the HUD shows as "remaining + queued", §11). */
    public int remainingCount() {
        return queue.size() + alive.size();
    }

    /** Cancels the remaining spawns (match end, §3 step 7). Alive enemies stay until the adapter removes them. */
    public List<PlannedEnemy> cancelRemainingSpawns() {
        List<PlannedEnemy> cancelled = List.copyOf(queue);
        queue.clear();
        return cancelled;
    }
}
