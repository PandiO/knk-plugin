package net.knightsandkings.knk.core.domain.item;

/**
 * One slot-indexed content entry, either as authored on a {@link KnkKit} (catalog) or as
 * resolved by a claim/give (docs/specs/kits/DESIGN.md §2.2/§4.1) - both shapes are
 * (SlotIndex, ItemBlueprintId, Quantity) on the wire, so one record covers both.
 */
public record KnkKitContent(int slotIndex, int itemBlueprintId, int quantity) {}
