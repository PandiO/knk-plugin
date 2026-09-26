package net.knightsandkings.knk.core.enchantbook;

import net.knightsandkings.knk.core.domain.item.GradeCatalog;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.knightsandkings.knk.core.enchantbook.EnchantBookPayload.Kind.CUSTOM;
import static net.knightsandkings.knk.core.enchantbook.EnchantBookPayload.Kind.VANILLA;
import static org.junit.jupiter.api.Assertions.*;

class EnchantBookCapSettingsTest {

    private final GradeCatalog catalog = new GradeCatalog();
    private final EnchantBookCapSettings defaults = EnchantBookCapSettings.DEFAULTS;

    @Test
    void defaults() {
        assertTrue(defaults.enabled());
        assertFalse(defaults.applyToCustom());
        assertEquals(1, defaults.ungradedStars());
        assertEquals(0.20, defaults.bonusLevelChance());
    }

    @Test
    void levelCap_GradedItem() {
        assertEquals(1, defaults.levelCap(catalog, 1, VANILLA, 5));
        assertEquals(2, defaults.levelCap(catalog, 4, VANILLA, 5));
        assertEquals(5, defaults.levelCap(catalog, 5, VANILLA, 5));
        assertNull(defaults.levelCap(catalog, 6, VANILLA, 5));
        assertNull(defaults.levelCap(catalog, 10, VANILLA, 5));
    }

    @Test
    void levelCap_UngradedItemCountsAsOneStarByDefault() {
        assertEquals(1, defaults.levelCap(catalog, null, VANILLA, 5));
        assertEquals(0, defaults.levelCap(catalog, null, VANILLA, 3));
        assertNull(new EnchantBookCapSettings(true, false, 0, 0.2).levelCap(catalog, null, VANILLA, 5));
        assertEquals(2, new EnchantBookCapSettings(true, false, 4, 0.2).levelCap(catalog, null, VANILLA, 5));
    }

    @Test
    void levelCap_CustomUncappedUnlessConfigured() {
        assertNull(defaults.levelCap(catalog, 1, CUSTOM, 3));
        assertEquals(0, new EnchantBookCapSettings(true, true, 1, 0.2).levelCap(catalog, 1, CUSTOM, 3));
        assertEquals(1, new EnchantBookCapSettings(true, true, 1, 0.2).levelCap(catalog, 3, CUSTOM, 3));
    }

    @Test
    void levelCap_DisabledIsUncapped() {
        assertNull(new EnchantBookCapSettings(false, true, 1, 0.2).levelCap(catalog, 1, VANILLA, 5));
    }

    @Test
    void levelCap_UsesLiveDivisors() {
        catalog.replace(List.of(new KnkGrade(1, "Common", 1, 70.0, 1), new KnkGrade(6, "Mythic", 6, 8.0, 2)));
        assertEquals(5, defaults.levelCap(catalog, 1, VANILLA, 5));
        assertEquals(2, defaults.levelCap(catalog, 6, VANILLA, 5));
    }

    @Test
    void levelCap_UnknownStarsUncapped() {
        assertNull(defaults.levelCap(catalog, 42, VANILLA, 5));
    }

    @Test
    void bonus_RollsAgainstTheChance_Clamped() {
        assertTrue(defaults.bonus(0.0));
        assertTrue(defaults.bonus(0.1999));
        assertFalse(defaults.bonus(0.20));
        assertFalse(new EnchantBookCapSettings(true, false, 1, -1).bonus(0.0));
        assertTrue(new EnchantBookCapSettings(true, false, 1, 5).bonus(0.9999));
        assertFalse(new EnchantBookCapSettings(true, false, 1, Double.NaN).bonus(0.0));
        assertEquals(0, new EnchantBookCapSettings(true, false, -3, 0.2).ungradedStars());
    }
}
