package net.knightsandkings.knk.core.domain.item;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradeCatalogTest {

    @Test
    void defaults_AreTheDecidedTable() {
        List<KnkGrade> d = GradeCatalog.DEFAULTS;
        assertEquals(10, d.size());
        Integer[] divisors = {5, 4, 3, 2, 1, null, null, null, null, null};
        double[] drops = {70, 60, 40, 25, 15, 8, 5, 1, 0.5, 0.05};
        String[] names = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Ascended", "Relic", "Exalted", "Divine"};
        for (int i = 0; i < 10; i++) {
            assertEquals(i + 1, d.get(i).stars());
            assertEquals(names[i], d.get(i).name());
            assertEquals(divisors[i], d.get(i).enchantLevelCapDivisor());
            assertEquals(drops[i], d.get(i).dropChance(), 1e-9);
        }
    }

    @Test
    void byStars_FallsBackToDefaultsUntilLoaded() {
        GradeCatalog catalog = new GradeCatalog();
        assertFalse(catalog.isLoaded());
        assertEquals(5, catalog.byStars(1).orElseThrow().enchantLevelCapDivisor());
        assertNull(catalog.byStars(6).orElseThrow().enchantLevelCapDivisor());
        assertTrue(catalog.byStars(11).isEmpty());
        assertTrue(catalog.byId(1).isEmpty());
    }

    @Test
    void byStars_PrefersLiveValues_SoRetuningAppliesToExistingItems() {
        GradeCatalog catalog = new GradeCatalog();
        catalog.replace(List.of(new KnkGrade(11, "Common", 1, 70.0, 3), new KnkGrade(16, "Mythic", 6, 8.0, 1)));
        assertTrue(catalog.isLoaded());
        assertEquals(3, catalog.byStars(1).orElseThrow().enchantLevelCapDivisor());
        assertEquals(1, catalog.byStars(6).orElseThrow().enchantLevelCapDivisor());
        // Not in the live table: seeded default.
        assertEquals(4, catalog.byStars(2).orElseThrow().enchantLevelCapDivisor());
        assertEquals("Mythic", catalog.byId(16).orElseThrow().name());
    }

    @Test
    void replace_IgnoresNullsAndDuplicateStars() {
        GradeCatalog catalog = new GradeCatalog();
        java.util.ArrayList<KnkGrade> grades = new java.util.ArrayList<>();
        grades.add(null);
        grades.add(new KnkGrade(2, "B", 1, null, 3));
        grades.add(new KnkGrade(1, "A", 1, null, 2)); // lowest id wins, whatever the order
        grades.add(new KnkGrade(null, "No id", 4, null, 9));
        catalog.replace(grades);
        assertEquals(2, catalog.byStars(1).orElseThrow().enchantLevelCapDivisor());
        assertEquals(9, catalog.byStars(4).orElseThrow().enchantLevelCapDivisor());
        catalog.replace(null); // no-op
        assertEquals(2, catalog.byStars(1).orElseThrow().enchantLevelCapDivisor());
    }

    @Test
    void starsOf_UsesOwnStarsElseLooksUpById() {
        GradeCatalog catalog = new GradeCatalog();
        assertEquals(3, catalog.starsOf(new KnkGrade(9, "Rare", 3)).orElseThrow());
        assertTrue(catalog.starsOf(new KnkGrade(9, "Rare", null)).isEmpty()); // not loaded yet
        catalog.replace(List.of(new KnkGrade(9, "Rare", 3, 40.0, 3)));
        assertEquals(3, catalog.starsOf(new KnkGrade(9, "Rare", null)).orElseThrow());
        assertTrue(catalog.starsOf(new KnkGrade(9, "Zero", 0)).isEmpty());
        assertTrue(catalog.starsOf(null).isEmpty());
        assertTrue(catalog.starsOf(new KnkGrade(null, "x", null)).isEmpty());
    }
}
