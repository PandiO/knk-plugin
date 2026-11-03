package net.knightsandkings.knk.core.survival.spawn;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S42, S56: equal chance by default, optional weight, optional unlock round, deterministic draws. */
class MonsterMixTest {

    @Test
    void equalChanceByDefaultAndWeightsShiftTheDraw() {
        MonsterMix mix = MonsterMix.of(MonsterType.of("ZOMBIE"), MonsterType.of("SKELETON").withWeight(3));
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(42);
        for (int i = 0; i < 4000; i++) counts.merge(mix.draw(1, random).orElseThrow().id(), 1, Integer::sum);
        double skeletonShare = counts.get("SKELETON") / 4000.0;
        assertTrue(skeletonShare > 0.7 && skeletonShare < 0.8, "≈ 75 %: " + skeletonShare);
    }

    @Test
    void sameSeedSameSequence() {
        MonsterMix mix = MonsterMix.of(MonsterType.of("ZOMBIE"), MonsterType.of("SPIDER"), MonsterType.of("CREEPER"));
        List<String> a = draws(mix, new Random(7));
        List<String> b = draws(mix, new Random(7));
        assertEquals(a, b);
    }

    private static List<String> draws(MonsterMix mix, Random random) {
        return java.util.stream.IntStream.range(0, 20).mapToObj(i -> mix.draw(1, random).orElseThrow().id()).toList();
    }

    @Test
    void unlockRoundGatesTypes() {
        MonsterMix mix = MonsterMix.of(MonsterType.of("ZOMBIE"), MonsterType.of("CREEPER").unlockedAt(5));
        assertEquals(List.of("ZOMBIE"), mix.eligible(4).stream().map(MonsterType::id).toList());
        assertEquals(2, mix.eligible(5).size());
        Random random = new Random(1);
        for (int i = 0; i < 50; i++) assertEquals("ZOMBIE", mix.draw(4, random).orElseThrow().id());
    }

    @Test
    void readinessFailsWithoutATypeOrWithoutARoundOneType() {
        assertEquals("mix.empty", new MonsterMix(List.of()).readiness().get(0).code());
        MonsterMix late = MonsterMix.of(MonsterType.of("CREEPER").unlockedAt(3));
        assertEquals("mix.none-in-round-1", late.readiness().get(0).code());
        assertEquals(Optional.empty(), late.draw(1, new Random()));
        MonsterMix dup = MonsterMix.of(MonsterType.of("ZOMBIE"), MonsterType.of("ZOMBIE"));
        assertEquals("mix.duplicate", dup.readiness().get(0).code());
        assertTrue(MonsterMix.of(MonsterType.of("ZOMBIE")).readiness().isEmpty());
    }
}
