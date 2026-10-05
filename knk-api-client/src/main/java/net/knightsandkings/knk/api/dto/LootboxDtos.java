package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of knk-web-api's lootbox runtime (docs/specs/lootboxes/DESIGN.md §3.3; {@code Dtos/LootboxRuntimeDtos.cs},
 * the odds part of {@code Dtos/LootboxDtos.cs}). Only the fields the plugin uses; unknown ones are ignored.
 */
public final class LootboxDtos {

    private LootboxDtos() {
    }

    // ===== GET api/LootboxSpawns/runtime-config =====

    public record RuntimeConfigDto(
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("globalMaxActive") int globalMaxActive,
            @JsonProperty("maxClaimsPerPlayerPerDay") Integer maxClaimsPerPlayerPerDay,
            @JsonProperty("announceMinItemStars") int announceMinItemStars,
            @JsonProperty("announceSpawnMinBoxStars") int announceSpawnMinBoxStars,
            @JsonProperty("dropAnnouncementTemplate") String dropAnnouncementTemplate,
            @JsonProperty("spawnAnnouncementTemplate") String spawnAnnouncementTemplate,
            @JsonProperty("serverTimeUtc") OffsetDateTime serverTimeUtc,
            @JsonProperty("types") List<RuntimeTypeDto> types,
            @JsonProperty("grades") List<RuntimeGradeDto> grades,
            @JsonProperty("areas") List<RuntimeAreaDto> areas
    ) {
    }

    public record RuntimeTypeDto(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("categoryId") int categoryId,
            @JsonProperty("categoryName") String categoryName,
            @JsonProperty("displayMaterialKey") String displayMaterialKey,
            @JsonProperty("spawnWeight") int spawnWeight,
            @JsonProperty("minBoxStars") int minBoxStars,
            @JsonProperty("maxBoxStars") int maxBoxStars,
            @JsonProperty("maxClaimsPerPlayerPerDay") Integer maxClaimsPerPlayerPerDay,
            @JsonProperty("announceMinItemStars") Integer announceMinItemStars
    ) {
    }

    public record RuntimeGradeDto(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("stars") int stars
    ) {
    }

    public record RuntimeAreaDto(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("world") String world,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("maxActive") int maxActive,
            @JsonProperty("spawnIntervalSeconds") int spawnIntervalSeconds,
            @JsonProperty("spawnChancePercent") double spawnChancePercent,
            @JsonProperty("minOnlinePlayers") int minOnlinePlayers,
            @JsonProperty("minDistanceFromPlayers") int minDistanceFromPlayers,
            @JsonProperty("lifetimeMinutes") int lifetimeMinutes,
            @JsonProperty("excludedRegionIds") List<String> excludedRegionIds,
            @JsonProperty("allowedTypeIds") List<Integer> allowedTypeIds,
            @JsonProperty("activeCount") int activeCount,
            @JsonProperty("createdByUserId") Integer createdByUserId
    ) {
    }

    // ===== Spawns =====

    public record SpawnDto(
            @JsonProperty("id") int id,
            @JsonProperty("token") UUID token,
            @JsonProperty("lootboxTypeId") int lootboxTypeId,
            @JsonProperty("lootboxTypeName") String lootboxTypeName,
            @JsonProperty("categoryName") String categoryName,
            @JsonProperty("boxGradeId") int boxGradeId,
            @JsonProperty("boxGradeName") String boxGradeName,
            @JsonProperty("boxStars") int boxStars,
            @JsonProperty("boxLabel") String boxLabel,
            @JsonProperty("spawnAreaId") Integer spawnAreaId,
            @JsonProperty("spawnAreaName") String spawnAreaName,
            @JsonProperty("world") String world,
            @JsonProperty("x") int x,
            @JsonProperty("y") int y,
            @JsonProperty("z") int z,
            @JsonProperty("status") String status,
            @JsonProperty("spawnedAt") OffsetDateTime spawnedAt,
            @JsonProperty("expiresAt") OffsetDateTime expiresAt
    ) {
    }

    public record SpawnRequestDto(
            @JsonProperty("areaId") int areaId,
            @JsonProperty("world") String world,
            @JsonProperty("x") int x,
            @JsonProperty("y") int y,
            @JsonProperty("z") int z,
            @JsonProperty("serverId") String serverId
    ) {
    }

