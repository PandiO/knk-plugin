package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to SalaryPayoutResultDto from knk-web-api (POST /api/Users/{id}/salary/payout).
 */
public record SalaryPayoutResultDto(
    @JsonProperty("paid") boolean paid,
    @JsonProperty("amountPaid") int amountPaid,
    @JsonProperty("hoursCovered") double hoursCovered,
    @JsonProperty("globalMultiplier") double globalMultiplier,
    @JsonProperty("personalMultiplier") double personalMultiplier,
    @JsonProperty("rankMultiplier") double rankMultiplier,
    @JsonProperty("newCoinsBalance") int newCoinsBalance,
    @JsonProperty("lastSalaryPayoutAt") java.time.OffsetDateTime lastSalaryPayoutAt,
    @JsonProperty("nextEligibleAt") java.time.OffsetDateTime nextEligibleAt,
    @JsonProperty("titleBracketId") Integer titleBracketId,
    @JsonProperty("titleSalary") int titleSalary,
    @JsonProperty("paidHours") double paidHours,
    @JsonProperty("baseAmount") double baseAmount,
    @JsonProperty("multipliers") java.util.List<RewardMultiplierDto> multipliers
) {}
