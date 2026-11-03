package net.knightsandkings.knk.core.survival.stuck;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Per-enemy stuck handling for one round (DESIGN §3c, S43). The adapter calls {@link #track} on every spawn
 * (first spawn and respawn), {@link #report} with a progress sample per check, {@link #lastEnemies} and
 * {@link #backstop} once per tick or so, and {@link #forget} on death. Every respawn, glow and system kill goes to
 * the {@link StuckTelemetrySink}.
 * <ol>
 *   <li>Stuck: no progress for N → {@link StuckDecision.Respawn} (same planned enemy, nearest zones preferred).</li>
 *   <li>Last enemies: queue exhausted and at most K alive → glow and direct pursuit, flagged once per enemy.</li>
 *   <li>Respawn limit: stuck again after R respawns → {@link StuckDecision.SystemKill}.</li>
 *   <li>Round backstop: T after the last planned spawn → system kill of everything still alive (off when T is empty).</li>
 * </ol>
 * Time comes from the samples and the callers (milliseconds), nothing is read from a clock.
 */
public final class StuckMonitor {
    private final StuckSettings settings;
    private final StuckTelemetrySink telemetry;
    private final Map<Long, Tracked> tracked = new LinkedHashMap<>();
    private final Set<Long> glowing = new LinkedHashSet<>();
    private boolean backstopFired;

    private static final class Tracked {
        final PlannedEnemy enemy;
        long lastProgressMillis;
        Optional<Double> bestDistance = Optional.empty();
        Optional<String> target = Optional.empty();
        Optional<SurvivalPoint> lastPosition = Optional.empty();
        int respawnCount;

        Tracked(PlannedEnemy enemy, long spawnedAtMillis) {
            this.enemy = enemy;
            this.lastProgressMillis = spawnedAtMillis;
        }
    }

    public StuckMonitor(StuckSettings settings, StuckTelemetrySink telemetry) {
        this.settings = settings;
        this.telemetry = telemetry == null ? StuckTelemetrySink.none() : telemetry;
    }

    public StuckSettings settings() {
        return settings;
    }

    /**
     * Starts (first spawn) or restarts (respawn) the progress clock of an enemy. A respawn keeps the respawn count;
     * the distance baseline is reset because the enemy stands somewhere else now.
     */
    public void track(PlannedEnemy enemy, long spawnedAtMillis) {
        Tracked existing = tracked.get(enemy.id());
        if (existing == null) {
            tracked.put(enemy.id(), new Tracked(enemy, spawnedAtMillis));
        } else {
            existing.lastProgressMillis = spawnedAtMillis;
            existing.bestDistance = Optional.empty();
        }
    }

    /** The enemy died or was removed for good. */
    public void forget(long enemyId) {
        tracked.remove(enemyId);
        glowing.remove(enemyId);
    }

    public boolean isTracked(long enemyId) {
        return tracked.containsKey(enemyId);
    }

    public int respawnCount(long enemyId) {
        Tracked t = tracked.get(enemyId);
        return t == null ? 0 : t.respawnCount;
    }

    /** Glow and direct pursuit are the same flag (rule 2): the last enemies target the nearest player directly. */
    public boolean isGlowing(long enemyId) {
        return glowing.contains(enemyId);
    }

    public boolean pursuesDirectly(long enemyId) {
        return isGlowing(enemyId);
    }

    /**
     * Records a progress sample and decides (rules 1 and 3). Progress = damage dealt or taken, or a meaningful
     * reduction of the distance to the target (vs. the best distance since the baseline was set). A target change
     * only resets the distance baseline; navigation blocked never counts as progress. Unknown enemies give
     * {@link StuckDecision.None}.
     */
    public StuckDecision report(long enemyId, ProgressSample sample) {
        Tracked t = tracked.get(enemyId);
        if (t == null) return StuckDecision.None.INSTANCE;
        t.lastPosition = Optional.of(sample.position());
        boolean progress = false;
        if (sample.dealtOrTookDamage()) progress = true;
        if (!sample.target().equals(t.target)) {
            // A new target is not progress by itself (an enemy flipping targets while stuck stays stuck); it only
            // restarts the distance baseline, since the old distance means nothing for the new target.
            t.target = sample.target();
            t.bestDistance = Optional.empty();
        }
        if (!sample.navigationBlocked() && sample.distanceToTarget().isPresent()) {
            double distance = sample.distanceToTarget().get();
            if (t.bestDistance.isEmpty()) {
                t.bestDistance = Optional.of(distance);
            } else if (t.bestDistance.get() - distance >= settings.meaningfulDistance()) {
                t.bestDistance = Optional.of(distance);
                progress = true;
            }
        }
        if (progress) {
            t.lastProgressMillis = sample.atMillis();
            return StuckDecision.None.INSTANCE;
        }
        if (sample.atMillis() - t.lastProgressMillis < settings.stuckAfterMillis()) {
            return StuckDecision.None.INSTANCE;
        }
        if (t.respawnCount >= settings.respawnLimit()) {
            telemetry.record(event(StuckEvent.Kind.RESPAWN_LIMIT_KILL, t, sample.atMillis()));
            forget(enemyId);
            return new StuckDecision.SystemKill(enemyId, StuckEvent.Kind.RESPAWN_LIMIT_KILL);
        }
        t.respawnCount++;
        t.lastProgressMillis = sample.atMillis();
        t.bestDistance = Optional.empty();
        telemetry.record(event(StuckEvent.Kind.STUCK_RESPAWN, t, sample.atMillis()));
        return new StuckDecision.Respawn(enemyId, t.respawnCount);
    }

    /**
     * Rule 2: with the queue exhausted and at most K enemies alive, every alive enemy glows and pursues directly.
     *
     * @return the enemies newly flagged by this call (already glowing ones are not repeated)
     */
    public List<Long> lastEnemies(boolean queueExhausted, Collection<Long> aliveIds, long nowMillis) {
        if (!queueExhausted || aliveIds.size() > settings.lastEnemiesCount()) return List.of();
        List<Long> newlyFlagged = new ArrayList<>();
        for (long id : aliveIds) {
            if (glowing.add(id)) {
                newlyFlagged.add(id);
                Tracked t = tracked.get(id);
                if (t != null) telemetry.record(event(StuckEvent.Kind.GLOW, t, nowMillis));
            }
        }
        return List.copyOf(newlyFlagged);
    }

    /**
     * Rule 4: T after the last planned spawn, everything still alive is system-killed. Fires once per round.
     *
     * @param lastPlannedSpawnMillis from {@code RoundSpawnQueue.lastPlannedSpawnMillis()} (empty = queue not exhausted)
     * @return the ids to kill as the system; empty when the backstop is off, not due, already fired or nothing is alive
     */
    public List<Long> backstop(OptionalLong lastPlannedSpawnMillis, Collection<Long> aliveIds, long nowMillis) {
        if (backstopFired || !settings.backstopEnabled() || lastPlannedSpawnMillis.isEmpty() || aliveIds.isEmpty()) {
            return List.of();
        }
        if (nowMillis - lastPlannedSpawnMillis.getAsLong() < settings.roundBackstopMillis().getAsLong()) return List.of();
        backstopFired = true;
        List<Long> kills = new ArrayList<>();
        for (long id : aliveIds) {
            kills.add(id);
            Tracked t = tracked.get(id);
            if (t != null) telemetry.record(event(StuckEvent.Kind.BACKSTOP_KILL, t, nowMillis));
            forget(id);
        }
        return List.copyOf(kills);
    }

    public boolean backstopFired() {
        return backstopFired;
    }

    private static StuckEvent event(StuckEvent.Kind kind, Tracked t, long atMillis) {
        return new StuckEvent(kind, t.enemy.id(), t.enemy.round(), atMillis, t.lastPosition, t.target, t.respawnCount);
    }
}
