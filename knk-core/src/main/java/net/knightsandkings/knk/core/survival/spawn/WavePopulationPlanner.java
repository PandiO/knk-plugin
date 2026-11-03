package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.scaling.WaveScaling;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Generates a round's finite planned population (DESIGN §3 step 3, §7): the count from the scaling, the types from
 * the monster mix (or the special profile's mix), honouring the special-wave schedule. A replacing profile stands
 * in for the normal composition; an additive profile is appended to it. Deterministic given the random source.
 */
public final class WavePopulationPlanner {
    private final WaveScaling scaling;
    private final MonsterMix normalMix;
    private final SpecialWaveSchedule schedule;
    private final Map<String, SpecialWaveProfile> profiles;

    public WavePopulationPlanner(WaveScaling scaling, MonsterMix normalMix, SpecialWaveSchedule schedule,
                                 Map<String, SpecialWaveProfile> profiles) {
        this.scaling = scaling;
        this.normalMix = normalMix;
        this.schedule = schedule == null ? SpecialWaveSchedule.none() : schedule;
        this.profiles = Map.copyOf(profiles);
    }

    /** The special profile the schedule selects for {@code round}, if its id is configured. */
    public Optional<SpecialWaveProfile> specialProfileFor(int round) {
        return schedule.profileIdFor(round).map(profiles::get);
    }

    /**
     * Plans round {@code round}.
     *
     * @param playersAtRoundStart every player in the match at round start, ghosts included
     * @param ids                 the match's enemy id sequence
     */
    public List<PlannedEnemy> plan(int round, int playersAtRoundStart, EnemyIdSequence ids, RandomGenerator random) {
        int normalCount = scaling.enemyCount(round, playersAtRoundStart);
        Optional<SpecialWaveProfile> special = specialProfileFor(round);
        List<PlannedEnemy> planned = new ArrayList<>();
        if (special.isEmpty() || special.get().additive()) {
            draw(planned, normalMix, normalCount, round, false, ids, random);
        }
        if (special.isPresent()) {
            SpecialWaveProfile profile = special.get();
            MonsterMix mix = profile.mix().isEmpty() ? normalMix : profile.mix();
            draw(planned, mix, profile.count(normalCount), round, true, ids, random);
        }
        return List.copyOf(planned);
    }

    private static void draw(List<PlannedEnemy> into, MonsterMix mix, int count, int round, boolean special,
                             EnemyIdSequence ids, RandomGenerator random) {
        for (int i = 0; i < count; i++) {
            Optional<MonsterType> type = mix.draw(round, random);
            if (type.isEmpty()) {
                throw new IllegalStateException("no monster type is unlocked in round " + round + " (readiness should have failed)");
            }
            into.add(new PlannedEnemy(ids.next(), round, type.get().id(), special));
        }
    }

    /** Readiness of the mix, the schedule and every special profile's mix. */
    public List<ReadinessIssue> readiness() {
        List<ReadinessIssue> issues = new ArrayList<>(normalMix.readiness());
        issues.addAll(scaling.settings().readiness());
        issues.addAll(schedule.readiness(profiles.keySet()));
        for (SpecialWaveProfile profile : profiles.values()) {
            if (profile.mix().isEmpty()) continue;
            for (ReadinessIssue issue : profile.mix().readiness()) {
                issues.add(new ReadinessIssue(issue.severity(), "special." + issue.code(),
                    "Special profile " + profile.id() + ": " + issue.message()));
            }
        }
        return List.copyOf(issues);
    }
}
