package net.knightsandkings.knk.paper.clan;

import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Siege Phase 1: banner patterns are applied bottom to top; unknown keys are skipped, not thrown.
 * Uses stand-ins for PatternType/Pattern - PatternType can't be loaded (or mocked) without a server.
 */
class BannerDesignBukkitMapperTest {

    private record Applied(DyeColor color, String pattern) {}

    private static final Set<String> KNOWN = Set.of("minecraft:stripe_top", "minecraft:cross", "minecraft:border");

    private static String resolve(String key) {
        return KNOWN.contains(key) ? key : null;
    }

    @Test
    void patternsFollowLayerOrder() {
        KnkBannerDesign design = new KnkBannerDesign(1, "Crown", "RED", List.of(
                new KnkBannerLayer(3, 2, "minecraft:border", "BLACK"),
                new KnkBannerLayer(1, 0, "minecraft:stripe_top", "YELLOW"),
                new KnkBannerLayer(2, 1, "minecraft:cross", "WHITE")));

        List<Applied> applied = BannerDesignBukkitMapper.toLayers(
                design.toPatternSpec(), BannerDesignBukkitMapperTest::resolve, Applied::new, key -> {});

        assertEquals(List.of(
                new Applied(DyeColor.YELLOW, "minecraft:stripe_top"),
                new Applied(DyeColor.WHITE, "minecraft:cross"),
                new Applied(DyeColor.BLACK, "minecraft:border")), applied);
    }

    @Test
    void unknownPatternIsSkippedAndReported() {
        KnkBannerDesign design = new KnkBannerDesign(1, "Future", "WHITE", List.of(
                new KnkBannerLayer(1, 0, "minecraft:from_a_newer_version", "RED"),
                new KnkBannerLayer(2, 1, "minecraft:cross", "BLUE")));
        List<String> unknown = new ArrayList<>();

        List<Applied> applied = BannerDesignBukkitMapper.toLayers(
                design.toPatternSpec(), BannerDesignBukkitMapperTest::resolve, Applied::new, unknown::add);

        assertEquals(List.of(new Applied(DyeColor.BLUE, "minecraft:cross")), applied);
        assertEquals(List.of("minecraft:from_a_newer_version"), unknown);
    }

    @Test
    void baseColourPicksBannerMaterial() {
        assertEquals(Material.RED_BANNER, BannerDesignBukkitMapper.bannerMaterial("RED"));
        assertEquals(Material.LIGHT_BLUE_BANNER, BannerDesignBukkitMapper.bannerMaterial("LIGHT_BLUE"));
        assertEquals(Material.WHITE_BANNER, BannerDesignBukkitMapper.bannerMaterial(null));
        assertEquals(Material.WHITE_BANNER, BannerDesignBukkitMapper.bannerMaterial("NOT_A_COLOUR"));
    }
}
