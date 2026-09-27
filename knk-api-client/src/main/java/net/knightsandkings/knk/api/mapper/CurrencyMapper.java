package net.knightsandkings.knk.api.mapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.api.dto.currency.CurrencyDtos;
import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlert;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertPage;
import net.knightsandkings.knk.core.domain.currency.LeaderboardEntry;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerLine;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PaymentNotice;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.ReversalOutcome;
import net.knightsandkings.knk.core.domain.currency.TransferLock;
import net.knightsandkings.knk.core.domain.currency.TransferLimits;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/** {@code api/currency} DTOs → knk-core currency records (currency ledger, KNG-21 Phases 3–4). */
public final class CurrencyMapper {

    private CurrencyMapper() {
    }

    public static Balances mapBalances(CurrencyDtos.BalancesDto dto) {
        return dto == null ? null : new Balances(dto.userId(), dto.coins(), dto.gems(), dto.experiencePoints());
    }

    public static PendingTransfer mapPending(CurrencyDtos.PendingTransferDto dto) {
        if (dto == null) {
            return null;
        }
        return new PendingTransfer(dto.publicId(), dto.status(), currency(dto.currency()), dto.amount(), dto.fee(),
            dto.recipientUserId(), dto.recipientUsername(), instant(dto.expiresAt()), dto.expiresInSeconds());
    }

    public static TransferOutcome mapTransfer(CurrencyDtos.TransferResultDto dto) {
        if (dto == null) {
            return null;
        }
        TransferOutcome.Status status = "PendingConfirmation".equalsIgnoreCase(dto.status())
            ? TransferOutcome.Status.PENDING_CONFIRMATION
            : TransferOutcome.Status.COMPLETED;
        return new TransferOutcome(status, dto.publicId(), dto.replayed(), currency(dto.currency()), dto.amount(), dto.fee(),
            dto.senderUserId(), dto.senderUsername(), dto.recipientUserId(), dto.recipientUsername(),
            mapBalances(dto.senderBalances()), mapPending(dto.pending()));
    }

    public static TransferLimits mapLimits(CurrencyDtos.TransferLimitsDto dto) {
        if (dto == null) {
            return null;
        }
        return new TransferLimits(dto.userId(), currency(dto.currency()), dto.transferable(), dto.minTransfer(), dto.maxTransfer(),
            dto.dailySendCap(), dto.sentLast24h(), dto.remainingToday(), dto.confirmThreshold(), dto.transferFeeBasisPoints(),
            instant(dto.nextTransferAt()), dto.eligible(), dto.minSenderAccountAgeHours(), instant(dto.eligibleFrom()),
            dto.requiredTitleName(), dto.requiredExperience(), dto.locked());
    }

    public static LeaderboardPage mapLeaderboard(CurrencyDtos.LeaderboardDto dto) {
        if (dto == null) {
            return null;
        }
        List<LeaderboardEntry> entries = dto.entries() == null ? List.of() : dto.entries().stream()
            .map(e -> new LeaderboardEntry(e.rank(), e.userId(), e.username(), e.balance()))
            .toList();
        return new LeaderboardPage(currency(dto.currency()), dto.page(), dto.pageSize(), dto.totalCount(), entries);
    }

    public static LedgerPage mapLedger(CurrencyDtos.LedgerPageDto dto) {
        if (dto == null) {
            return null;
        }
        List<LedgerLine> lines = dto.items() == null ? List.of() : dto.items().stream()
            .map(l -> new LedgerLine(l.entryId(), l.publicId(), instant(l.createdAt()), currency(l.currency()), l.amount(),
                l.balanceBefore(), l.balanceAfter(), l.kind(), l.reasonCode(), l.reason(), l.initiator(), l.initiatorUsername(),
                l.initiatorComponent(), l.counterpartyUserId(), l.counterpartyUsername()))
            .toList();
        return new LedgerPage(lines, dto.totalCount(), dto.pageNumber(), dto.pageSize());
    }

    public static PaymentNotice mapPaymentNotice(CurrencyDtos.PaymentNotificationDto dto) {
        if (dto == null) {
            return null;
        }
        return new PaymentNotice(dto.amount(), currency(dto.currency()), dto.fromUserId(), dto.fromUsername(),
            dto.transactionPublicId(), dto.balanceAfter());
    }

    public static ReversalOutcome mapReversal(CurrencyDtos.ReversalResultDto dto) {
        if (dto == null || dto.posting() == null) {
            return null;
        }
        CurrencyDtos.PostingResultDto posting = dto.posting();
        List<ReversalOutcome.Leg> legs = posting.entries() == null ? List.of() : posting.entries().stream()
            .map(e -> new ReversalOutcome.Leg(e.userId(), currency(e.currency()), e.amount(), e.balanceAfter()))
            .toList();
        Map<Integer, Balances> balances = new HashMap<>();
        if (posting.balances() != null) {
            posting.balances().values().forEach(b -> {
                if (b != null) {
                    balances.put(b.userId(), mapBalances(b));
                }
            });
        }
        // Either flag marks a replay: never shown as a fresh reversal.
        boolean replayed = posting.replayed() || Boolean.TRUE.equals(dto.replayed());
        return new ReversalOutcome(dto.reversedPublicId(), posting.publicId(), replayed, dto.partial(), legs, balances);
    }

    public static TransferLock mapLock(CurrencyDtos.TransferLockDto dto) {
        return dto == null ? null : new TransferLock(dto.userId(), dto.username(), dto.locked(), dto.reason(), instant(dto.lockedAt()));
    }

    public static CurrencyAlert mapAlert(CurrencyDtos.CurrencyAlertDto dto) {
        if (dto == null) {
            return null;
        }
        return new CurrencyAlert(dto.id(), dto.rule(), dto.ruleName(), dto.severity(), dto.summary(), dto.userId(), dto.username(),
            dto.transactionPublicId(), instant(dto.createdAt()), instant(dto.ackedAt()), dto.ackedByUsername());
    }

    public static CurrencyAlertPage mapAlertPage(CurrencyDtos.CurrencyAlertPageDto dto) {
        if (dto == null) {
            return new CurrencyAlertPage(List.of(), 0, 1, 1, 0);
        }
        List<CurrencyAlert> items = dto.items() == null ? List.of() : dto.items().stream().map(CurrencyMapper::mapAlert).toList();
        return new CurrencyAlertPage(items, dto.totalCount(), dto.pageNumber(), dto.pageSize(), dto.openCount());
    }

    public static CurrencyAlertNotice mapAlertNotice(CurrencyDtos.CurrencyAlertNotificationDto dto) {
        if (dto == null) {
            return null;
        }
        return new CurrencyAlertNotice(dto.alertId(), dto.rule(), dto.ruleName(), dto.severity(), dto.summary(), dto.userId(),
            dto.username(), dto.transfersDisabled());
    }

    /** "Coins"/"Gems"/"Experience" → enum; unknown → COINS (the API only sends these three). */
    static BalanceCurrency currency(String wire) {
        BalanceCurrency currency = wire == null ? null : BalanceCurrency.fromWireValue(wire);
        return currency != null ? currency : BalanceCurrency.COINS;
    }

    /**
     * The API's timestamps are UTC; values read back from MySQL come without an offset
     * ({@code 2026-09-26T19:08:00.841058}), so a missing offset means UTC. Null/blank/garbage → null.
     */
    public static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException alsoIgnored) {
                return null;
            }
        }
    }
}
