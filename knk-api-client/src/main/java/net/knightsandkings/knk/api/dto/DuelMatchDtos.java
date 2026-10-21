package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire DTOs for knk-web-api's {@code /api/DuelMatches} (arena duels KNG-213), mirroring {@code Dtos/DuelDtos.cs}.
 * JSON is camelCase; enums go as the API's names ({@code "OWN_GEAR_KEEP"}, {@code "AgreedDraw"}); instants as
 * ISO-8601 strings. Response types use boxed fields so a missing value maps to the mapper's default.
 */
public final class DuelMatchDtos {
    private DuelMatchDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @JsonProperty("ownerUserId") Integer ownerUserId,
            @JsonProperty("material") String material,
            @JsonProperty("amount") Integer amount,
            @JsonProperty("name") String name,
            @JsonProperty("grade") String grade,
            @JsonProperty("instanceId") Long instanceId
    ) {}

    // ---- requests ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateRequest(
            @JsonProperty("publicToken") String publicToken,
            @JsonProperty("arenaId") int arenaId,
            @JsonProperty("challengerUserId") int challengerUserId,
            @JsonProperty("opponentUserId") int opponentUserId,
            @JsonProperty("gearMode") String gearMode,
            @JsonProperty("fixedKitId") Integer fixedKitId,
            @JsonProperty("challengerCoins") long challengerCoins,
            @JsonProperty("opponentCoins") long opponentCoins,
            @JsonProperty("stakeItems") List<Item> stakeItems,
            @JsonProperty("offerCount") int offerCount,
            @JsonProperty("lockedAt") String lockedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StartFightRequest(
            @JsonProperty("battlegroundId") int battlegroundId,
            @JsonProperty("challengerKitId") Integer challengerKitId,
            @JsonProperty("opponentKitId") Integer opponentKitId,
            @JsonProperty("startedAt") String startedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Side(
            @JsonProperty("damageDealt") Double damageDealt,
            @JsonProperty("damageTaken") Double damageTaken,
            @JsonProperty("hits") Integer hits,
            @JsonProperty("attackAttempts") Integer attackAttempts,
            @JsonProperty("armorPoints") Integer armorPoints,
            @JsonProperty("mainHand") String mainHand,
            @JsonProperty("hadWeapon") Boolean hadWeapon
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Telemetry(
            @JsonProperty("durationSeconds") Double durationSeconds,
            @JsonProperty("challenger") Side challenger,
            @JsonProperty("opponent") Side opponent
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompleteRequest(
            @JsonProperty("winnerUserId") int winnerUserId,
            @JsonProperty("endReason") String endReason,
            @JsonProperty("telemetry") Telemetry telemetry,
            @JsonProperty("spoilsItems") List<Item> spoilsItems,
            @JsonProperty("endedAt") String endedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancelRequest(
            @JsonProperty("endReason") String endReason,
            @JsonProperty("telemetry") Telemetry telemetry,
            @JsonProperty("endedAt") String endedAt
    ) {}

    public record AbortUnfinishedRequest(@JsonProperty("endReason") String endReason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StaffActionRequest(@JsonProperty("reason") String reason) {}

    // ---- responses ----

    public record Match(
            @JsonProperty("id") Integer id,
            @JsonProperty("publicToken") String publicToken,
            @JsonProperty("arenaId") Integer arenaId,
            @JsonProperty("battlegroundId") Integer battlegroundId,
            @JsonProperty("challengerUserId") Integer challengerUserId,
            @JsonProperty("opponentUserId") Integer opponentUserId,
            @JsonProperty("gearMode") String gearMode,
            @JsonProperty("fixedKitId") Integer fixedKitId,
            @JsonProperty("challengerKitId") Integer challengerKitId,
            @JsonProperty("opponentKitId") Integer opponentKitId,
            @JsonProperty("status") String status,
            @JsonProperty("endReason") String endReason,
            @JsonProperty("winnerUserId") Integer winnerUserId,
            @JsonProperty("challengerCoins") Long challengerCoins,
            @JsonProperty("opponentCoins") Long opponentCoins,
            @JsonProperty("feePercent") Integer feePercent,
            @JsonProperty("feeCoins") Long feeCoins,
            @JsonProperty("valueMoving") Boolean valueMoving,
            @JsonProperty("review") String review,
            @JsonProperty("reviewTier") Integer reviewTier,
            @JsonProperty("reviewSignals") List<String> reviewSignals,
            @JsonProperty("undeliveredItems") String undeliveredItems,
            @JsonProperty("stakeItems") List<Item> stakeItems,
            @JsonProperty("spoilsItems") List<Item> spoilsItems,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("endedAt") String endedAt
    ) {}

    public record Revert(
            @JsonProperty("confiscated") Boolean confiscated,
            @JsonProperty("payoutCoins") Long payoutCoins,
            @JsonProperty("recoveredCoins") Long recoveredCoins,
            @JsonProperty("feeReversedCoins") Long feeReversedCoins,
            @JsonProperty("returnedToLoserCoins") Long returnedToLoserCoins,
            @JsonProperty("returnedToWinnerCoins") Long returnedToWinnerCoins,
            @JsonProperty("confiscatedCoins") Long confiscatedCoins,
            @JsonProperty("missingCoins") Long missingCoins,
            @JsonProperty("undeliveredItems") String undeliveredItems
    ) {}

    public record Result(
            @JsonProperty("match") Match match,
            @JsonProperty("settlement") String settlement,
            @JsonProperty("alreadyDone") Boolean alreadyDone,
            @JsonProperty("revert") Revert revert
    ) {}

    public record Refusal(@JsonProperty("code") String code, @JsonProperty("message") String message) {}

    public record CheckResult(
            @JsonProperty("allowed") Boolean allowed,
            @JsonProperty("valueMoving") Boolean valueMoving,
            @JsonProperty("feeCoins") Long feeCoins,
            @JsonProperty("feePercent") Integer feePercent,
            @JsonProperty("refusals") List<Refusal> refusals
    ) {}

    public record AbortUnfinishedResult(@JsonProperty("abortedMatchIds") List<Integer> abortedMatchIds) {}

    /** The API's refusal body: {@code { code, message, details }}. */
    public record Error(@JsonProperty("code") String code, @JsonProperty("message") String message) {}
}
