package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceAdjustmentResultDto(
    @JsonProperty("newCoins") int newCoins,
    @JsonProperty("newGems") int newGems,
    @JsonProperty("newExperiencePoints") int newExperiencePoints,
    @JsonProperty("titleChange") TitleChangeResultDto titleChange,
    @JsonProperty("changes") List<Change> changes,
    @JsonProperty("replayed") boolean replayed
) {
    /** One change as the API's ledger recorded it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
        @JsonProperty("currency") String currency,
        @JsonProperty("mode") String mode,
        @JsonProperty("amount") long amount,
        @JsonProperty("balanceBefore") long balanceBefore,
        @JsonProperty("balanceAfter") long balanceAfter,
        @JsonProperty("transactionPublicId") String transactionPublicId,
        @JsonProperty("replayed") boolean replayed
    ) {}
}