    public record AdminSpawnRequestDto(
            @JsonProperty("typeId") int typeId,
            @JsonProperty("boxStars") Integer boxStars,
            @JsonProperty("world") String world,
            @JsonProperty("x") int x,
            @JsonProperty("y") int y,
            @JsonProperty("z") int z,
            @JsonProperty("serverId") String serverId
    ) {
    }

    // ===== Claims =====

    public record ClaimRequestDto(
            @JsonProperty("token") UUID token,
            @JsonProperty("userId") int userId,
            @JsonProperty("idempotencyKey") String idempotencyKey
    ) {
    }

    public record AdminGiveRequestDto(
            @JsonProperty("userId") int userId,
            @JsonProperty("typeId") int typeId,
            @JsonProperty("boxStars") Integer boxStars,
            @JsonProperty("idempotencyKey") String idempotencyKey
    ) {
    }

    public record ClaimEnchantmentDto(
            @JsonProperty("definitionId") int definitionId,
            @JsonProperty("key") String key,
            @JsonProperty("isCustom") boolean isCustom,
            @JsonProperty("level") int level
    ) {
    }

    public record ClaimResultDto(
            @JsonProperty("claimId") int claimId,
            @JsonProperty("replay") boolean replay,
            @JsonProperty("userId") int userId,
            @JsonProperty("lootboxSpawnId") Integer lootboxSpawnId,
            @JsonProperty("lootboxTypeId") int lootboxTypeId,
            @JsonProperty("boxStars") int boxStars,
            @JsonProperty("boxLabel") String boxLabel,
            @JsonProperty("itemInstanceId") Long itemInstanceId,
            @JsonProperty("itemBlueprintId") int itemBlueprintId,
            @JsonProperty("itemName") String itemName,
            @JsonProperty("itemGradeId") Integer itemGradeId,
            @JsonProperty("itemGradeStars") Integer itemGradeStars,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("isSpecial") boolean isSpecial,
            @JsonProperty("enchantments") List<ClaimEnchantmentDto> enchantments,
            @JsonProperty("announce") boolean announce,
            @JsonProperty("claimedAt") OffsetDateTime claimedAt,
            @JsonProperty("deliveredAt") OffsetDateTime deliveredAt
    ) {
    }

    public record DeliveredRequestDto(
            @JsonProperty("method") String method,
            @JsonProperty("note") String note,
            @JsonProperty("userId") Integer userId
    ) {
    }

    // ===== Token items (Phase 5) =====

    public record TokenIssueRequestDto(
            @JsonProperty("userId") int userId,
            @JsonProperty("typeId") int typeId,
            @JsonProperty("boxStars") Integer boxStars,
            @JsonProperty("quantity") int quantity,
            @JsonProperty("reason") String reason,
            @JsonProperty("idempotencyKey") String idempotencyKey
    ) {
    }

    public record TokenIssueResultDto(
            @JsonProperty("replay") boolean replay,
            @JsonProperty("tokens") List<TokenDto> tokens
    ) {
    }

    public record TokenDto(
            @JsonProperty("id") int id,
            @JsonProperty("token") UUID token,
            @JsonProperty("lootboxTypeId") int lootboxTypeId,
            @JsonProperty("lootboxTypeName") String lootboxTypeName,
            @JsonProperty("categoryName") String categoryName,
            @JsonProperty("boxStars") int boxStars,
            @JsonProperty("boxLabel") String boxLabel,
            @JsonProperty("status") String status,
            @JsonProperty("reason") String reason,
            @JsonProperty("issuedToUserId") Integer issuedToUserId
    ) {
    }

    public record TokenRedeemRequestDto(
            @JsonProperty("userId") int userId,
            @JsonProperty("idempotencyKey") String idempotencyKey
    ) {
    }

    // ===== POST api/LootboxSpawns/{id}/pickup (DESIGN.md §3.8) =====

    public record PickupRequestDto(
            @JsonProperty("token") UUID token,
            @JsonProperty("userId") int userId
    ) {
    }

    public record PickupResultDto(
            @JsonProperty("replay") boolean replay,
            @JsonProperty("spawnId") int spawnId,
            @JsonProperty("lootboxToken") TokenDto lootboxToken
    ) {
    }

