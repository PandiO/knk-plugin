package net.knightsandkings.knk.core.domain.clan;

import net.knightsandkings.knk.core.menu.BannerPatternSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 1: banner layer order and the conversion to the menu engine's banner form. */
class KnkBannerDesignTest {

    @Test
    void layersAreOrderedBottomToTopBySortOrderThenId() {
        KnkBannerDesign design = new KnkBannerDesign(1, "Crown", "RED", List.of(
                new KnkBannerLayer(30, 2, "minecraft:border", "BLACK"),
                new KnkBannerLayer(10, 0, "minecraft:stripe_top", "YELLOW"),
                new KnkBannerLayer(21, 1, "minecraft:cross", "WHITE"),
                new KnkBannerLayer(20, 1, "minecraft:circle", "BLUE")));

        assertEquals(List.of(10, 20, 21, 30), design.layers().stream().map(KnkBannerLayer::id).toList());
    }

    @Test
    void toPatternSpecKeepsOrderAndBaseColour() {
        KnkBannerDesign design = new KnkBannerDesign(1, "Crown", "LIGHT_BLUE", List.of(
                new KnkBannerLayer(2, 1, "minecraft:border", "BLACK"),
                new KnkBannerLayer(1, 0, "minecraft:stripe_top", "YELLOW")));

        BannerPatternSpec spec = design.toPatternSpec();

        assertEquals("LIGHT_BLUE", spec.baseColor());
        assertEquals(List.of(
                new BannerPatternSpec.Layer("minecraft:stripe_top", "YELLOW"),
                new BannerPatternSpec.Layer("minecraft:border", "BLACK")), spec.layers());
        assertTrue(spec.errors().isEmpty());
    }

    @Test
    void plainBannerHasNoLayers() {
        BannerPatternSpec spec = new KnkBannerDesign(1, "Plain", "GREEN", null).toPatternSpec();

        assertEquals("GREEN", spec.baseColor());
        assertTrue(spec.layers().isEmpty());
    }

    @Test
    void badLayerIsReportedNotThrown() {
        BannerPatternSpec spec = new KnkBannerDesign(1, "Odd", null, List.of(
                new KnkBannerLayer(1, 0, "minecraft:cross", "NOT_A_COLOUR"),
                new KnkBannerLayer(2, 1, "minecraft:border", "BLACK"))).toPatternSpec();

        assertNull(spec.baseColor());
        assertEquals(1, spec.layers().size());
        assertEquals(1, spec.errors().size());
    }
}
