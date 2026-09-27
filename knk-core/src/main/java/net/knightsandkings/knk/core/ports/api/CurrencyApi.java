package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlert;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertPage;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.ReversalOutcome;
import net.knightsandkings.knk.core.domain.currency.TransferLock;
import net.knightsandkings.knk.core.domain.currency.TransferLimits;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * Player currency routes of knk-web-api ({@code api/currency}, currency-payments
 * IMPLEMENTATION_PLAN.md Phase 3): balances, /baltop, limits, /pay with its confirmation step, and
 * a player's ledger history; and the staff routes of {@code api/currency/admin} (Phase 4):
 * reversals and transfer locks, and (Phase 5) the currency alerts. Refusals complete
 * exceptionally with a {@link CurrencyException} carrying the API's code (e.g.
 * {@code DailyCapExceeded}); the server decides everything, the plugin only shows what it answers.
 * <p>
 * Writes are safe to retry: each call sends one {@code Idempotency-Key} (a new one per call, the
 * same on every retry of that call), so a timeout followed by a retry never pays twice. When no
 * answer arrives after every retry the future fails with
 * {@link CurrencyException#outcomeUnknown()} set.
 */
public interface CurrencyApi {

    CompletableFuture<Balances> getBalances(int userId);

    /** {@code currency} COINS or GEMS; {@code page} 1-based. */
    CompletableFuture<LeaderboardPage> getLeaderboard(BalanceCurrency currency, int page, int pageSize);

    CompletableFuture<TransferLimits> getLimits(int userId, BalanceCurrency currency);

    /**
     * {@code /pay}: sends {@code amount} of {@code currency} from the sender (who must be the
     * player running the command - the API requires it as the acting user) to the recipient.
     * {@code bypassLimits}: the sender holds knk.pay.bypass.
     */
    CompletableFuture<TransferOutcome> transfer(int senderUserId, int recipientUserId, BalanceCurrency currency, long amount,
                                                boolean bypassLimits);

    /** {@code /pay confirm <id>}: sends the sender's pending payment; a repeat returns the first result. */
    CompletableFuture<TransferOutcome> confirmTransfer(int senderUserId, String pendingPublicId, boolean bypassLimits);

    /** {@code /pay cancel <id>}. */
    CompletableFuture<PendingTransfer> cancelTransfer(int senderUserId, String pendingPublicId);

    /** A player's ledger history, newest first; {@code currency} null for all of coins, gems and XP. */
    CompletableFuture<LedgerPage> getTransactions(int userId, BalanceCurrency currency, int page, int pageSize);

    // ===== Staff (Phase 4; the caller checked the staff member's knk.admin.currency.* node) =====

    /**
     * {@code /knk currency reverse}: reverses transaction {@code publicId} as staff member
     * {@code actingUserId} with {@code note} (at least 10 characters). {@code allowPartial}: reverse
     * what is left when the player has spent some of it. Refused with {@code AlreadyReversed},
     * {@code ReversalWouldGoNegative}, {@code NotReversible} or {@code TransactionNotFound}.
     */
    CompletableFuture<ReversalOutcome> reverseTransaction(int actingUserId, String publicId, String note, boolean allowPartial);

    /** {@code /knk currency lock}: locks the player's transfers with a reason. */
    CompletableFuture<TransferLock> lockTransfers(int actingUserId, int userId, String reason);

    /** {@code /knk currency unlock}. */
    CompletableFuture<TransferLock> unlockTransfers(int actingUserId, int userId);

    // ===== Staff: currency alerts (Phase 5; the caller checked knk.admin.currency.alerts) =====

    /** {@code /knk currency alerts [all] [page]}: open alerts (or every alert), newest first. */
    CompletableFuture<CurrencyAlertPage> getAlerts(boolean includeAcknowledged, int page, int pageSize);

    /** {@code /knk currency alerts ack <id>}: marks the alert handled by {@code actingUserId} (repeatable). */
    CompletableFuture<CurrencyAlert> acknowledgeAlert(int actingUserId, long alertId);
}