    // ===== POST api/LootboxTokens/status =====

    public record TokenStatusRequestDto(
            @JsonProperty("tokens") List<UUID> tokens
    ) {
    }

    public record TokenStatusDto(
            @JsonProperty("token") UUID token,
            @JsonProperty("status") String status
    ) {
    }

    public record TokensDeliveredRequestDto(
            @JsonProperty("userId") int userId,
            @JsonProperty("tokens") List<UUID> tokens
    ) {
    }

    // ===== In-game areas =====

    public record InGameAreaCreateDto(
            @JsonProperty("name") String name,
            @JsonProperty("world") String world,
            @JsonProperty("wgRegionId") String wgRegionId
    ) {
    }

    /** The created area as the API's admin {@code LootboxSpawnAreaDto} returns it (excluded regions as CSV). */
    public record SpawnAreaDto(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("world") String world,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("maxActive") int maxActive,
            @JsonProperty("spawnIntervalSeconds") int spawnIntervalSeconds,
            @JsonProperty("spawnChancePercent") double spawnChancePercent,
            @JsonProperty("minOnlinePlayers") int minOnlinePlayers,
            @JsonProperty("minDistanceFromPlayers") int minDistanceFromPlayers,
            @JsonProperty("lifetimeMinutes") int lifetimeMinutes,
            @JsonProperty("excludedRegionIds") String excludedRegionIds,
            @JsonProperty("createdByUserId") Integer createdByUserId
    ) {
    }

    public record InGameAreaDeleteResultDto(
            @JsonProperty("id") int id,
            @JsonProperty("name") String name,
            @JsonProperty("world") String world,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("removedSpawnIds") List<Integer> removedSpawnIds
    ) {
    }

    // ===== GET api/LootboxTypes/{id}/odds =====

    public record OddsDto(
            @JsonProperty("lootboxTypeId") int lootboxTypeId,
            @JsonProperty("lootboxTypeName") String lootboxTypeName,
            @JsonProperty("boxStars") int boxStars,
            @JsonProperty("normalRollPercent") double normalRollPercent,
            @JsonProperty("itemGrades") List<GradeOddsDto> itemGrades,
            @JsonProperty("items") List<ItemOddsDto> items,
            @JsonProperty("specials") List<SpecialOddsDto> specials,
            @JsonProperty("enchantments") List<EnchantOddsDto> enchantments
    ) {
    }

    public record GradeOddsDto(
            @JsonProperty("name") String name,
            @JsonProperty("stars") int stars,
            @JsonProperty("percent") double percent,
            @JsonProperty("itemCount") Integer itemCount
    ) {
    }

    public record ItemOddsDto(
            @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
            @JsonProperty("name") String name,
            @JsonProperty("stars") int stars,
            @JsonProperty("percent") double percent,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("rollsEnchantments") Boolean rollsEnchantments
    ) {
    }

    public record EnchantOddsDto(
            @JsonProperty("enchantmentDefinitionId") int enchantmentDefinitionId,
            @JsonProperty("key") String key,
            @JsonProperty("isCustom") boolean isCustom,
            @JsonProperty("hitPercent") double hitPercent,
            @JsonProperty("levelsByGrade") List<LevelRangeDto> levelsByGrade
    ) {
    }

    public record LevelRangeDto(
            @JsonProperty("stars") int stars,
            @JsonProperty("minLevel") Integer minLevel,
            @JsonProperty("maxLevel") Integer maxLevel
    ) {
    }

    public record SpecialOddsDto(
            @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
            @JsonProperty("name") String name,
            @JsonProperty("percent") double percent
    ) {
    }

    // ===== LootboxWorldChanged player notification payload (DESIGN.md §3.9) =====

    public record WorldChangedNotificationDto(
            @JsonProperty("removedSpawnIds") List<Integer> removedSpawnIds,
            @JsonProperty("revokedTokens") List<UUID> revokedTokens
    ) {
    }

    /** A 409/429 body: {@code {code, message}} plus, for the daily limit, {@code scope, limit, resetsAt}. */
    public record RejectionDto(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("scope") String scope,
            @JsonProperty("limit") Integer limit,
            @JsonProperty("resetsAt") OffsetDateTime resetsAt
    ) {
    }
}
