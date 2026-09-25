package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One (SlotIndex, ItemBlueprintId, Quantity) entry. Shared wire shape between knk-web-api's
 * {@code KitContentDto} (authored on the Kit catalog) and {@code KitContentSlotDto} (resolved
 * inside a claim/give result) - both are field-for-field identical on the wire.
 */
public record KitContentSlotDto(
        @JsonProperty("slotIndex") Integer slotIndex,
        @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
        @JsonProperty("quantity") Integer quantity
) {}
