package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.EnchantDropPlanner.Drop;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5c: the per-second enchant-book drop roll (DESIGN §9.4). */
class EnchantDropPlannerTest {

    private static final List<String> KEYS = List.of("minecraft:sharpness", "minecraft:protection");

    @Test
    void nothingDropsWithoutChanceKeysObjectivesOrRoom() {
        SplittableRandom random = new SplittableRandom(1);
        assertEquals(Optional.empty(), EnchantDropPlanner.roll(random, 0, 0, 10, List.of(2.5), KEYS, 1, 2));
        assertEquals(Optional.empty(), EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(), KEYS, 1, 2));
        assertEquals(Optional.empty(), EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(2.5), List.of(), 1, 2));
        assertEquals(Optional.empty(), EnchantDropPlanner.roll(random, 1000, 10, 10, List.of(2.5), KEYS, 1, 2),
                "MaxBooksAlive reached");
    }

    @Test
    void chanceIsPerMille() {
        SplittableRandom random = new SplittableRandom(42);
        int drops = 0;
        for (int i = 0; i < 100_000; i++) {
            if (EnchantDropPlanner.roll(random, 30, 0, 10, List.of(2.5), KEYS, 1, 2).isPresent()) drops++;
        }
        assertTrue(drops > 2_700 && drops < 3_300, "about 3% (v2's 30 per mille): " + drops);
    }

    @Test
    void dropsLandUniformlyInsideTheRealRadius() {
        SplittableRandom random = new SplittableRandom(7);
        int inner = 0;
        int n = 20_000;
        Set<Integer> objectives = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Drop d = EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(2.5, 4.0), KEYS, 1, 2).orElseThrow();
            double radius = d.objectiveIndex() == 0 ? 2.5 : 4.0;
            double distance = Math.hypot(d.offsetX(), d.offsetZ());
            assertTrue(distance <= radius + 1e-9);
            if (distance <= radius / 2) inner++;
            objectives.add(d.objectiveIndex());
        }
        assertEquals(Set.of(0, 1), objectives);
        double share = (double) inner / n;
        assertTrue(share > 0.22 && share < 0.28, "uniform by area: a quarter inside half the radius, got " + share
                + " (v2's int cast kept books within 2 blocks)");
    }

    @Test
    void levelsAndKeysComeFromTheConfiguredRanges() {
        SplittableRandom random = new SplittableRandom(3);
        Set<Integer> levels = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Drop d = EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(2.5), KEYS, 1, 2).orElseThrow();
            levels.add(d.level());
            keys.add(d.enchantmentKey());
        }
        assertEquals(Set.of(1, 2), levels);
        assertEquals(Set.copyOf(KEYS), keys);
        assertEquals(1, EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(2.5), KEYS, 0, 0).orElseThrow().level(),
                "levels below 1 are raised to 1");
        assertEquals(1, EnchantDropPlanner.roll(random, 1000, 0, 10, List.of(2.5), KEYS, 3, 1).orElseThrow().level(),
                "min above max: the range collapses to the lower bound");
    }
}
