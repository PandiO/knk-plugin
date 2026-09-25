package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConditionBindingDto(
        @JsonProperty("id") Integer id,
        @JsonProperty("conditionTypeId") String conditionTypeId,
        @JsonProperty("paramsJson") String paramsJson,
        @JsonProperty("sortOrder") Integer sortOrder,
        // InventoryMenu Phase 9 (E5): "Click" | "Render"; absent (older API) = Click.
        @JsonProperty("phase") String phase
) {}
