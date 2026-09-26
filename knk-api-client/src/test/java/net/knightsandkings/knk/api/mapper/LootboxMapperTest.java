package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.dto.LootboxDtos;
import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lootboxes Phase 3: knk-web-api's runtime JSON (as {@code Dtos/LootboxRuntimeDtos.cs} serializes it, including
 * MySQL-read times without an offset) through the api-client DTOs into the knk-core records.
 */
public class LootboxMapperTest {

    public static ObjectMapper apiObjectMapper() {
        JavaTimeModule time = new JavaTimeModule();
        time.addDeserializer(OffsetDateTime.class, new LenientOffsetDateTimeDeserializer());
        return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).registerModule(time);
    }

    private final ObjectMapper mapper = apiObjectMapper();

    @Test
    void runtimeConfig() throws Exception {
        String json = """
                {"enabled":true,"globalMaxActive":15,"maxClaimsPerPlayerPerDay":10,"announceMinItemStars":5,
                 "announceSpawnMinBoxStars":6,"dropAnnouncementTemplate":"&6{player} &efound {item} &ein a {box}!",
                 "spawnAnnouncementTemplate":"&eA {box} &eappeared in &6{area}&e!","serverTimeUtc":"2026-09-26T12:00:00Z",
                 "types":[{"id":3,"name":"Weapons Lootbox","categoryId":1,"categoryName":"Weapons",
                           "displayMaterialKey":"minecraft:iron_sword","spawnWeight":10,"minBoxStars":1,"maxBoxStars":5,
                           "maxClaimsPerPlayerPerDay":null,"announceMinItemStars":4}],
                 "grades":[{"id":5,"name":"Legendary","stars":5}],
                 "areas":[{"id":9,"name":"spawn","world":"world","wgRegionId":"lootbox_spawn","enabled":false,"maxActive":3,
                           "spawnIntervalSeconds":600,"spawnChancePercent":100.0,"minOnlinePlayers":3,
                           "minDistanceFromPlayers":24,"lifetimeMinutes":30,"excludedRegionIds":["plot_1"],
                           "allowedTypeIds":[3],"activeCount":1,"createdByUserId":2,"somethingNew":true}]}
                """;

        KnkLootboxRuntimeConfig config = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.RuntimeConfigDto.class));

        assertTrue(config.enabled());
        assertEquals(10, config.maxClaimsPerPlayerPerDay());
        assertEquals(Instant.parse("2026-09-26T12:00:00Z"), config.serverTimeUtc());
        assertEquals(4, config.typeByCategory("weapons").orElseThrow().announceMinItemStars());
        assertEquals("Legendary", config.gradeName(5).orElseThrow());
        KnkLootboxArea area = config.areaByName("SPAWN").orElseThrow();
        assertFalse(area.enabled());
        assertEquals(List.of("plot_1"), area.excludedRegionIds());
        assertEquals(List.of(3), area.allowedTypeIds());
        assertEquals(100.0, area.spawnChancePercent());
    }

    @Test
    void spawn_withTimesWithoutOffset_areUtc() throws Exception {
        String json = """
                {"id":12,"token":"6f1c1d1e-0000-4000-8000-000000000001","lootboxTypeId":3,"lootboxTypeName":"Weapons Lootbox",
                 "categoryName":"Weapons","boxGradeId":5,"boxGradeName":"Legendary","boxStars":5,
                 "boxLabel":"Legendary Weapons Lootbox","spawnAreaId":null,"world":"world","x":-17,"y":70,"z":33,
                 "status":"Active","spawnedAt":"2026-09-26T12:00:00","expiresAt":"2026-09-26T12:30:00"}
                """;

        KnkLootboxSpawn spawn = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.SpawnDto.class));

        assertEquals(UUID.fromString("6f1c1d1e-0000-4000-8000-000000000001"), spawn.token());
        assertNull(spawn.spawnAreaId());
        assertEquals(-2, spawn.chunkX());
        assertEquals(2, spawn.chunkZ());
        assertEquals(Instant.parse("2026-09-26T12:30:00Z"), spawn.expiresAt());
    }

    @Test
    void claimResult_carriesTheInstanceIdAndEnchantments() throws Exception {
        String json = """
                {"claimId":41,"replay":true,"userId":9,"lootboxSpawnId":12,"lootboxTypeId":3,"boxStars":5,
                 "boxLabel":"Legendary Weapons Lootbox","itemInstanceId":9007199254740993,"itemBlueprintId":77,
                 "itemName":"Flaming Samurai","itemGradeId":5,"itemGradeStars":5,"quantity":1,"isSpecial":true,
                 "enchantments":[{"definitionId":1,"key":"minecraft:sharpness","isCustom":false,"level":5},
                                 {"definitionId":20,"key":"strength","isCustom":true,"level":2}],
                 "announce":true,"claimedAt":"2026-09-26T12:01:00Z","deliveredAt":null}
                """;

        KnkLootboxClaimResult claim = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.ClaimResultDto.class));

        assertTrue(claim.replay());
        assertEquals(9007199254740993L, claim.itemInstanceId());
        assertEquals(List.of(new KnkLootboxClaimEnchantment(1, "minecraft:sharpness", false, 5),
                new KnkLootboxClaimEnchantment(20, "strength", true, 2)), claim.enchantments());
        assertTrue(claim.isSpecial());
        assertFalse(claim.isDelivered());
    }

    @Test
    void stackableClaim_hasNoInstance() throws Exception {
        String json = """
                {"claimId":42,"userId":9,"lootboxTypeId":4,"boxStars":2,"itemBlueprintId":5,"quantity":16,
                 "enchantments":null,"deliveredAt":"2026-09-26 12:02:00"}
                """;

        KnkLootboxClaimResult claim = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.ClaimResultDto.class));

        assertNull(claim.itemInstanceId());
        assertTrue(claim.enchantments().isEmpty());
        assertTrue(claim.isDelivered());
    }

    @Test
    void inGameArea_splitsTheExcludedRegionsCsv() throws Exception {
        String json = """
                {"id":9,"name":"spawn","world":"world","wgRegionId":"lootbox_spawn","enabled":true,"maxActive":3,
                 "spawnIntervalSeconds":600,"spawnChancePercent":100,"minOnlinePlayers":3,"minDistanceFromPlayers":24,
                 "lifetimeMinutes":30,"excludedRegionIds":" a , ,b","createdByUserId":2,"allowedTypes":[]}
                """;

        KnkLootboxArea area = LootboxMapper.toCore(mapper.readValue(json, LootboxDtos.SpawnAreaDto.class));

        assertEquals(List.of("a", "b"), area.excludedRegionIds());
        assertTrue(area.allowedTypeIds().isEmpty());
        assertEquals(0, area.activeCount());
    }

    @Test
    void nullConfig_isTheEmptyConfig() {
        assertFalse(LootboxMapper.toCore((LootboxDtos.RuntimeConfigDto) null).enabled());
    }
}
