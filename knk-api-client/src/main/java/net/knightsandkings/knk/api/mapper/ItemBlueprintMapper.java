package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.DomainSummaryDto;
import net.knightsandkings.knk.api.dto.GradeDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintDefaultEnchantmentDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintListDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintListDtoPagedResultDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintOriginDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintReadDto;
import net.knightsandkings.knk.api.dto.ItemBlueprintTagDto;
import net.knightsandkings.knk.api.dto.MinecraftMaterialRefDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintOrigin;
import net.knightsandkings.knk.core.domain.item.KnkTag;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;

import java.util.Collections;
import java.util.List;

public final class ItemBlueprintMapper {
    private ItemBlueprintMapper() {}

    public static KnkItemBlueprint toCore(ItemBlueprintReadDto dto) {
        if (dto == null) return null;

        List<KnkItemBlueprintDefaultEnchantment> enchantments = dto.defaultEnchantments() == null
                ? Collections.emptyList()
                : dto.defaultEnchantments().stream().map(ItemBlueprintMapper::toCore).toList();

        String iconNamespaceKey = dto.iconMaterialRef() != null ? dto.iconMaterialRef().namespaceKey() : null;

        List<KnkTag> tags = dto.tags() == null
                ? Collections.emptyList()
                : dto.tags().stream().map(ItemBlueprintMapper::toCore).toList();

        List<KnkItemBlueprintOrigin> origins = dto.origins() == null
                ? Collections.emptyList()
                : dto.origins().stream().map(ItemBlueprintMapper::toCore).toList();

        return new KnkItemBlueprint(
                dto.id(),
                dto.name(),
                dto.description(),
                dto.iconMaterialRefId(),
                iconNamespaceKey,
                dto.defaultDisplayName(),
                dto.defaultDisplayDescription(),
                dto.defaultQuantity(),
                dto.maxStackSize(),
                enchantments,
                enchantments.size(),
                toCore(dto.grade()),
                tags,
                origins
        );
    }

    public static KnkItemBlueprint toCore(ItemBlueprintListDto dto) {
        if (dto == null) return null;

        KnkGrade grade = (dto.gradeId() == null && dto.gradeName() == null)
                ? null
                : new KnkGrade(dto.gradeId(), dto.gradeName(), null);

        return new KnkItemBlueprint(
                dto.id(),
                dto.name(),
                dto.description(),
                dto.iconMaterialRefId(),
                dto.iconNamespaceKey(),
                dto.defaultDisplayName(),
                null,
                null,
                null,
                Collections.emptyList(),
                dto.defaultEnchantmentsCount(),
                grade,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public static KnkGrade toCore(GradeDto dto) {
        if (dto == null) return null;
        return new KnkGrade(dto.id(), dto.name(), dto.stars());
    }

    public static KnkTag toCore(net.knightsandkings.knk.api.dto.TagDto dto) {
        if (dto == null) return null;
        return new KnkTag(dto.id(), dto.name());
    }

    public static KnkTag toCore(ItemBlueprintTagDto dto) {
        if (dto == null) return null;
        return dto.tag() != null ? toCore(dto.tag()) : new KnkTag(dto.tagId(), null);
    }

    public static KnkItemBlueprintOrigin toCore(ItemBlueprintOriginDto dto) {
        if (dto == null) return null;

        DomainSummaryDto domain = dto.domain();
        return new KnkItemBlueprintOrigin(
                dto.domainId(),
                domain != null ? domain.name() : null,
                domain != null ? domain.domainType() : null,
                dto.sequenceNumber()
        );
    }

    public static KnkItemBlueprintDefaultEnchantment toCore(ItemBlueprintDefaultEnchantmentDto dto) {
        if (dto == null) return null;

        return new KnkItemBlueprintDefaultEnchantment(
                dto.itemBlueprintId(),
                dto.enchantmentDefinitionId(),
                dto.level(),
                dto.enchantmentDefinition() != null ? dto.enchantmentDefinition().key() : null,
                dto.enchantmentDefinition() != null ? dto.enchantmentDefinition().displayName() : null,
                dto.enchantmentDefinition() != null ? dto.enchantmentDefinition().maxLevel() : null,
                dto.enchantmentDefinition() != null ? dto.enchantmentDefinition().isCustom() : null
        );
    }

    public static Page<KnkItemBlueprint> mapPagedList(ItemBlueprintListDtoPagedResultDto result) {
        if (result == null) {
            return new Page<>(Collections.emptyList(), 0, 1, 10);
        }

        List<KnkItemBlueprint> items = result.items() != null
                ? result.items().stream().map(ItemBlueprintMapper::toCore).toList()
                : Collections.emptyList();

        int totalCount = result.totalCount() != null ? Math.toIntExact(result.totalCount()) : items.size();
        int pageNumber = result.pageNumber() != null ? result.pageNumber() : 1;
        int pageSize = result.pageSize() != null ? result.pageSize() : Math.max(items.size(), 1);

        return new Page<>(items, totalCount, pageNumber, pageSize);
    }

    public static KnkMinecraftMaterialRef toCore(MinecraftMaterialRefDto dto) {
        if (dto == null) return null;

        return new KnkMinecraftMaterialRef(
                dto.id(),
                dto.namespaceKey(),
                dto.legacyName(),
                dto.category(),
                dto.iconUrl()
        );
    }
}
