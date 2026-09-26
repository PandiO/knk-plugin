package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.LootboxDtos;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxAreaDeleteResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxGrade;
import net.knightsandkings.knk.core.lootbox.KnkLootboxOdds;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.KnkLootboxType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Lootbox runtime DTOs to the knk-core records (docs/specs/lootboxes/DESIGN.md §3.3). Null-safe on lists. */
public final class LootboxMapper {

    private LootboxMapper() {
    }

    public static KnkLootboxRuntimeConfig toCore(LootboxDtos.RuntimeConfigDto dto) {
        if (dto == null) {
            return KnkLootboxRuntimeConfig.empty();
        }
        return new KnkLootboxRuntimeConfig(
                dto.enabled(),
                dto.globalMaxActive(),
                dto.maxClaimsPerPlayerPerDay(),
                dto.announceMinItemStars(),
                dto.announceSpawnMinBoxStars(),
                dto.dropAnnouncementTemplate(),
                dto.spawnAnnouncementTemplate(),
                instant(dto.serverTimeUtc()),
                map(dto.types(), LootboxMapper::toCore),
                map(dto.grades(), g -> new KnkLootboxGrade(g.id(), g.name(), g.stars())),
                map(dto.areas(), LootboxMapper::toCore));
    }

    public static KnkLootboxType toCore(LootboxDtos.RuntimeTypeDto dto) {
        return new KnkLootboxType(dto.id(), dto.name(), dto.categoryId(), dto.categoryName(), dto.displayMaterialKey(),
                dto.spawnWeight(), dto.minBoxStars(), dto.maxBoxStars(), dto.maxClaimsPerPlayerPerDay(), dto.announceMinItemStars());
    }

    public static KnkLootboxArea toCore(LootboxDtos.RuntimeAreaDto dto) {
        return new KnkLootboxArea(dto.id(), dto.name(), dto.world(), dto.wgRegionId(), dto.enabled(), dto.maxActive(),
                dto.spawnIntervalSeconds(), dto.spawnChancePercent(), dto.minOnlinePlayers(), dto.minDistanceFromPlayers(),
                dto.lifetimeMinutes(), dto.excludedRegionIds(), dto.allowedTypeIds(), dto.activeCount(), dto.createdByUserId());
    }

    /** The admin area DTO an in-game create returns; a new area has no boxes yet and allows every type. */
    public static KnkLootboxArea toCore(LootboxDtos.SpawnAreaDto dto) {
        if (dto == null) {
            return null;
        }
        return new KnkLootboxArea(dto.id(), dto.name(), dto.world(), dto.wgRegionId(), dto.enabled(), dto.maxActive(),
                dto.spawnIntervalSeconds(), dto.spawnChancePercent(), dto.minOnlinePlayers(), dto.minDistanceFromPlayers(),
                dto.lifetimeMinutes(), splitCsv(dto.excludedRegionIds()), List.of(), 0, dto.createdByUserId());
    }

    public static KnkLootboxSpawn toCore(LootboxDtos.SpawnDto dto) {
        if (dto == null) {
            return null;
        }
        return new KnkLootboxSpawn(dto.id(), dto.token(), dto.lootboxTypeId(), dto.lootboxTypeName(), dto.categoryName(),
                dto.boxGradeId(), dto.boxGradeName(), dto.boxStars(), dto.boxLabel(), dto.spawnAreaId(), dto.spawnAreaName(),
                dto.world(), dto.x(), dto.y(), dto.z(), dto.status(), instant(dto.spawnedAt()), instant(dto.expiresAt()));
    }

    public static List<KnkLootboxSpawn> toSpawns(List<LootboxDtos.SpawnDto> dtos) {
        return map(dtos, LootboxMapper::toCore);
    }

    public static KnkLootboxClaimResult toCore(LootboxDtos.ClaimResultDto dto) {
        if (dto == null) {
            return null;
        }
        return new KnkLootboxClaimResult(dto.claimId(), dto.replay(), dto.userId(), dto.lootboxSpawnId(), dto.lootboxTypeId(),
                dto.boxStars(), dto.boxLabel(), dto.itemInstanceId(), dto.itemBlueprintId(), dto.itemName(), dto.itemGradeId(),
                dto.itemGradeStars(), dto.quantity(), dto.isSpecial(),
                map(dto.enchantments(), e -> new KnkLootboxClaimEnchantment(e.definitionId(), e.key(), e.isCustom(), e.level())),
                dto.announce(), instant(dto.claimedAt()), instant(dto.deliveredAt()));
    }

    public static List<KnkLootboxClaimResult> toClaims(List<LootboxDtos.ClaimResultDto> dtos) {
        return map(dtos, LootboxMapper::toCore);
    }

    public static KnkLootboxAreaDeleteResult toCore(LootboxDtos.InGameAreaDeleteResultDto dto) {
        if (dto == null) {
            return null;
        }
        return new KnkLootboxAreaDeleteResult(dto.id(), dto.name(), dto.world(), dto.wgRegionId(), dto.removedSpawnIds());
    }

    public static KnkLootboxOdds toCore(LootboxDtos.OddsDto dto) {
        if (dto == null) {
            return null;
        }
        return new KnkLootboxOdds(dto.lootboxTypeId(), dto.lootboxTypeName(), dto.boxStars(), dto.normalRollPercent(),
                map(dto.itemGrades(), g -> new KnkLootboxOdds.Grade(g.name(), g.stars(), g.percent(), g.itemCount())),
                map(dto.items(), i -> new KnkLootboxOdds.Item(i.name(), i.stars(), i.percent())),
                map(dto.specials(), s -> new KnkLootboxOdds.Special(s.name(), s.percent())));
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) {
        if (source == null) {
            return List.of();
        }
        return source.stream().filter(Objects::nonNull).map(mapper).filter(Objects::nonNull).toList();
    }
}
