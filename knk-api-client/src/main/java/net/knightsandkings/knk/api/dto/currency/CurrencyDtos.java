package net.knightsandkings.knk.api.dto.currency;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON shapes of knk-web-api's {@code api/currency} routes (Dtos/CurrencyDtos.cs, currency ledger
 * KNG-21 Phase 3). Timestamps stay strings here; CurrencyMapper parses them (the API sends UTC,
 * sometimes without an offset).
 */
public final class CurrencyDtos {

    private CurrencyDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BalancesDto(
        @JsonProperty("userId") int userId,
        @JsonProperty("coins") long coins,
        @JsonProperty("gems") long gems,
        @JsonProperty("experiencePoints") long experiencePoints
    ) {}

    /** Body of POST /api/currency/transfers. */
    public record CreateTransferDto(
        @JsonProperty("senderUserId") int senderUserId,
        @JsonProperty("recipientUserId") int recipientUserId,
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") long amount,
        @JsonProperty("bypassLimits") boolean bypassLimits
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingTransferDto(
        @JsonProperty("publicId") String publicId,
        @JsonProperty("status") String status,
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") long amount,
        @JsonProperty("fee") long fee,
        @JsonProperty("recipientUserId") int recipientUserId,
        @JsonProperty("recipientUsername") String recipientUsername,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("expiresAt") String expiresAt,
        @JsonProperty("expiresInSeconds") int expiresInSeconds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferResultDto(
        @JsonProperty("status") String status,
        @JsonProperty("transactionId") Long transactionId,
        @JsonProperty("publicId") String publicId,
        @JsonProperty("replayed") boolean replayed,
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") long amount,
        @JsonProperty("fee") long fee,
        @JsonProperty("senderUserId") int senderUserId,
        @JsonProperty("senderUsername") String senderUsername,
        @JsonProperty("recipientUserId") int recipientUserId,
        @JsonProperty("recipientUsername") String recipientUsername,
        @JsonProperty("senderBalances") BalancesDto senderBalances,
        @JsonProperty("pending") PendingTransferDto pending
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferLimitsDto(
        @JsonProperty("userId") int userId,
        @JsonProperty("currency") String currency,
        @JsonProperty("transferable") boolean transferable,
        @JsonProperty("minTransfer") long minTransfer,
        @JsonProperty("maxTransfer") long maxTransfer,
        @JsonProperty("dailySendCap") long dailySendCap,
        @JsonProperty("sentLast24h") long sentLast24h,
        @JsonProperty("remainingToday") long remainingToday,
        @JsonProperty("confirmThreshold") long confirmThreshold,
        @JsonProperty("transferFeeBasisPoints") int transferFeeBasisPoints,
        @JsonProperty("nextTransferAt") String nextTransferAt,
        @JsonProperty("eligible") boolean eligible,
        @JsonProperty("minSenderAccountAgeHours") int minSenderAccountAgeHours,
        @JsonProperty("eligibleFrom") String eligibleFrom,
        @JsonProperty("requiredTitleName") String requiredTitleName,
        @JsonProperty("requiredExperience") Integer requiredExperience,
        @JsonProperty("locked") boolean locked
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeaderboardEntryDto(
        @JsonProperty("rank") int rank,
        @JsonProperty("userId") int userId,
        @JsonProperty("username") String username,
        @JsonProperty("balance") long balance
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeaderboardDto(
        @JsonProperty("currency") String currency,
        @JsonProperty("page") int page,
        @JsonProperty("pageSize") int pageSize,
        @JsonProperty("totalCount") int totalCount,
        @JsonProperty("entries") List<LeaderboardEntryDto> entries
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LedgerLineDto(
        @JsonProperty("entryId") long entryId,
        @JsonProperty("publicId") String publicId,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("currency") String currency,
        @JsonProperty("amount") long amount,
        @JsonProperty("balanceBefore") long balanceBefore,
        @JsonProperty("balanceAfter") long balanceAfter,
        @JsonProperty("kind") String kind,
        @JsonProperty("reasonCode") String reasonCode,
        @JsonProperty("reason") String reason,
        @JsonProperty("initiator") String initiator,
        @JsonProperty("initiatorUsername") String initiatorUsername,
        @JsonProperty("initiatorComponent") String initiatorComponent,
        @JsonProperty("counterpartyUserId") Integer counterpartyUserId,
        @JsonProperty("counterpartyUsername") String counterpartyUsername
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LedgerPageDto(
        @JsonProperty("items") List<LedgerLineDto> items,
        @JsonProperty("totalCount") int totalCount,
        @JsonProperty("pageNumber") int pageNumber,
        @JsonProperty("pageSize") int pageSize
    ) {}

    /** Payload of a PaymentReceived player notification. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentNotificationDto(
        @JsonProperty("amount") long amount,
        @JsonProperty("currency") String currency,
        @JsonProperty("fromUserId") int fromUserId,
        @JsonProperty("fromUsername") String fromUsername,
        @JsonProperty("transactionPublicId") String transactionPublicId,
        @JsonProperty("balanceAfter") long balanceAfter
    ) {}

    /** The API's refusal body: {@code {error, code, message, details}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrencyErrorDto(
        @JsonProperty("error") String error,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message,
        @JsonProperty("details") Map<String, Object> details
    ) {}
}
