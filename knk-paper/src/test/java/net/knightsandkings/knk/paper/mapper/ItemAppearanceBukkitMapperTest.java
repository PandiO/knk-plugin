package net.knightsandkings.knk.paper.mapper;

import com.google.gson.JsonObject;
import net.knightsandkings.knk.core.domain.item.KnkArmorTrim;
import net.knightsandkings.knk.core.domain.item.KnkShieldBanner;
import net.knightsandkings.knk.paper.tasks.ScannedItemJsonBuilder;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PandiO/knk-workspace#232: the look is only put on metas that can hold it, and the scan's JSON for it is the shape
 * the web app's ItemBlueprint form reads. Applying a real pattern or trim, and scanning a real item, need the server
 * registries (PatternType, TrimPattern and even Material.isAir can't run without one) - that is the in-game smoke test.
 */
class ItemAppearanceBukkitMapperTest {

    private static final KnkShieldBanner BANNER = new KnkShieldBanner("RED", List.of(
            new KnkShieldBanner.Layer("minecraft:stripe_top", "YELLOW"),
            new KnkShieldBanner.Layer("minecraft:border", "BLACK")));
    private static final KnkArmorTrim TRIM = new KnkArmorTrim("minecraft:silence", "minecraft:netherite");

    @Test
    void aMetaThatIsNotABannerOrArmourIsLeftAlone() {
        ItemMeta swordMeta = mock(ItemMeta.class);
        List<String> warnings = new ArrayList<>();

        ItemAppearanceBukkitMapper.applyShieldBanner(swordMeta, BANNER, warnings::add);
        ItemAppearanceBukkitMapper.applyArmorTrim(swordMeta, TRIM, warnings::add);

        verifyNoInteractions(swordMeta);
        assertTrue(warnings.isEmpty());
        assertNull(ItemAppearanceBukkitMapper.readShieldBanner(swordMeta));
        assertNull(ItemAppearanceBukkitMapper.readArmorTrim(swordMeta));
    }

    @Test
    void nothingToApplyTouchesNothing() {
        ItemMeta meta = mock(ItemMeta.class);
        ItemAppearanceBukkitMapper.applyShieldBanner(meta, null, s -> {});
        ItemAppearanceBukkitMapper.applyShieldBanner(meta, new KnkShieldBanner(null, List.of()), s -> {});
        ItemAppearanceBukkitMapper.applyArmorTrim(meta, null, s -> {});
        ItemAppearanceBukkitMapper.applyArmorTrim(meta, new KnkArmorTrim("minecraft:silence", null), s -> {});
        ItemAppearanceBukkitMapper.apply(null, BANNER, TRIM, "blueprint 1");
        verifyNoInteractions(meta);
    }

    @Test
    void scanJsonHasTheWebAppsShape() {
        JsonObject banner = ScannedItemJsonBuilder.shieldBannerJson(BANNER);
        assertEquals("RED", banner.get("baseColor").getAsString());
        assertEquals(2, banner.getAsJsonArray("layers").size());
        assertEquals("minecraft:stripe_top", banner.getAsJsonArray("layers").get(0).getAsJsonObject().get("patternKey").getAsString());
        assertEquals("YELLOW", banner.getAsJsonArray("layers").get(0).getAsJsonObject().get("color").getAsString());
        assertEquals("minecraft:border", banner.getAsJsonArray("layers").get(1).getAsJsonObject().get("patternKey").getAsString());

        JsonObject bannerItem = ScannedItemJsonBuilder.shieldBannerJson(new KnkShieldBanner(null, BANNER.layers()));
        assertTrue(bannerItem.get("baseColor").isJsonNull(), "a banner item's colour is its material");

        assertNull(ScannedItemJsonBuilder.shieldBannerJson(null));
        assertNull(ScannedItemJsonBuilder.shieldBannerJson(new KnkShieldBanner(null, List.of())));

        JsonObject trim = ScannedItemJsonBuilder.armorTrimJson(TRIM);
        assertEquals("minecraft:silence", trim.get("pattern").getAsString());
        assertEquals("minecraft:netherite", trim.get("material").getAsString());
        assertNull(ScannedItemJsonBuilder.armorTrimJson(null));
        assertNull(ScannedItemJsonBuilder.armorTrimJson(new KnkArmorTrim(null, "minecraft:gold")));
    }
}
