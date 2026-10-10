package net.knightsandkings.knk.core.lootbox;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reel's passing items roll the box's own enchant rolls, so only the winner isn't the odd one out. */
class LootboxDecoyRollsTest {

    private static KnkLootboxOdds.Enchantment sharpness(double hitPercent) {
        return new KnkLootboxOdds.Enchantment(11, "minecraft:sharpness", false, hitPercent, List.of(
                new KnkLootboxOdds.LevelRange(5, 1, 4),
                new KnkLootboxOdds.LevelRange(3, 1, 2),
                new KnkLootboxOdds.LevelRange(1, null, null)));
    }

    @Test
    void aRollHitsBelowItsChance_andMissesAtOrAboveIt() {
        assertEquals(1, LootboxDecoyRolls.roll(List.of(sharpness(60)), 5, () -> 0.59).size());
        assertTrue(LootboxDecoyRolls.roll(List.of(sharpness(60)), 5, () -> 0.60).isEmpty());
    }

    @Test
    void theLevelIsUniformWithinTheGradesRange() {
        // draws: hit (0.0), then the level pick
        assertEquals(1, level(5, 0.0));
        assertEquals(2, level(5, 0.26));
        assertEquals(4, level(5, 0.999));
        assertEquals(1, level(3, 0.0));
        assertEquals(2, level(3, 0.999));
    }

    @Test
    void aGradeWithoutLevels_orWithoutAnEntry_neverGetsTheRoll() {
        assertTrue(LootboxDecoyRolls.roll(List.of(sharpness(100)), 1, () -> 0.0).isEmpty());
        assertTrue(LootboxDecoyRolls.roll(List.of(sharpness(100)), 4, () -> 0.0).isEmpty());
        assertTrue(LootboxDecoyRolls.roll(List.of(sharpness(100)), 0, () -> 0.0).isEmpty());
        assertTrue(LootboxDecoyRolls.roll(null, 5, () -> 0.0).isEmpty());
    }

    @Test
    void theRolledEnchantmentCarriesItsDefinition_inRollOrder() {
        KnkLootboxOdds.Enchantment strength = new KnkLootboxOdds.Enchantment(20, "strength", true, 100, List.of(new KnkLootboxOdds.LevelRange(5, 1, 1)));

        List<KnkLootboxClaimEnchantment> rolled = LootboxDecoyRolls.roll(List.of(sharpness(100), strength), 5, () -> 0.0);

        assertEquals(List.of(new KnkLootboxClaimEnchantment(11, "minecraft:sharpness", false, 1),
                new KnkLootboxClaimEnchantment(20, "strength", true, 1)), rolled);
    }

    @Test
    void overManyRolls_theHitRateFollowsTheChance() {
        Random random = new Random(3);
        int hits = 0;
        for (int i = 0; i < 20_000; i++) {
            hits += LootboxDecoyRolls.roll(List.of(sharpness(40)), 5, random::nextDouble).size();
        }
        assertEquals(0.40, hits / 20_000.0, 0.02);
    }

    private static int level(int stars, double levelDraw) {
        double[] draws = {0.0, levelDraw};
        int[] next = {0};
        return LootboxDecoyRolls.roll(List.of(sharpness(100)), stars, () -> draws[Math.min(next[0]++, 1)]).get(0).level();
    }
}
