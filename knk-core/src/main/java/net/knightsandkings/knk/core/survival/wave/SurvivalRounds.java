package net.knightsandkings.knk.core.survival.wave;

import net.knightsandkings.knk.core.survival.SurvivalSubstate;

import java.util.List;
import java.util.Optional;

/**
 * The round lifecycle of one survival match (DESIGN §3 steps 3–7, §3c): PREPARING → WAVE_ACTIVE → INTERMISSION →
 * WAVE_ACTIVE … → ENDING, with the per-round {@link RoundSpawnQueue} and the {@link WaveRecord}. The adapter
 * drives it: start a wave with its planned population, report spawns and deaths on the current queue, poll
 * {@link #completeWaveIfDone(long)} and {@link #intermissionOver(long)}, and {@link #end()} when an end condition
 * is met.
 */
public final class SurvivalRounds {
    private final long intermissionMillis;
    private SurvivalSubstate substate = SurvivalSubstate.PREPARING;
    private RoundSpawnQueue current;
    private Intermission intermission;
    private WaveRecord record = WaveRecord.NONE;

    /** @param intermissionMillis the configured round delay (S27, default {@link Intermission#DEFAULT_DURATION_MILLIS}) */
    public SurvivalRounds(long intermissionMillis) {
        if (intermissionMillis < 0) throw new IllegalArgumentException("intermissionMillis must be >= 0");
        this.intermissionMillis = intermissionMillis;
    }

    public static SurvivalRounds withDefaultIntermission() {
        return new SurvivalRounds(Intermission.DEFAULT_DURATION_MILLIS);
    }

    public SurvivalSubstate substate() {
        return substate;
    }

    public WaveRecord record() {
        return record;
    }

    /** The round that is active or was last active (0 before wave 1). */
    public int currentRound() {
        return current == null ? 0 : current.round();
    }

    /** The next round to start. */
    public int nextRound() {
        return currentRound() + 1;
    }

    public Optional<RoundSpawnQueue> currentQueue() {
        return Optional.ofNullable(current);
    }

    public Optional<Intermission> intermission() {
        return substate == SurvivalSubstate.INTERMISSION ? Optional.ofNullable(intermission) : Optional.empty();
    }

    /**
     * Starts the next wave with its planned population (§3 step 3). Allowed from PREPARING (wave 1) and from
     * INTERMISSION; the planned enemies must belong to {@link #nextRound()}.
     */
    public RoundSpawnQueue startWave(List<PlannedEnemy> planned, int maxAlive) {
        if (substate != SurvivalSubstate.PREPARING && substate != SurvivalSubstate.INTERMISSION) {
            throw new IllegalStateException("cannot start a wave in " + substate);
        }
        int round = nextRound();
        current = new RoundSpawnQueue(round, planned, maxAlive);
        intermission = null;
        record = record.started(round);
        substate = SurvivalSubstate.WAVE_ACTIVE;
        return current;
    }

    /**
     * Enters the intermission when the active wave's queue is exhausted and no enemy is alive (§3 step 4–5).
     *
     * @return the intermission that started, or empty when the wave is not complete (or not active)
     */
    public Optional<Intermission> completeWaveIfDone(long nowMillis) {
        if (substate != SurvivalSubstate.WAVE_ACTIVE || current == null || !current.isComplete()) return Optional.empty();
        record = record.completed(current.round());
        intermission = Intermission.startingAt(nowMillis, intermissionMillis);
        substate = SurvivalSubstate.INTERMISSION;
        return Optional.of(intermission);
    }

    /** True while in INTERMISSION and the round delay has elapsed: the adapter starts the next wave. */
    public boolean intermissionOver(long nowMillis) {
        return substate == SurvivalSubstate.INTERMISSION && intermission != null && intermission.isOver(nowMillis);
    }

    /** Deaths count toward a team wipe only here (S41). */
    public boolean deathsCountTowardWipe() {
        return substate == SurvivalSubstate.WAVE_ACTIVE;
    }

    /**
     * Goes to ENDING (§3 step 7): remaining spawns are cancelled; the wave record keeps "reached" without
     * "completed" for the active wave.
     *
     * @return the cancelled planned enemies (never spawned)
     */
    public List<PlannedEnemy> end() {
        substate = SurvivalSubstate.ENDING;
        intermission = null;
        return current == null ? List.of() : current.cancelRemainingSpawns();
    }
}
