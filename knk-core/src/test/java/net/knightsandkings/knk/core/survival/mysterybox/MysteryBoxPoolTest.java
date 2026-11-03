package net.knightsandkings.knk.core.survival.mysterybox;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxEntry.BookEntry;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxEntry.ItemEntry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S24, S59, §8a: percentage pools in basis points, exactly 100 %, deterministic picks, independent enchantments. */
class MysteryBoxPoolTest {

    @Test
    void basisPointsAcceptHundredthsOfAPercentOnly() {
        assertEquals(10_000, BasisPoints.fromPercent(100));
        assertEquals(1, BasisPoints.fromPercent(0.01));
        assertEquals(1250, BasisPoints.fromPercent(12.5));
        assertEquals(12.5, BasisPoints.toPercent(1250), 0.0);
        assertThrows(IllegalArgumentException.class, () -> BasisPoints.fromPercent(33.333));
        assertThrows(IllegalArgumentException.class, () -> BasisPoints.fromPercent(100.01));
        assertThrows(IllegalArgumentException.class, () -> BasisPoints.fromPercent(-1));
    }

    @Test
    void validationRequiresExactlyOneHundredPercentAndNonEmpty() {
        MysteryBoxPool empty = new MysteryBoxPool(MysteryBoxVariant.ITEMS, List.of());
        assertEquals("box.empty", empty.validate().get(0).code());

        MysteryBoxPool short99 = new MysteryBoxPool(MysteryBoxVariant.ITEMS, List.of(ItemEntry.of("sword", 60), ItemEntry.of("bow", 39.99)));
        List<String> codes = short99.validate().stream().map(ReadinessIssue::code).toList();
        assertEquals(List.of("box.total"), codes);
        assertTrue(short99.validate().get(0).message().contains("99.99"));

        MysteryBoxPool exact = new MysteryBoxPool(MysteryBoxVariant.ITEMS, List.of(ItemEntry.of("sword", 60), ItemEntry.of("bow", 40)));
        assertTrue(exact.isValid());
        assertThrows(IllegalStateException.class, () -> short99.pick(new Random()));
    }

    @Test
    void validationChecksVariantReferencesAndDuplicateEnchantments() {
        ItemEntry item = new ItemEntry("sword", 5000, List.of(new EnchantmentChance("sharpness", 3, 5000),
            new EnchantmentChance("sharpness", 5, 1000)));
        MysteryBoxPool mixed = new MysteryBoxPool(MysteryBoxVariant.ENCHANTED_BOOKS, List.of(item, BookEntry.of("unbreaking", 2, 50)));
        List<String> codes = mixed.validate(id -> false, id -> id.equals("sharpness")).stream().map(ReadinessIssue::code).toList();
        assertTrue(codes.contains("box.variant"));
        assertTrue(codes.contains("box.unknown-blueprint"));
        assertTrue(codes.contains("box.duplicate-enchantment"));
        assertTrue(codes.contains("box.unknown-enchantment"));
        assertTrue(!codes.contains("box.total"));
    }

    @Test
    void pickFollowsTheOddsAndIsDeterministic() {
        MysteryBoxPool pool = new MysteryBoxPool(MysteryBoxVariant.ENCHANTED_BOOKS, List.of(
            BookEntry.of("sharpness", 1, 70), BookEntry.of("protection", 2, 29.99), BookEntry.of("mending", 1, 0.01)));
        assertTrue(pool.isValid());
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(21);
        for (int i = 0; i < 10_000; i++) counts.merge(pool.pick(random).entry().describe(), 1, Integer::sum);
        double sharpness = counts.get("book sharpness 1") / 10_000.0;
        assertTrue(sharpness > 0.68 && sharpness < 0.72, "≈ 70 %: " + sharpness);
        assertTrue(counts.getOrDefault("book mending 1", 0) < 10, "0.01 % is rare");
        assertEquals(pool.pick(new Random(5)), pool.pick(new Random(5)));
    }

    @Test
    void everyBasisPointIsReachableAndTheLastEntryCatchesTheTop() {
        MysteryBoxPool pool = new MysteryBoxPool(MysteryBoxVariant.ITEMS, List.of(ItemEntry.of("a", 0.01), ItemEntry.of("b", 99.99)));
        Set<String> seen = new java.util.HashSet<>();
        Random random = new Random(3);
        for (int i = 0; i < 100_000; i++) seen.add(pool.pick(random).entry().describe());
        assertEquals(Set.of("item a", "item b"), seen);
    }

    @Test
    void itemEnchantmentsRollIndependentlyWithTheirOwnChances() {
        ItemEntry sword = new ItemEntry("sword", 10_000, List.of(
            new EnchantmentChance("sharpness", 3, 5000), new EnchantmentChance("fire", 1, 10_000), new EnchantmentChance("looting", 2, 1)));
        MysteryBoxPool pool = new MysteryBoxPool(MysteryBoxVariant.ITEMS, List.of(sword));
        assertTrue(pool.isValid(), "enchantment chances need not add up to anything");
        int sharpness = 0;
        int fire = 0;
        Random random = new Random(8);
        for (int i = 0; i < 4000; i++) {
            MysteryBoxRoll roll = pool.pick(random);
            assertEquals(sword, roll.entry());
            for (EnchantmentChance c : roll.rolledEnchantments()) {
                if (c.enchantmentId().equals("sharpness")) sharpness++;
                if (c.enchantmentId().equals("fire")) fire++;
            }
        }
        assertEquals(4000, fire, "100 % always hits");
        assertTrue(sharpness > 1850 && sharpness < 2150, "≈ 50 %: " + sharpness);
    }

    @Test
    void settingsDefaults() {
        MysteryBoxSettings s = MysteryBoxSettings.defaults();
        assertEquals(1000, s.rollPrice());
        assertEquals(10_000, s.claimTimeoutMillis());
    }
}
