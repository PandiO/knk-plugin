package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxVariant;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.EquipmentMode;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.MysteryBoxPointSpec;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.PurchasePointSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Link S7b: the phase 4 sections of the frozen configuration (equipment, purchase points, Mystery Box points). */
class SurvivalPhase4MappingTest {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String BASE = """
        "siegeScenarioId": 1, "siegeScenarioName": "Test", "minPlayers": 1, "humanTeamId": 1, "npcTeamId": 2,
        "endConditions": { "scenarioAllCaptured": false, "lobbyOverride": "Inherit" }, "objectives": [], "gates": [],
        "stuck": { "stuckAfterMillis": 30000, "lastEnemiesCount": 3, "respawnLimit": 2 },
        "spawn": { "favorNearPlayersPercent": 0, "nearZoneCount": 1, "zones": [] },
        "difficulty": { "id": 1, "name": "Normal", "countCurve": { "preset": "Linear", "base": 2, "step": 1 },
          "statCurve": { "preset": "Linear", "base": 1, "step": 0.5 },
          "statWeights": { "health": 1, "walkingSpeed": 1, "meleeDamage": 1, "rangedDamage": 1, "attackSpeed": 1 },
          "perPlayer": { "factor": 0, "applyToStats": false }, "maxAlive": 40, "statCap": 8, "countCap": 0, "spawnIntervalMillis": 500 },
        "monsterTypes": [ { "entityType": "ZOMBIE", "weight": 1, "unlockRound": 1 } ], "readiness": { "isReady": true }
        """;

    private static SurvivalConfiguration map(String extra) throws Exception {
        return SurvivalConfigurationMapper.map(JSON.readValue("{" + BASE + (extra.isBlank() ? "" : ", " + extra) + "}", ConfigurationDto.class));
    }

    @Test
    void noPhase4SectionsMeansOwnInventoryAndNoPoints() throws Exception {
        SurvivalConfiguration c = map("");
        assertEquals(EquipmentMode.OWN_INVENTORY, c.equipment().mode());
        assertTrue(c.equipment().kits().isEmpty());
        assertTrue(c.purchasePoints().isEmpty());
        assertTrue(c.mysteryBoxPoints().isEmpty());
    }

    @Test
    void kitsModeKeepsTheSelectedKitsInOrderWithTheDefault() throws Exception {
        SurvivalConfiguration c = map("""
            "equipment": { "mode": "Kits", "selectedKits": [ { "kitId": 5, "kitName": "Knight", "sortOrder": 2 },
              { "kitId": 3, "kitName": "Archer", "sortOrder": 1 }, { "kitId": null } ], "defaultKitId": 5, "baselineLoadoutKitId": null }
            """);
        assertEquals(EquipmentMode.KITS, c.equipment().mode());
        assertEquals(2, c.equipment().kits().size());
        assertEquals(3, c.equipment().kits().get(0).kitId());
        assertEquals("Archer", c.equipment().kits().get(0).name());
        assertEquals(5, c.equipment().defaultKitId().orElseThrow());
        assertTrue(c.equipment().baselineKitId().isEmpty());
        var selection = c.equipment().newSelection();
        assertTrue(selection.worthAsking());
        assertEquals(5, selection.resolve(java.util.UUID.randomUUID()).orElseThrow().kitId());
    }

    @Test
    void matchProgressionCarriesTheBaselineKitAndUnknownModesFallBack() throws Exception {
        SurvivalConfiguration c = map("\"equipment\": { \"mode\": \"MatchProgression\", \"baselineLoadoutKitId\": 9 }");
        assertEquals(EquipmentMode.MATCH_PROGRESSION, c.equipment().mode());
        assertEquals(9, c.equipment().baselineKitId().orElseThrow());
        assertEquals(EquipmentMode.MATCH_PROGRESSION, SurvivalConfigurationMapper.parseEquipmentMode("match_progression"));
        assertEquals(EquipmentMode.KITS, SurvivalConfigurationMapper.parseEquipmentMode("KITS"));
        assertEquals(EquipmentMode.OWN_INVENTORY, SurvivalConfigurationMapper.parseEquipmentMode("OwnInventory"));
        assertEquals(EquipmentMode.OWN_INVENTORY, SurvivalConfigurationMapper.parseEquipmentMode("??"));
    }

    @Test
    void purchasePointsMapOffersInOrderWithDefaults() throws Exception {
        SurvivalConfiguration c = map("""
            "purchasePoints": [ { "id": 4, "name": "Armory", "sortOrder": 1, "location": { "x": 10.2, "y": 64, "z": -3.7, "world": "w" },
              "interactionRange": 3.5, "offers": [
                { "id": 11, "itemBlueprintId": 100, "itemBlueprintName": "Arrows", "quantity": 16, "price": 40, "sortOrder": 2 },
                { "id": 12, "itemBlueprintId": 101, "itemBlueprintName": "Sword", "quantity": 0, "price": -5, "sortOrder": 1 },
                { "id": 13, "itemBlueprintId": null, "quantity": 1, "price": 1 } ] },
              { "id": 2, "name": null, "sortOrder": 0, "location": null, "interactionRange": 0, "offers": null } ]
            """);
        assertEquals(2, c.purchasePoints().size());
        PurchasePointSpec first = c.purchasePoints().get(0);
        assertEquals(2, first.id());
        assertEquals("purchase point 2", first.name());
        assertTrue(first.location().isEmpty());
        assertEquals(4.0, first.interactionRange());
        PurchasePointSpec armory = c.purchasePoint(4).orElseThrow();
        assertEquals(3.5, armory.interactionRange());
        assertEquals("w", armory.location().orElseThrow().world());
        assertEquals(2, armory.offers().size(), "the offer without a blueprint is dropped");
        assertEquals(12, armory.offers().get(0).id());
        assertEquals(1, armory.offers().get(0).quantity());
        assertEquals(0, armory.offers().get(0).price());
        assertEquals(16, armory.offers().get(1).quantity());
        assertEquals(40, armory.offers().get(1).price());
    }

