package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: interval, min players, caps, chance and a uniform point in the region's bounds. */
class LootboxSpawnPlannerTest {

    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    private final Deque<Double> rolls = new ArrayDeque<>();
    private final LootboxSpawnPlanner planner = new LootboxSpawnPlanner(() -> rolls.isEmpty() ? 0.0 : rolls.poll());

    private static KnkLootboxArea area(int id, boolean enabled, int maxActive, int interval, double chance, int minPlayers) {
        return new KnkLootboxArea(id, "area" + id, "world", "lootbox_area" + id, enabled, maxActive, interval, chance,
                minPlayers, 24, 30, List.of(), List.of(), 0, null);
    }

    private LootboxSpawnPlanner.Skip decide(KnkLootboxArea area, Instant now, int online, int inArea, int global) {
        return planner.decide(area, true, now, online, inArea, global, 15).skip();
    }

    @Test
    void triesAnEligibleArea_thenWaitsItsInterval() {
        KnkLootboxArea area = area(1, true, 3, 600, 100, 3);

        assertEquals(LootboxSpawnPlanner.Skip.NONE, decide(area, T0, 3, 0, 0));
        assertEquals(LootboxSpawnPlanner.Skip.INTERVAL, decide(area, T0.plusSeconds(599), 3, 0, 0));
        assertEquals(LootboxSpawnPlanner.Skip.NONE, decide(area, T0.plusSeconds(600), 3, 0, 0));
    }

    @Test
    void disabledArea_orDisabledConfig_isNeverTried() {
        assertEquals(LootboxSpawnPlanner.Skip.DISABLED, decide(area(1, false, 3, 60, 100, 0), T0, 10, 0, 0));
        assertEquals(LootboxSpawnPlanner.Skip.DISABLED,
                planner.decide(area(2, true, 3, 60, 100, 0), false, T0, 10, 0, 0, 15).skip());
    }

    @Test
    void tooFewPlayers_orFullCaps_skipWithoutUsingTheInterval() {
        KnkLootboxArea area = area(1, true, 3, 600, 100, 3);

        assertEquals(LootboxSpawnPlanner.Skip.TOO_FEW_PLAYERS, decide(area, T0, 2, 0, 0));
        assertEquals(LootboxSpawnPlanner.Skip.AREA_FULL, decide(area, T0, 3, 3, 3));
        assertEquals(LootboxSpawnPlanner.Skip.GLOBAL_FULL, decide(area, T0, 3, 0, 15));
        // None of those counted as a try: the next tick may try straight away.
        assertEquals(LootboxSpawnPlanner.Skip.NONE, decide(area, T0.plusSeconds(1), 3, 0, 0));
    }

    @Test
    void chanceRoll_missCostsTheInterval() {
        KnkLootboxArea area = area(1, true, 3, 600, 25, 0);

        rolls.add(0.25); // >= 25% misses
        assertEquals(LootboxSpawnPlanner.Skip.CHANCE, decide(area, T0, 0, 0, 0));
        assertEquals(LootboxSpawnPlanner.Skip.INTERVAL, decide(area, T0.plusSeconds(10), 0, 0, 0));

        rolls.add(0.2499);
        assertEquals(LootboxSpawnPlanner.Skip.NONE, decide(area, T0.plusSeconds(600), 0, 0, 0));
    }

    @Test
    void zeroChance_neverSpawns() {
        rolls.add(0.0);
        assertEquals(LootboxSpawnPlanner.Skip.CHANCE, decide(area(1, true, 3, 60, 0, 0), T0, 1, 0, 0));
    }

    @Test
    void candidate_isUniformOverTheBoundsAndInsideThem() {
        LootboxSpawnPlanner.Bounds bounds = new LootboxSpawnPlanner.Bounds(-10, -64, 5, 9, 319, 14);

        rolls.add(0.0);
        rolls.add(0.0);
        assertEquals(new LootboxSpawnPlanner.Candidate(-10, 5), planner.candidate(bounds).orElseThrow());
        rolls.add(0.999999);
        rolls.add(0.999999);
        assertEquals(new LootboxSpawnPlanner.Candidate(9, 14), planner.candidate(bounds).orElseThrow());

        LootboxSpawnPlanner seeded = new LootboxSpawnPlanner(new java.util.Random(7)::nextDouble);
        int[] xs = new int[20];
        for (int i = 0; i < 20_000; i++) {
            LootboxSpawnPlanner.Candidate c = seeded.candidate(bounds).orElseThrow();
            assertTrue(bounds.containsXZ(c.x(), c.z()));
            xs[c.x() + 10]++;
        }
        for (int count : xs) {
            assertTrue(Math.abs(count - 1000) < 150, "every column about equally likely: " + count);
        }
    }

    @Test
    void degenerateBounds_giveNoCandidate() {
        assertEquals(Optional.empty(), planner.candidate(new LootboxSpawnPlanner.Bounds(5, 0, 5, 4, 0, 5)));
        assertEquals(Optional.empty(), planner.candidate(null));
    }

    @Test
    void forget_letsAnAreaTryAgainAtOnce() {
        KnkLootboxArea area = area(1, true, 3, 600, 100, 0);
        decide(area, T0, 0, 0, 0);
        planner.forget(1);
        assertEquals(LootboxSpawnPlanner.Skip.NONE, decide(area, T0.plusSeconds(1), 0, 0, 0));
    }

    @Test
    void farEnough_comparesTheDistanceWithTheMinimum() {
        assertTrue(LootboxSpawnPlanner.farEnough(24, 0, 0, 24));
        assertFalse(LootboxSpawnPlanner.farEnough(16, 0, 16, 24)); // ~22.6
        assertTrue(LootboxSpawnPlanner.farEnough(0, 0, 0, 0));
    }
}
