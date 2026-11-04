package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.ItemBlueprintReadDto;
import net.knightsandkings.knk.api.dto.LootboxDtos;
import net.knightsandkings.knk.core.domain.item.KnkArmorTrim;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkShieldBanner;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#232: the look fields of the blueprint and claim JSON reach the core records, layers in order. */
class ItemAppearanceMapperTest {

    // The client's mapper shape (LootboxMapperTest.apiObjectMapper): unknown fields ignored, java.time supported.
    private final ObjectMapper mapper = LootboxMapperTest.apiObjectMapper();

    private static final String LOOK = """
            "shieldBannerBaseColor":"RED",
            "shieldBannerLayers":[{"patternKey":"minecraft:stripe_top","color":"YELLOW"},{"patternKey":"minecraft:cross","color":"WHITE"},{"patternKey":"minecraft:border","color":"BLACK"}],
            "armorTrimPattern":"minecraft:silence","armorTrimMaterial":"minecraft:netherite"
            """;

    private static final KnkShieldBanner BANNER = new KnkShieldBanner("RED", List.of(
            new KnkShieldBanner.Layer("minecraft:stripe_top", "YELLOW"),
            new KnkShieldBanner.Layer("minecraft:cross", "WHITE"),
            new KnkShieldBanner.Layer("minecraft:border", "BLACK")));

    @Test
    void blueprintJsonMapsTheLook() throws Exception {
        String json = "{\"id\":7,\"name\":\"Kingsguard Shield\",\"defaultDisplayName\":\"Shield\",\"maxStackSize\":1," + LOOK + "}";

        KnkItemBlueprint blueprint = ItemBlueprintMapper.toCore(mapper.readValue(json, ItemBlueprintReadDto.class));

        assertEquals(BANNER, blueprint.shieldBanner());
        assertEquals(new KnkArmorTrim("minecraft:silence", "minecraft:netherite"), blueprint.armorTrim());
    }

    @Test
    void anOlderApiOrAPlainItemHasNoLook() throws Exception {
        KnkItemBlueprint older = ItemBlueprintMapper.toCore(mapper.readValue("{\"id\":7,\"name\":\"Sword\"}", ItemBlueprintReadDto.class));
        assertNull(older.shieldBanner());
        assertNull(older.armorTrim());

        KnkItemBlueprint plain = ItemBlueprintMapper.toCore(mapper.readValue(
                "{\"id\":8,\"name\":\"Shield\",\"shieldBannerBaseColor\":null,\"shieldBannerLayers\":[],\"armorTrimPattern\":null,\"armorTrimMaterial\":null}",
                ItemBlueprintReadDto.class));
        assertNull(plain.shieldBanner());
        assertNull(plain.armorTrim());
    }

    @Test
    void halfATrimIsNoTrim() {
        assertNull(ItemAppearanceMapper.toArmorTrim("minecraft:silence", null));
        assertNull(ItemAppearanceMapper.toArmorTrim("", "minecraft:gold"));
        assertEquals(new KnkShieldBanner("BLUE", List.of()), ItemAppearanceMapper.toShieldBanner("BLUE", null));
    }

    @Test
    void claimJsonMapsTheLook() throws Exception {
        String json = "{\"claimId\":41,\"replay\":false,\"userId\":9,\"lootboxTypeId\":3,\"boxStars\":5,\"boxLabel\":\"Box\","
                + "\"itemInstanceId\":5,\"itemBlueprintId\":77,\"itemName\":\"Shield\",\"quantity\":1,\"isSpecial\":false,"
                + "\"enchantments\":[],\"announce\":false,\"claimedAt\":\"2026-11-04T12:00:00Z\",\"deliveredAt\":null," + LOOK + "}";

        KnkLootboxClaimResult claim = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.ClaimResultDto.class));

        assertTrue(claim.hasLook());
        assertEquals(BANNER, claim.shieldBanner());
        assertEquals(new KnkArmorTrim("minecraft:silence", "minecraft:netherite"), claim.armorTrim());

        KnkLootboxClaimResult older = LootboxMapper.toCore(mapper.readValue(
                "{\"claimId\":41,\"replay\":false,\"userId\":9,\"lootboxTypeId\":3,\"boxStars\":5,\"itemBlueprintId\":77,\"quantity\":1,\"isSpecial\":false,\"announce\":false}",
                LootboxDtos.ClaimResultDto.class));
        assertFalse(older.hasLook());
    }
}
