package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The wire shapes of item delivery and the reward inbox (P7, PandiO/knk-workspace#60 and #684): knk-web-api
 * {@code ItemDeliveryDtos.cs}. Dates stay strings and are parsed by {@code ItemDeliveryApiImpl} (the API may send them
 * with or without a zone).
 */
public final class ItemDeliveryDtos {

    private ItemDeliveryDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LineRequestDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                                 @JsonProperty("itemInstanceId") Long itemInstanceId,
                                 @JsonProperty("amount") long amount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryRequestDto(@JsonProperty("userId") int userId, @JsonProperty("reason") String reason,
                                     @JsonProperty("sourceType") String sourceType,
                                     @JsonProperty("sourceRef") String sourceRef, @JsonProperty("note") String note,
                                     @JsonProperty("idempotencyKey") String idempotencyKey,
                                     @JsonProperty("lines") List<LineRequestDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliveryDto(@JsonProperty("id") Long id, @JsonProperty("status") String status,
                              @JsonProperty("inboxedUnits") Long inboxedUnits, @JsonProperty("lostUnits") Long lostUnits,
                              @JsonProperty("capUnits") Long capUnits, @JsonProperty("expiresAt") String expiresAt,
                              @JsonProperty("replayed") Boolean replayed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntryDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                           @JsonProperty("itemBlueprintName") String itemBlueprintName,
                           @JsonProperty("itemInstanceId") Long itemInstanceId,
                           @JsonProperty("customDisplayName") String customDisplayName,
                           @JsonProperty("amount") Long amount, @JsonProperty("nextExpiresAt") String nextExpiresAt,
                           @JsonProperty("nextExpiringAmount") Long nextExpiringAmount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ViewDto(@JsonProperty("userId") Integer userId, @JsonProperty("storageId") Integer storageId,
                          @JsonProperty("usedUnits") Double usedUnits, @JsonProperty("capUnits") Long capUnits,
                          @JsonProperty("fillPercent") Integer fillPercent,
                          @JsonProperty("warningPercent") Integer warningPercent,
                          @JsonProperty("premiumTierGroupId") Integer premiumTierGroupId,
                          @JsonProperty("isLocked") Boolean isLocked, @JsonProperty("entries") List<EntryDto> entries) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoveDto(@JsonProperty("idempotencyKey") String idempotencyKey,
                          @JsonProperty("lines") List<LineRequestDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineResultDto(@JsonProperty("accepted") Long accepted, @JsonProperty("requested") Long requested,
                                @JsonProperty("rejectReason") String rejectReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockResultDto(@JsonProperty("status") String status, @JsonProperty("replayed") Boolean replayed,
                                 @JsonProperty("accepted") Long accepted, @JsonProperty("lines") List<LineResultDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoveResultDto(@JsonProperty("storageId") Integer storageId, @JsonProperty("result") StockResultDto result,
                                @JsonProperty("view") ViewDto view) {
    }

    /** The {@code inbox} payload of an InboxUpdated player notification. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InboxNotificationDto(@JsonProperty("event") String event, @JsonProperty("deliveryId") Long deliveryId,
                                       @JsonProperty("reason") String reason,
                                       @JsonProperty("inboxedUnits") Long inboxedUnits,
                                       @JsonProperty("lostUnits") Long lostUnits,
                                       @JsonProperty("warningPercent") Integer warningPercent,
                                       @JsonProperty("fillPercent") Integer fillPercent,
                                       @JsonProperty("capUnits") Long capUnits,
                                       @JsonProperty("expiringUnits") Long expiringUnits,
                                       @JsonProperty("expiresAt") String expiresAt) {
    }
}
