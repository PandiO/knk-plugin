package net.knightsandkings.knk.api.mapper;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.api.dto.ShopDtos;
import net.knightsandkings.knk.core.production.shop.ShopOffer;
import net.knightsandkings.knk.core.production.shop.ShopOfferItem;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.production.shop.ShopQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.core.production.shop.ShopSellQuote;
import net.knightsandkings.knk.core.production.shop.ShopSellRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellResult;

/** {@link ShopDtos} to and from the knk-core shop records (PandiO/knk-workspace#687). */
public final class ShopMapper {

    private ShopMapper() {
    }

    public static ShopOffer toOffer(ShopDtos.OfferDto dto, int shopId) {
        if (dto == null) {
            return new ShopOffer(shopId, null, null, 1, null, null, null, false, false, List.of());
        }
        List<ShopOfferItem> items = new ArrayList<>();
        for (ShopDtos.OfferItemDto item : dto.items() == null ? List.<ShopDtos.OfferItemDto>of() : dto.items()) {
            if (item == null || item.itemBlueprintId() == null || item.unitPrice() == null) {
                continue; // an item without a price is not on offer
            }
            items.add(new ShopOfferItem(item.catalogueEntryId(), item.itemBlueprintId(),
                    item.itemBlueprintName() != null ? item.itemBlueprintName() : "Item #" + item.itemBlueprintId(),
                    item.itemInstanceId(), item.unitPrice(), item.priceSource(), item.stock(),
                    Boolean.TRUE.equals(item.soldOut()), Boolean.TRUE.equals(item.pinned()),
                    item.sortOrder() != null ? item.sortOrder() : Integer.MAX_VALUE, item.enchantments()));
        }
        return new ShopOffer(dto.shopId() != null ? dto.shopId() : shopId,
                dto.shopName() != null ? dto.shopName() : "Shop #" + shopId, dto.kind(),
                dto.level() != null ? dto.level() : 1, dto.shopTypeName(), dto.townId(), dto.townName(),
                Boolean.TRUE.equals(dto.hideSoldOut()), Boolean.TRUE.equals(dto.buysFromPlayers()), items);
    }

    public static ShopDtos.PurchaseRequestDto toDto(ShopPurchaseRequest request) {
        return new ShopDtos.PurchaseRequestDto(request.userId(), request.item().catalogueEntryId(),
                request.item().itemBlueprintId(), request.item().itemInstanceId(), request.amount(),
                request.expectedUnitPrice(), request.confirmed(),
                request.deliverTo() != null ? request.deliverTo().apiName() : null, request.idempotencyKey());
    }

    public static ShopQuote toQuote(ShopDtos.QuoteDto dto, int shopId) {
        return new ShopQuote(dto.shopId() != null ? dto.shopId() : shopId, dto.catalogueEntryId(),
                dto.itemBlueprintId() != null ? dto.itemBlueprintId() : 0, dto.itemBlueprintName(), dto.itemInstanceId(),
                orZero(dto.amount()), orZero(dto.unitPrice()), orZero(dto.totalPrice()), dto.priceSource(), dto.stock(),
                blankToNull(dto.refusal()), dto.refusalMessage());
    }

    public static ShopPurchaseResult toPurchase(ShopDtos.PurchaseResultDto dto, int shopId) {
        return new ShopPurchaseResult(dto.shopId() != null ? dto.shopId() : shopId,
                dto.itemBlueprintId() != null ? dto.itemBlueprintId() : 0, dto.itemInstanceId(), orZero(dto.amount()),
                orZero(dto.unitPrice()), orZero(dto.totalPrice()), dto.deliverTo(), dto.itemDeliveryId(), dto.coinsAfter(),
                Boolean.TRUE.equals(dto.replayed()));
    }

    public static ShopDtos.SellRequestDto toDto(ShopSellRequest request) {
        List<ShopDtos.SellLineDto> lines = new ArrayList<>();
        for (ShopSellLine line : request.lines()) {
            lines.add(line.unique()
                    ? new ShopDtos.SellLineDto(null, 1, line.itemInstanceId())
                    : new ShopDtos.SellLineDto(line.itemBlueprintId(), line.amount(), null));
        }
        return new ShopDtos.SellRequestDto(request.userId(), lines, request.expectedTotal(), request.confirmed(),
                request.idempotencyKey());
    }

    public static ShopSellQuote toSellQuote(ShopDtos.SellQuoteDto dto, int shopId) {
        return new ShopSellQuote(dto.shopId() != null ? dto.shopId() : shopId, lines(dto.lines()), orZero(dto.total()),
                Boolean.TRUE.equals(dto.accepted()), Boolean.TRUE.equals(dto.townCanPay()));
    }

    public static ShopSellResult toSellResult(ShopDtos.SellResultDto dto, int shopId) {
        return new ShopSellResult(dto.shopId() != null ? dto.shopId() : shopId, orZero(dto.total()), lines(dto.lines()),
                dto.coinsAfter(), Boolean.TRUE.equals(dto.replayed()));
    }

    static List<ShopSellQuote.Line> lines(List<ShopDtos.SellQuoteLineDto> dtos) {
        List<ShopSellQuote.Line> lines = new ArrayList<>();
        int index = 0;
        for (ShopDtos.SellQuoteLineDto line : dtos == null ? List.<ShopDtos.SellQuoteLineDto>of() : dtos) {
            if (line != null) {
                lines.add(new ShopSellQuote.Line(line.lineNo() != null ? line.lineNo() : index, line.itemBlueprintId(),
                        line.itemBlueprintName(), line.itemInstanceId(), orZero(line.amount()), orZero(line.unitPrice()),
                        orZero(line.linePrice()), blankToNull(line.refusal()), line.refusalMessage()));
            }
            index++;
        }
        return lines;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "None".equalsIgnoreCase(value) ? null : value;
    }
}
