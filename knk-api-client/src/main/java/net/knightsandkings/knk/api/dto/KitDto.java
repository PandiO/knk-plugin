package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Full CRUD shape, including nested Contents - mirrors knk-web-api's {@code KitDto}
 * field-for-field (docs/specs/kits/IMPLEMENTATION_PLAN.md §1). Read-only on the plugin side
 * (DESIGN.md §4.0/§4.5) - used for {@code getById}/{@code search} only, never submitted back.
 */
public record KitDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("helmetId") Integer helmetId,
        @JsonProperty("chestplateId") Integer chestplateId,
        @JsonProperty("leggingsId") Integer leggingsId,
        @JsonProperty("bootsId") Integer bootsId,
        @JsonProperty("shieldId") Integer shieldId,
        @JsonProperty("handId") Integer handId,
        @JsonProperty("contents") List<KitContentSlotDto> contents,
        @JsonProperty("minTitleBracketId") Integer minTitleBracketId,
        @JsonProperty("requiredPermissionGroupId") Integer requiredPermissionGroupId,
        @JsonProperty("requiredPermissionNode") String requiredPermissionNode,
        @JsonProperty("grantOnFirstJoin") Boolean grantOnFirstJoin,
        @JsonProperty("cooldownSeconds") Integer cooldownSeconds,
        @JsonProperty("costAmount") Integer costAmount,
        @JsonProperty("costCurrency") String costCurrency,
        @JsonProperty("isSinglePurchasePremium") Boolean isSinglePurchasePremium,
        @JsonProperty("premiumPriceGems") Integer premiumPriceGems
) {}