    @Test
    void mysteryBoxPointsMapBothVariantsWithBasisPointsAndTheCorePool() throws Exception {
        SurvivalConfiguration c = map("""
            "mysteryBoxPoints": [
              { "id": 7, "name": "Items box", "location": { "x": 1, "y": 65, "z": 1, "world": "w" }, "blockType": "minecraft:END_PORTAL_FRAME",
                "rollPrice": 500, "claimTimeoutMillis": 8000, "pool": { "id": 1, "variant": "Items", "entries": [
                  { "id": 21, "sortOrder": 2, "itemBlueprintId": 100, "itemBlueprintName": "Bow", "chancePercent": 40, "chanceBasisPoints": 4000,
                    "enchantments": [ { "id": 1, "enchantmentDefinitionId": 50, "enchantmentKey": "minecraft:power", "level": 2, "chancePercent": 25.5, "chanceBasisPoints": 2550 } ] },
                  { "id": 22, "sortOrder": 1, "itemBlueprintId": 101, "itemBlueprintName": "Sword", "chancePercent": 60, "chanceBasisPoints": null } ] } },
              { "id": 8, "name": "Books", "location": { "x": 2, "y": 65, "z": 1, "world": "w" }, "blockType": null, "rollPrice": null, "claimTimeoutMillis": 0,
                "pool": { "id": 2, "variant": "EnchantedBooks", "entries": [
                  { "id": 31, "enchantmentDefinitionId": 50, "enchantmentKey": "minecraft:power", "enchantmentLevel": 3, "chanceBasisPoints": 7000 },
                  { "id": 32, "enchantmentDefinitionId": 51, "enchantmentKey": "minecraft:flame", "enchantmentLevel": 1, "chanceBasisPoints": 2999 } ] } } ]
            """);
        assertEquals(2, c.mysteryBoxPoints().size());
        MysteryBoxPointSpec itemsBox = c.mysteryBoxPoint(7).orElseThrow();
        assertEquals("minecraft:end_portal_frame", itemsBox.blockType());
        assertEquals(500, itemsBox.rollPrice());
        assertEquals(8000, itemsBox.claimTimeoutMillis());
        assertEquals(MysteryBoxVariant.ITEMS, itemsBox.variant());
        assertEquals(2, itemsBox.entries().size());
        assertEquals(22, itemsBox.entries().get(0).id(), "sorted by sortOrder");
        assertEquals(6000, itemsBox.entries().get(0).basisPoints(), "percent converted when basis points are missing");
        assertEquals(4000, itemsBox.entry(21).orElseThrow().basisPoints());
        assertEquals(2550, itemsBox.entry(21).orElseThrow().enchantments().get(0).basisPoints());
        assertFalse(itemsBox.entry(21).orElseThrow().isBook());
        assertEquals("Bow", itemsBox.entry(21).orElseThrow().describe());
        assertTrue(itemsBox.pool().isValid(), "exactly 100 %");
        assertEquals(10_000, itemsBox.pool().totalBasisPoints());

        MysteryBoxPointSpec books = c.mysteryBoxPoint(8).orElseThrow();
        assertEquals(MysteryBoxPointSpec.DEFAULT_BLOCK_TYPE, books.blockType());
        assertEquals(1000, books.rollPrice());
        assertEquals(10_000, books.claimTimeoutMillis());
        assertEquals(MysteryBoxVariant.ENCHANTED_BOOKS, books.variant());
        assertTrue(books.entry(31).orElseThrow().isBook());
        assertEquals("book minecraft:power 3", books.entry(31).orElseThrow().describe());
        assertFalse(books.pool().isValid(), "99.99 %");
        assertEquals(9999, books.pool().totalBasisPoints());
    }

    @Test
    void basisPointsPreferTheServedValueAndRejectFinerPercents() {
        assertEquals(1234, SurvivalConfigurationMapper.basisPoints(1234, new BigDecimal("99")));
        assertEquals(1250, SurvivalConfigurationMapper.basisPoints(null, new BigDecimal("12.5")));
        assertEquals(0, SurvivalConfigurationMapper.basisPoints(null, new BigDecimal("12.345")));
        assertEquals(0, SurvivalConfigurationMapper.basisPoints(null, null));
        assertEquals(0, SurvivalConfigurationMapper.basisPoints(-5, null));
        assertEquals(MysteryBoxVariant.ENCHANTED_BOOKS, SurvivalConfigurationMapper.parseVariant("enchanted_books"));
        assertEquals(MysteryBoxVariant.ITEMS, SurvivalConfigurationMapper.parseVariant(null));
    }
}
