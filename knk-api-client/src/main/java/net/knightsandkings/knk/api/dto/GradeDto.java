package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GradeDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("stars") Integer stars,
        // Linear KNG-6 (docs/specs/items/GRADE_DROPCHANCE.md): percent 0-100, and the enchant-book level cap
        // divisor (null = uncapped).
        @JsonProperty("dropChance") Double dropChance,
        @JsonProperty("enchantLevelCapDivisor") Integer enchantLevelCapDivisor
) {}
