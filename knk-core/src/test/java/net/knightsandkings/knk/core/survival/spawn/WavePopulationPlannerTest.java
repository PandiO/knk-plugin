package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.scaling.PerPlayerScaling;
import net.knightsandkings.knk.core.survival.scaling.ScalingCurve;
import net.knightsandkings.knk.core.survival.scaling.StatWeights;
import net.knightsandkings.knk.core.survival.scaling.WaveScaling;
import net.knightsandkings.knk.core.survival.scaling.WaveScalingSettings;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §3 step 3, §7: the planned population per round with normal and special composition. */
class WavePopulationPlannerTest {
    private final WaveScaling scaling = new WaveScaling(new WaveScalingSettings(ScalingCurve.linear(4, 2),
        ScalingCurve.linear(1, 0.1), StatWeights.uniform(), new PerPlayerScaling(0.5, false), 0, 5, 0));
    private final MonsterMix normal = MonsterMix.of(MonsterType.of("ZOMBIE"), MonsterType.of("SKELETON"));
    private final SpecialWaveProfile wolves = SpecialWaveProfile.replacing("wolves", MonsterMix.of(MonsterType.of("WOLF")));
    private final SpecialWaveProfile boss = new SpecialWaveProfile("boss", MonsterMix.of(MonsterType.of("RAVAGER")),
        OptionalInt.of(1), 1, true);
    private final SpecialWaveSchedule schedule = new SpecialWaveSchedule(List.of(
        SpecialWaveEntry.Recurring.every(5, "wolves", 1), new SpecialWaveEntry.Explicit(3, "boss")));
    private final WavePopulationPlanner planner = new WavePopulationPlanner(scaling, normal, schedule,
        Map.of("wolves", wolves, "boss", boss));

    @Test
    void normalRoundDrawsTheScaledCountFromTheNormalMix() {
        EnemyIdSequence ids = new EnemyIdSequence();
        List<PlannedEnemy> round2 = planner.plan(2, 1, ids, new Random(1));
        assertEquals(6, round2.size());
        assertTrue(round2.stream().allMatch(e -> e.round() == 2 && !e.special()));
        assertTrue(round2.stream().allMatch(e -> Set.of("ZOMBIE", "SKELETON").contains(e.monsterType())));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), round2.stream().map(PlannedEnemy::id).toList());
        assertEquals(7, ids.peek());
        assertEquals(9, planner.plan(2, 2, new EnemyIdSequence(), new Random(1)).size(), "per-player: 6 × 1.5");
    }

    @Test
    void replacingSpecialProfileStandsInForTheNormalComposition() {
        List<PlannedEnemy> round5 = planner.plan(5, 1, new EnemyIdSequence(), new Random(1));
        assertEquals(12, round5.size(), "normal count kept (countMultiplier 1)");
        assertTrue(round5.stream().allMatch(e -> e.monsterType().equals("WOLF") && e.special()));
    }

    @Test
    void additiveSpecialProfileIsAppendedToTheNormalWave() {
        List<PlannedEnemy> round3 = planner.plan(3, 1, new EnemyIdSequence(), new Random(1));
        assertEquals(8 + 1, round3.size());
        assertEquals(8, round3.stream().filter(e -> !e.special()).count());
        PlannedEnemy last = round3.get(8);
        assertTrue(last.special());
        assertEquals("RAVAGER", last.monsterType());
    }

    @Test
    void specialProfileWithoutMixUsesTheNormalMixAndItsCountRules() {
        SpecialWaveProfile doubled = new SpecialWaveProfile("double", null, OptionalInt.empty(), 2.5, false);
        WavePopulationPlanner p = new WavePopulationPlanner(scaling, normal,
            new SpecialWaveSchedule(List.of(new SpecialWaveEntry.Explicit(1, "double"))), Map.of("double", doubled));
        List<PlannedEnemy> round1 = p.plan(1, 1, new EnemyIdSequence(), new Random(3));
        assertEquals(10, round1.size(), "floor(4 × 2.5)");
        assertTrue(round1.stream().allMatch(e -> Set.of("ZOMBIE", "SKELETON").contains(e.monsterType())));
        assertEquals(1, doubled.count(0), "never below 1");
    }

    @Test
    void deterministicWithTheSameSeed() {
        assertEquals(planner.plan(4, 2, new EnemyIdSequence(), new Random(99)),
            planner.plan(4, 2, new EnemyIdSequence(), new Random(99)));
    }

    @Test
    void readinessCoversMixScheduleAndProfiles() {
        assertTrue(planner.readiness().isEmpty());
        WavePopulationPlanner bad = new WavePopulationPlanner(scaling, new MonsterMix(List.of()),
            new SpecialWaveSchedule(List.of(new SpecialWaveEntry.Explicit(2, "nope"))),
            Map.of("late", SpecialWaveProfile.replacing("late", MonsterMix.of(MonsterType.of("WOLF").unlockedAt(9)))));
        List<String> codes = bad.readiness().stream().map(i -> i.code()).toList();
        assertTrue(codes.contains("mix.empty"));
        assertTrue(codes.contains("special.unknown-profile"));
        assertTrue(codes.contains("special.mix.none-in-round-1"));
    }
}
