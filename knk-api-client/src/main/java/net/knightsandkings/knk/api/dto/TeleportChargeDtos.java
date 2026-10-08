package net.knightsandkings.knk.api.dto;

import java.util.List;

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

    /** POST .../back-fee - the flat coin fee of a player's own /back (Linear KNG-42). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BackFee(
        @JsonProperty("userId") int userId,
        @JsonProperty("amountCoins") int amountCoins,
        @JsonProperty("idempotencyKey") String idempotencyKey,
        @JsonProperty("backKind") String backKind
    ) {}

    /** POST .../spawn-fee - a /spawn priced by the player's permission groups (Linear KNG-41). */
    public record SpawnFee(
        @JsonProperty("userId") int userId,
        @JsonProperty("idempotencyKey") String idempotencyKey
    ) {}

    /** One currency taken or given back (KNG-41: a group's fixed price can combine several). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") Long amount,
        @JsonProperty("newBalance") Long newBalance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChargeResult(
        @JsonProperty("currency") String currency,
        @JsonProperty("charged") Long charged,
        @JsonProperty("newBalance") Long newBalance,
        @JsonProperty("replayed") Boolean replayed,
        @JsonProperty("transactionPublicId") String transactionPublicId,
        @JsonProperty("destination") TeleportDestinationDto destination,
        @JsonProperty("payments") List<Payment> payments
    ) {}

    /** GET .../policy?userId= - a player's per-group teleport fees and cooldowns (KNG-41). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Policy(
        @JsonProperty("userId") Integer userId,
        @JsonProperty("request") KindPolicy request,
        @JsonProperty("warp") KindPolicy warp,
        @JsonProperty("spawn") KindPolicy spawn
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KindPolicy(
        @JsonProperty("priceMode") String priceMode,
        @JsonProperty("priceMultiplier") Double priceMultiplier,
        @JsonProperty("priceCoins") Integer priceCoins,
        @JsonProperty("priceGems") Integer priceGems,
        @JsonProperty("priceExperience") Integer priceExperience,
        @JsonProperty("cooldownSeconds") Integer cooldownSeconds
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
