package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.KitAvailabilityDto;
import net.knightsandkings.knk.api.dto.KitClaimResultDto;
import net.knightsandkings.knk.api.dto.KitContentSlotDto;
import net.knightsandkings.knk.api.dto.KitDto;
import net.knightsandkings.knk.api.dto.KitPurchaseResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import net.knightsandkings.knk.core.domain.item.KnkKitPurchaseResult;

import java.util.Collections;
import java.util.List;

public final class KitsMapper {
    private KitsMapper() {}

    public static KnkKit toCore(KitDto dto) {
        if (dto == null) return null;

        return new KnkKit(
                dto.id(),
                dto.name(),
                dto.description(),
                dto.helmetId(),
                dto.chestplateId(),
                dto.leggingsId(),
                dto.bootsId(),
                dto.shieldId(),
                dto.handId(),
                toCoreContents(dto.contents()),
                dto.minTitleBracketId(),
                dto.requiredPermissionGroupId(),
                dto.requiredPermissionNode(),
                Boolean.TRUE.equals(dto.grantOnFirstJoin()),
                dto.cooldownSeconds() != null ? dto.cooldownSeconds() : 0,
                dto.costAmount(),
                dto.costCurrency(),
                Boolean.TRUE.equals(dto.isSinglePurchasePremium()),
                dto.premiumPriceGems()
        );
    }

    public static KnkKitContent toCore(KitContentSlotDto dto) {
        if (dto == null) return null;
        return new KnkKitContent(
                dto.slotIndex() != null ? dto.slotIndex() : 0,
                dto.itemBlueprintId() != null ? dto.itemBlueprintId() : 0,
                dto.quantity() != null ? dto.quantity() : 1
        );
    }

    public static KnkKitAvailability toCore(KitAvailabilityDto dto) {
        if (dto == null) return null;

        return new KnkKitAvailability(
                dto.kitId() != null ? dto.kitId() : 0,
                dto.name(),
                dto.description(),
                Boolean.TRUE.equals(dto.canClaim()),
                dto.denialReason(),
                dto.cooldownExpiresAt(),
                Boolean.TRUE.equals(dto.isPurchased()),
                dto.costAmount(),
                dto.costCurrency(),
                Boolean.TRUE.equals(dto.isSinglePurchasePremium()),
                dto.premiumPriceGems()
        );
    }

    public static KnkKitClaimResult toCore(KitClaimResultDto dto) {
        if (dto == null) return null;

        return new KnkKitClaimResult(
                dto.kitId() != null ? dto.kitId() : 0,
                dto.helmetId(),
                dto.chestplateId(),
                dto.leggingsId(),
                dto.bootsId(),
                dto.shieldId(),
                dto.handId(),
                toCoreContents(dto.contents())
        );
    }

    public static KnkKitPurchaseResult toCore(KitPurchaseResultDto dto) {
        if (dto == null) return null;

        return new KnkKitPurchaseResult(
                dto.kitId() != null ? dto.kitId() : 0,
                dto.userId() != null ? dto.userId() : 0,
                dto.gemsPaid() != null ? dto.gemsPaid() : 0,
                dto.purchasedAt()
        );
    }

    public static List<KnkKitAvailability> mapAvailabilityList(List<KitAvailabilityDto> dtos) {
        if (dtos == null) return Collections.emptyList();
        return dtos.stream().map(KitsMapper::toCore).toList();
    }

    public static List<KnkKitClaimResult> mapClaimResultList(List<KitClaimResultDto> dtos) {
        if (dtos == null) return Collections.emptyList();
        return dtos.stream().map(KitsMapper::toCore).toList();
    }

    public static Page<KnkKit> mapPage(net.knightsandkings.knk.api.dto.PagedResultDto<KitDto> result) {
        if (result == null) {
            return new Page<>(Collections.emptyList(), 0, 1, 10);
        }

        List<KnkKit> items = result.items() != null
                ? result.items().stream().map(KitsMapper::toCore).toList()
                : Collections.emptyList();

        int totalCount = result.totalCount() != null ? result.totalCount() : items.size();
        int pageNumber = result.pageNumber() != null ? result.pageNumber() : 1;
        int pageSize = result.pageSize() != null ? result.pageSize() : Math.max(items.size(), 1);

        return new Page<>(items, totalCount, pageNumber, pageSize);
    }

    private static List<KnkKitContent> toCoreContents(List<KitContentSlotDto> dtos) {
        if (dtos == null) return Collections.emptyList();
        return dtos.stream().map(KitsMapper::toCore).toList();
    }
}
