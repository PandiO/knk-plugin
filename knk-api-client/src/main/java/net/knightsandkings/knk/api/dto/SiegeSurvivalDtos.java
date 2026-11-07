package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.knightsandkings.knk.api.dto.SiegeDtos.ReadinessIssueDto;
import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wire DTOs for knk-web-api's Siege Survival phase 1 payloads (PandiO/knk-workspace#41, link S4). Mirrors
 * {@code Dtos/SiegeSurvivalDtos.cs}: the frozen match configuration ({@code GET
 * api/siege-survival/scenarios/{id}/configuration?lobbyId=&difficultyProfileId=}), readiness, the per-round preview,
 * the scenario summaries, and the result/checkpoint contracts of phases 2–5. JSON is camelCase; enums arrive as the
 * API's PascalCase names ({@code "OwnInventory"}, {@code "Kits"}, {@code "MatchProgression"}; {@code "Inherit"},
 * {@code "On"}, {@code "Off"}; {@code "Linear"}, {@code "Logarithmic"}, {@code "Exponential"}, {@code "Hybrid"};
 * {@code "All"}, {@code "Any"}; {@code "Standard"}, {@code "Allow"}, {@code "Deny"}; {@code "Recurring"},
 * {@code "Explicit"}; {@code "Items"}, {@code "EnchantedBooks"}; {@code "Spawn"}, {@code "Lane"}; the end reasons
 * {@code "TeamWipe"} … {@code "TechnicalLimit"}) and are kept as strings here; link S5 maps them onto knk-core's
 * survival records (upper snake case). Boxed types throughout, so a missing field maps to a default in the mapper
 * instead of failing the whole payload.
 */
public final class SiegeSurvivalDtos {
    private SiegeSurvivalDtos() {}

