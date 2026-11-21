package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The wire shapes of buying from and selling to shops (P10, PandiO/knk-workspace#248 and #687): knk-web-api
 * {@code ShopDtos.cs} ({@code ShopOfferDto}, {@code ShopPurchaseRequestDto}, {@code ShopQuoteDto},
 * {@code ShopPurchaseResultDto}, {@code ShopSellRequestDto}, {@code ShopSellQuoteDto}, {@code ShopSellResultDto}).
 */
public final class ShopDtos {

    private ShopDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfferDto(@JsonProperty("shopId") Integer shopId, @JsonProperty("shopName") String shopName,
                           @JsonProperty("kind") String kind, @JsonProperty("level") Integer level,
                           @JsonProperty("shopTypeName") String shopTypeName, @JsonProperty("townId") Integer townId,
                           @JsonProperty("townName") String townName, @JsonProperty("hideSoldOut") Boolean hideSoldOut,
                           @JsonProperty("buysFromPlayers") Boolean buysFromPlayers,
                           @JsonProperty("items") List<OfferItemDto> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfferItemDto(@JsonProperty("catalogueEntryId") Integer catalogueEntryId,
                               @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                               @JsonProperty("itemBlueprintName") String itemBlueprintName,
                               @JsonProperty("itemInstanceId") Long itemInstanceId,
                               @JsonProperty("unitPrice") Long unitPrice, @JsonProperty("priceSource") String priceSource,
                               @JsonProperty("stock") Long stock, @JsonProperty("soldOut") Boolean soldOut,
                               @JsonProperty("pinned") Boolean pinned, @JsonProperty("sortOrder") Integer sortOrder,
                               @JsonProperty("enchantments") List<String> enchantments) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PurchaseRequestDto(@JsonProperty("userId") int userId,
                                     @JsonProperty("catalogueEntryId") Integer catalogueEntryId,
                                     @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                                     @JsonProperty("itemInstanceId") Long itemInstanceId,
                                     @JsonProperty("amount") long amount,
                                     @JsonProperty("expectedUnitPrice") Long expectedUnitPrice,
                                     @JsonProperty("confirmed") boolean confirmed,
                                     @JsonProperty("deliverTo") String deliverTo,
                                     @JsonProperty("idempotencyKey") String idempotencyKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuoteDto(@JsonProperty("shopId") Integer shopId, @JsonProperty("catalogueEntryId") Integer catalogueEntryId,
                           @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                           @JsonProperty("itemBlueprintName") String itemBlueprintName,
                           @JsonProperty("itemInstanceId") Long itemInstanceId, @JsonProperty("amount") Long amount,
                           @JsonProperty("unitPrice") Long unitPrice, @JsonProperty("totalPrice") Long totalPrice,
                           @JsonProperty("priceSource") String priceSource, @JsonProperty("stock") Long stock,
                           @JsonProperty("refusal") String refusal, @JsonProperty("refusalMessage") String refusalMessage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseResultDto(@JsonProperty("shopId") Integer shopId, @JsonProperty("userId") Integer userId,
                                    @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                                    @JsonProperty("itemInstanceId") Long itemInstanceId, @JsonProperty("amount") Long amount,
                                    @JsonProperty("unitPrice") Long unitPrice, @JsonProperty("totalPrice") Long totalPrice,
                                    @JsonProperty("deliverTo") String deliverTo,
                                    @JsonProperty("stockTransactionId") Long stockTransactionId,
                                    @JsonProperty("currencyTransactionId") String currencyTransactionId,
                                    @JsonProperty("itemDeliveryId") Long itemDeliveryId, @JsonProperty("townId") Integer townId,
                                    @JsonProperty("coinsAfter") Long coinsAfter, @JsonProperty("replayed") Boolean replayed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SellLineDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId, @JsonProperty("amount") long amount,
                              @JsonProperty("itemInstanceId") Long itemInstanceId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SellRequestDto(@JsonProperty("userId") int userId, @JsonProperty("lines") List<SellLineDto> lines,
                                 @JsonProperty("expectedTotal") Long expectedTotal,
                                 @JsonProperty("confirmed") boolean confirmed,
                                 @JsonProperty("idempotencyKey") String idempotencyKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SellQuoteLineDto(@JsonProperty("lineNo") Integer lineNo, @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                                   @JsonProperty("itemBlueprintName") String itemBlueprintName,
                                   @JsonProperty("itemInstanceId") Long itemInstanceId, @JsonProperty("amount") Long amount,
                                   @JsonProperty("unitPrice") Long unitPrice, @JsonProperty("linePrice") Long linePrice,
                                   @JsonProperty("refusal") String refusal,
                                   @JsonProperty("refusalMessage") String refusalMessage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SellQuoteDto(@JsonProperty("shopId") Integer shopId, @JsonProperty("lines") List<SellQuoteLineDto> lines,
                               @JsonProperty("total") Long total, @JsonProperty("accepted") Boolean accepted,
                               @JsonProperty("townCanPay") Boolean townCanPay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SellResultDto(@JsonProperty("shopId") Integer shopId, @JsonProperty("userId") Integer userId,
                                @JsonProperty("total") Long total, @JsonProperty("lines") List<SellQuoteLineDto> lines,
                                @JsonProperty("stockTransactionIds") List<Long> stockTransactionIds,
                                @JsonProperty("currencyTransactionIds") List<String> currencyTransactionIds,
                                @JsonProperty("coinsAfter") Long coinsAfter, @JsonProperty("replayed") Boolean replayed) {
    }
}
