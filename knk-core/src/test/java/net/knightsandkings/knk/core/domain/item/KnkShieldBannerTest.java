package net.knightsandkings.knk.core.domain.item;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#232: a stored shield banner keeps its layer order on the way to the menu engine's form. */
class KnkShieldBannerTest {

    @Test
    void layersKeepTheirOrderInThePatternSpec() {
        KnkShieldBanner banner = new KnkShieldBanner("RED", List.of(
                new KnkShieldBanner.Layer("minecraft:stripe_top", "YELLOW"),
                new KnkShieldBanner.Layer("minecraft:cross", "WHITE"),
                new KnkShieldBanner.Layer("minecraft:border", "BLACK"),
                new KnkShieldBanner.Layer("minecraft:stripe_top", "BLUE")));

        BannerPatternSpec spec = banner.toPatternSpec();

        assertEquals("RED", spec.baseColor());
        assertEquals(List.of(
                new BannerPatternSpec.Layer("minecraft:stripe_top", "YELLOW"),
                new BannerPatternSpec.Layer("minecraft:cross", "WHITE"),
                new BannerPatternSpec.Layer("minecraft:border", "BLACK"),
                new BannerPatternSpec.Layer("minecraft:stripe_top", "BLUE")), spec.layers());
        assertTrue(spec.errors().isEmpty());
    }

    @Test
    void aBaseColourWithoutLayersIsAPlainColouredShield() {
        BannerPatternSpec spec = new KnkShieldBanner("BLUE", List.of()).toPatternSpec();
        assertEquals("BLUE", spec.baseColor());
        assertTrue(spec.layers().isEmpty());
    }

    @Test
    void emptyBannerHasNoSpec() {
        KnkShieldBanner empty = new KnkShieldBanner(null, null);
        assertTrue(empty.isEmpty());
        assertNull(empty.toPatternSpec());
        assertFalse(new KnkShieldBanner(null, List.of(new KnkShieldBanner.Layer("minecraft:globe", "PINK"))).isEmpty());
    }

    @Test
    void unknownColoursAreReportedNotThrown() {
        BannerPatternSpec spec = new KnkShieldBanner("MAROON", List.of(new KnkShieldBanner.Layer("minecraft:cross", "PLAID"))).toPatternSpec();
        assertNull(spec.baseColor());
        assertTrue(spec.layers().isEmpty());
        assertEquals(2, spec.errors().size(), spec.errors().toString());
    }

    @Test
    void blueprintKeepsItsLookThroughWithGradeAndWithLook() {
        KnkShieldBanner banner = new KnkShieldBanner("RED", List.of(new KnkShieldBanner.Layer("minecraft:cross", "WHITE")));
        KnkArmorTrim trim = new KnkArmorTrim("minecraft:silence", "minecraft:netherite");
        KnkItemBlueprint plain = new KnkItemBlueprint(1, "Shield", null, null, "minecraft:shield", "Shield", null, 1, 1,
                List.of(), 0, null, List.of(), List.of());
        assertNull(plain.shieldBanner());
        assertNull(plain.armorTrim());

        KnkItemBlueprint dressed = plain.withLook(banner, trim);
        assertEquals(banner, dressed.shieldBanner());
        assertEquals(trim, dressed.armorTrim());
        assertEquals(banner, dressed.withGrade(new KnkGrade(1, "Rare", 3)).shieldBanner());
        assertTrue(trim.isComplete());
        assertFalse(new KnkArmorTrim("minecraft:silence", null).isComplete());
    }
}
