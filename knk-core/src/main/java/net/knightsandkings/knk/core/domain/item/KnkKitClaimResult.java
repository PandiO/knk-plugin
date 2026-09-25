package net.knightsandkings.knk.core.domain.item;

import java.util.List;

/**
 * Resolved loadout returned by a successful claim/give (docs/specs/kits/DESIGN.md §4.1/§4.2) -
 * each named equipment field is an {@code ItemBlueprint} id (or null if that slot isn't part of
 * this Kit), and {@code contents} is the (SlotIndex, ItemBlueprintId, Quantity) list the plugin
 * places via {@code KitGrantPlacer}'s unified grant-placement algorithm. Mirrors knk-web-api's
 * {@code KitClaimResultDto} field-for-field.
 */
public record KnkKitClaimResult(
        int kitId,
        Integer helmetId,
        Integer chestplateId,
        Integer leggingsId,
        Integer bootsId,
        Integer shieldId,
        Integer handId,
        List<KnkKitContent> contents
) {}
