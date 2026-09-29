package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bodies of knk-web-api's teleport charge routes ({@code Dtos/TeleportDtos.cs},
 * docs/specs/teleport/DESIGN.md §3.7.3): {@code POST api/teleport-destinations/{domainId}/charge},
 * {@code .../request-fee} and {@code .../refund}.
 */
public final class TeleportChargeDtos {

    private TeleportChargeDtos() {
    }

    public record ChargeRequest(
        @JsonProperty("userId") int userId,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("bypassRequirements") boolean bypassRequirements,
        @JsonProperty("bypassCost") boolean bypassCost
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestFee(
        @JsonProperty("userId") int userId,
        @JsonProperty("amountCoins") int amountCoins,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("otherUserId") Integer otherUserId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChargeResult(
        @JsonProperty("currency") String currency,
        @JsonProperty("charged") Long charged,
        @JsonProperty("newBalance") Long newBalance,
        @JsonProperty("replayed") Boolean replayed,
        @JsonProperty("transactionPublicId") String transactionPublicId,
        @JsonProperty("destination") TeleportDestinationDto destination
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundRequest(
        @JsonProperty("userId") int userId,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("reason") String reason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResult(
        @JsonProperty("refunded") Boolean refunded,
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") Long amount,
        @JsonProperty("newBalance") Long newBalance,
        @JsonProperty("replayed") Boolean replayed
    ) {}

    /** A refusal: {@code { "error": "InsufficientGems", "message": "..." }}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(
        @JsonProperty("error") String error,
        @JsonProperty("message") String message
    ) {}
}