    // ---- Summaries ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioSummaryDto(
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("siegeScenarioName") String siegeScenarioName,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("equipmentMode") String equipmentMode,
            @JsonProperty("difficultyProfileCount") Integer difficultyProfileCount
    ) {}

    // ---- Readiness ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadinessDto(
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("siegeLobbyId") Integer siegeLobbyId,
            @JsonProperty("difficultyProfileId") Integer difficultyProfileId,
            @JsonProperty("isReady") Boolean isReady,
            @JsonProperty("errors") List<ReadinessIssueDto> errors,
            @JsonProperty("warnings") List<ReadinessIssueDto> warnings
    ) {}

    // ---- Frozen configuration ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigurationDto(
            @JsonProperty("generatedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime generatedAt,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("siegeScenarioName") String siegeScenarioName,
            @JsonProperty("siegeLobbyId") Integer siegeLobbyId,
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("minPlayers") Integer minPlayers,
            @JsonProperty("humanTeamId") Integer humanTeamId,
            @JsonProperty("npcTeamId") Integer npcTeamId,
            @JsonProperty("allowRecapture") Boolean allowRecapture,
            @JsonProperty("intermissionMillis") Integer intermissionMillis,
            @JsonProperty("equipment") EquipmentDto equipment,
            @JsonProperty("endConditions") EndConditionsDto endConditions,
            @JsonProperty("objectives") List<ObjectiveDto> objectives,
            @JsonProperty("gates") List<GateDto> gates,
            @JsonProperty("stuck") StuckDto stuck,
            @JsonProperty("spawn") SpawnDto spawn,
            @JsonProperty("difficulty") DifficultyDto difficulty,
            @JsonProperty("difficultyProfiles") List<DifficultyDto> difficultyProfiles,
            @JsonProperty("monsterTypes") List<MonsterTypeDto> monsterTypes,
            @JsonProperty("specialWaves") SpecialWavesDto specialWaves,
            @JsonProperty("killRewards") KillRewardsDto killRewards,
            @JsonProperty("purchasePoints") List<PurchasePointDto> purchasePoints,
            @JsonProperty("mysteryBoxPoints") List<MysteryBoxPointDto> mysteryBoxPoints,
            @JsonProperty("readiness") ReadinessDto readiness
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SelectedKitDto(
            @JsonProperty("kitId") Integer kitId,
            @JsonProperty("kitName") String kitName,
            @JsonProperty("sortOrder") Integer sortOrder
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EquipmentDto(
            @JsonProperty("mode") String mode,
            @JsonProperty("selectedKits") List<SelectedKitDto> selectedKits,
            @JsonProperty("defaultKitId") Integer defaultKitId,
            @JsonProperty("baselineLoadoutKitId") Integer baselineLoadoutKitId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndConditionsDto(
            @JsonProperty("scenarioAllCaptured") Boolean scenarioAllCaptured,
            @JsonProperty("lobbyOverride") String lobbyOverride,
            @JsonProperty("allCapturedEnabled") Boolean allCapturedEnabled,
            @JsonProperty("instaLoseObjectiveIds") List<Integer> instaLoseObjectiveIds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectiveDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("instaLose") Boolean instaLose,
            @JsonProperty("gateStructureId") Integer gateStructureId,
            @JsonProperty("captureLocation") LocationDto captureLocation,
            @JsonProperty("targetOverride") String targetOverride
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GateDto(
            @JsonProperty("gateStructureId") Integer gateStructureId,
            @JsonProperty("name") String name,
            @JsonProperty("isObjectiveGate") Boolean isObjectiveGate,
            @JsonProperty("targetOverride") String targetOverride
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StuckDto(
            @JsonProperty("stuckAfterMillis") Integer stuckAfterMillis,
            @JsonProperty("lastEnemiesCount") Integer lastEnemiesCount,
            @JsonProperty("respawnLimit") Integer respawnLimit,
            @JsonProperty("roundBackstopMillis") Integer roundBackstopMillis,
            @JsonProperty("meaningfulDistance") Double meaningfulDistance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpawnDto(
            @JsonProperty("favorNearPlayersPercent") Integer favorNearPlayersPercent,
            @JsonProperty("nearZoneCount") Integer nearZoneCount,
            @JsonProperty("zones") List<SpawnZoneDto> zones
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpawnZoneDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("name") String name,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("weight") Double weight,
            @JsonProperty("anchorLocationId") Integer anchorLocationId,
            @JsonProperty("anchorLocation") LocationDto anchorLocation,
            @JsonProperty("areaRadius") Double areaRadius,
            @JsonProperty("prerequisitePolicy") String prerequisitePolicy,
            @JsonProperty("prerequisiteObjectiveIds") List<Integer> prerequisiteObjectiveIds,
            @JsonProperty("minWave") Integer minWave,
            @JsonProperty("maxWave") Integer maxWave,
            @JsonProperty("forNormalWaves") Boolean forNormalWaves,
            @JsonProperty("forSpecialWaves") Boolean forSpecialWaves,
            @JsonProperty("laneAutoReroute") Boolean laneAutoReroute,
            @JsonProperty("points") List<SpawnZonePointDto> points
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpawnZonePointDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("survivalSpawnZoneId") Integer survivalSpawnZoneId,
            @JsonProperty("kind") String kind,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("name") String name,
            @JsonProperty("locationId") Integer locationId,
            @JsonProperty("location") LocationDto location
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScalingCurveDto(
            @JsonProperty("preset") String preset,
            @JsonProperty("base") Double base,
            @JsonProperty("step") Double step,
            @JsonProperty("multiplier") Double multiplier,
            @JsonProperty("switchRound") Integer switchRound
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatWeightsDto(
            @JsonProperty("health") Double health,
            @JsonProperty("walkingSpeed") Double walkingSpeed,
            @JsonProperty("meleeDamage") Double meleeDamage,
            @JsonProperty("rangedDamage") Double rangedDamage,
            @JsonProperty("attackSpeed") Double attackSpeed
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PerPlayerScalingDto(
            @JsonProperty("factor") Double factor,
            @JsonProperty("applyToStats") Boolean applyToStats
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifficultyDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("isDefault") Boolean isDefault,
            @JsonProperty("countCurve") ScalingCurveDto countCurve,
            @JsonProperty("statCurve") ScalingCurveDto statCurve,
            @JsonProperty("statWeights") StatWeightsDto statWeights,
            @JsonProperty("perPlayer") PerPlayerScalingDto perPlayer,
            @JsonProperty("maxAlive") Integer maxAlive,
            @JsonProperty("statCap") Double statCap,
            @JsonProperty("countCap") Integer countCap,
            @JsonProperty("repairRatePerSecond") Double repairRatePerSecond,
            @JsonProperty("gateDamageFactor") Double gateDamageFactor,
            @JsonProperty("captureContributionWeight") Double captureContributionWeight,
            @JsonProperty("spawnIntervalMillis") Integer spawnIntervalMillis,
            @JsonProperty("aggroRadius") Double aggroRadius,
            @JsonProperty("leashDistance") Double leashDistance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MonsterTypeDto(
            @JsonProperty("entityType") String entityType,
            @JsonProperty("weight") Double weight,
            @JsonProperty("unlockRound") Integer unlockRound
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpecialWavesDto(
            @JsonProperty("profiles") List<SpecialWaveProfileDto> profiles,
            @JsonProperty("entries") List<SpecialWaveEntryDto> entries
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpecialWaveProfileDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("countOverride") Integer countOverride,
            @JsonProperty("countMultiplier") Double countMultiplier,
            @JsonProperty("additive") Boolean additive,
            @JsonProperty("monsterTypes") List<MonsterTypeDto> monsterTypes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpecialWaveEntryDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("survivalSpecialWaveProfileId") Integer survivalSpecialWaveProfileId,
            @JsonProperty("profileKey") String profileKey,
            @JsonProperty("kind") String kind,
            @JsonProperty("interval") Integer interval,
            @JsonProperty("startWave") Integer startWave,
            @JsonProperty("priority") Integer priority,
            @JsonProperty("wave") Integer wave,
            @JsonProperty("sortOrder") Integer sortOrder
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KillRewardsDto(
            @JsonProperty("coinsPerKill") BigDecimal coinsPerKill,
            @JsonProperty("xpPerKill") BigDecimal xpPerKill,
            @JsonProperty("matchCurrencyPerKill") Integer matchCurrencyPerKill,
            @JsonProperty("bonusMultiplier") BigDecimal bonusMultiplier
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchasePointDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("name") String name,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("locationId") Integer locationId,
            @JsonProperty("location") LocationDto location,
            @JsonProperty("interactionRange") Double interactionRange,
            @JsonProperty("offers") List<PurchaseOfferDto> offers
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseOfferDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("survivalPurchasePointId") Integer survivalPurchasePointId,
            @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
            @JsonProperty("itemBlueprintName") String itemBlueprintName,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("price") Integer price,
            @JsonProperty("sortOrder") Integer sortOrder
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxPointDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("location") LocationDto location,
            @JsonProperty("interactionRange") Double interactionRange,
            @JsonProperty("blockType") String blockType,
            @JsonProperty("rollPrice") Integer rollPrice,
            @JsonProperty("claimTimeoutMillis") Integer claimTimeoutMillis,
            @JsonProperty("pool") MysteryBoxPoolDto pool
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxPoolDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("name") String name,
            @JsonProperty("variant") String variant,
            @JsonProperty("totalChancePercent") BigDecimal totalChancePercent,
            @JsonProperty("entries") List<MysteryBoxEntryDto> entries
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxEntryDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("survivalMysteryBoxPoolId") Integer survivalMysteryBoxPoolId,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
            @JsonProperty("itemBlueprintName") String itemBlueprintName,
            @JsonProperty("enchantmentDefinitionId") Integer enchantmentDefinitionId,
            @JsonProperty("enchantmentKey") String enchantmentKey,
            @JsonProperty("enchantmentLevel") Integer enchantmentLevel,
            @JsonProperty("chancePercent") BigDecimal chancePercent,
            @JsonProperty("chanceBasisPoints") Integer chanceBasisPoints,
            @JsonProperty("enchantments") List<MysteryBoxEntryEnchantmentDto> enchantments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxEntryEnchantmentDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("enchantmentDefinitionId") Integer enchantmentDefinitionId,
            @JsonProperty("enchantmentKey") String enchantmentKey,
            @JsonProperty("level") Integer level,
            @JsonProperty("chancePercent") BigDecimal chancePercent,
            @JsonProperty("chanceBasisPoints") Integer chanceBasisPoints
    ) {}

    // ---- Match-scoped Mystery Box rolls (DESIGN §8a; link S7a, Dtos/SiegeSurvivalMysteryBoxDtos.cs) ----
    // POST api/siege-survival/matches/{matchId}/mystery-box/{pointId}/roll { rollId, userId, priceCharged } -> 201 (200 on a
    // repeat of the same rollId and body), .../mystery-box/rolls/{rollId}/claim { deliveryKind }, .../forfeit { reason },
    // GET .../mystery-box/rolls. Every answer is a MysteryBoxRollDto; a 409 carries { code, message } with the codes of
    // SiegeSurvivalMatchApiImpl. Enum strings: variant "Items" | "EnchantedBooks"; state "Rolled" | "Claimed" | "Forfeited";
    // deliveryKind "Inventory" | "Drop"; reason "Timeout" | "RollerDied" | "RollerLeft" | "MatchEnded".

    public record MysteryBoxRollRequestDto(
            @JsonProperty("rollId") String rollId,
            @JsonProperty("userId") Integer userId,
            @JsonProperty("priceCharged") Integer priceCharged
    ) {}

    public record MysteryBoxClaimRequestDto(
            @JsonProperty("deliveryKind") String deliveryKind
    ) {}

    public record MysteryBoxForfeitRequestDto(
            @JsonProperty("reason") String reason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxRolledEnchantmentDto(
            @JsonProperty("enchantmentDefinitionId") Integer enchantmentDefinitionId,
            @JsonProperty("level") Integer level
    ) {}

    /**
     * One stored roll: {@code entry} is the picked pool entry as served at roll time (a snapshot), {@code rolledEnchantments}
     * the item entry's enchantments that passed their own chance (empty for a book: the book's enchantment is the entry's
     * {@code enchantmentDefinitionId}/{@code enchantmentLevel}), {@code reel} the carousel's entry ids drawn with the real
     * odds, the last one being the result.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MysteryBoxRollDto(
            @JsonProperty("rollId") String rollId,
            @JsonProperty("siegeMatchId") Integer siegeMatchId,
            @JsonProperty("pointId") Integer pointId,
            @JsonProperty("userId") Integer userId,
            @JsonProperty("variant") String variant,
            @JsonProperty("entry") MysteryBoxEntryDto entry,
            @JsonProperty("rolledEnchantments") List<MysteryBoxRolledEnchantmentDto> rolledEnchantments,
            @JsonProperty("reel") List<Integer> reel,
            @JsonProperty("priceCharged") Integer priceCharged,
            @JsonProperty("state") String state,
            @JsonProperty("rolledAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime rolledAt,
            @JsonProperty("claimedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime claimedAt,
            @JsonProperty("deliveryKind") String deliveryKind,
            @JsonProperty("forfeitedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime forfeitedAt,
            @JsonProperty("forfeitReason") String forfeitReason
    ) {}

    // ---- Editor preview (DESIGN §11) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PreviewDto(
            @JsonProperty("difficultyProfileId") Integer difficultyProfileId,
            @JsonProperty("players") Integer players,
            @JsonProperty("rounds") List<RoundPreviewDto> rounds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoundPreviewDto(
            @JsonProperty("round") Integer round,
            @JsonProperty("enemyCount") Integer enemyCount,
            @JsonProperty("curveValue") Double curveValue,
            @JsonProperty("health") Double health,
            @JsonProperty("walkingSpeed") Double walkingSpeed,
            @JsonProperty("meleeDamage") Double meleeDamage,
            @JsonProperty("rangedDamage") Double rangedDamage,
            @JsonProperty("attackSpeed") Double attackSpeed
    ) {}

    // ---- Result and checkpoint contracts (phases 2–5; no endpoint accepts them yet) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchResultDto(
            @JsonProperty("siegeMatchId") Integer siegeMatchId,
            @JsonProperty("siegeLobbyId") Integer siegeLobbyId,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("difficultyProfileId") Integer difficultyProfileId,
            @JsonProperty("configurationGeneratedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime configurationGeneratedAt,
            @JsonProperty("startedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime startedAt,
            @JsonProperty("endedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime endedAt,
            @JsonProperty("endReason") String endReason,
            @JsonProperty("reachedWave") Integer reachedWave,
            @JsonProperty("completedWave") Integer completedWave,
            @JsonProperty("earnedKills") Integer earnedKills,
            @JsonProperty("systemKills") Integer systemKills,
            @JsonProperty("players") List<PlayerResultDto> players,
            @JsonProperty("settlement") SettlementDto settlement
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerResultDto(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("presentAtEnd") Boolean presentAtEnd,
            @JsonProperty("leftAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime leftAt,
            @JsonProperty("kitId") Integer kitId,
            @JsonProperty("kills") Integer kills,
            @JsonProperty("bonusKills") Integer bonusKills,
            @JsonProperty("deaths") Integer deaths,
            @JsonProperty("repairWork") Double repairWork,
            @JsonProperty("matchCurrencyEarned") Long matchCurrencyEarned,
            @JsonProperty("matchCurrencySpent") Long matchCurrencySpent,
            @JsonProperty("finalMatchBalance") Long finalMatchBalance,
            @JsonProperty("coinsAccrued") BigDecimal coinsAccrued,
            @JsonProperty("xpAccrued") BigDecimal xpAccrued,
            @JsonProperty("coinsSettled") Integer coinsSettled,
            @JsonProperty("xpSettled") Integer xpSettled
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SettlementDto(
            @JsonProperty("settlesRewards") Boolean settlesRewards,
            @JsonProperty("totalCoins") Integer totalCoins,
            @JsonProperty("totalXp") Integer totalXp,
            @JsonProperty("ledgerKey") String ledgerKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchCheckpointDto(
            @JsonProperty("siegeMatchId") Integer siegeMatchId,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("difficultyProfileId") Integer difficultyProfileId,
            @JsonProperty("capturedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime capturedAt,
            @JsonProperty("substate") String substate,
            @JsonProperty("wave") Integer wave,
            @JsonProperty("completedWave") Integer completedWave,
            @JsonProperty("specialProfileKey") String specialProfileKey,
            @JsonProperty("aliveEnemies") Integer aliveEnemies,
            @JsonProperty("queuedEnemies") Integer queuedEnemies,
            @JsonProperty("npcHeldObjectiveIds") List<Integer> npcHeldObjectiveIds,
            @JsonProperty("activeSpawnZoneIds") List<Integer> activeSpawnZoneIds,
            @JsonProperty("doorRepairs") List<DoorRepairCheckpointDto> doorRepairs,
            @JsonProperty("players") List<PlayerCheckpointDto> players
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoorRepairCheckpointDto(
            @JsonProperty("gateDoorId") Integer gateDoorId,
            @JsonProperty("destroyed") Boolean destroyed,
            @JsonProperty("repairWork") Double repairWork,
            @JsonProperty("repairTarget") Double repairTarget,
            @JsonProperty("repairers") Integer repairers
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlayerCheckpointDto(
            @JsonProperty("userId") Integer userId,
            @JsonProperty("ghost") Boolean ghost,
            @JsonProperty("kitId") Integer kitId,
            @JsonProperty("matchBalance") Long matchBalance,
            @JsonProperty("kills") Integer kills,
            @JsonProperty("deaths") Integer deaths,
            @JsonProperty("pendingMysteryBoxRollId") String pendingMysteryBoxRollId
    ) {}
}
