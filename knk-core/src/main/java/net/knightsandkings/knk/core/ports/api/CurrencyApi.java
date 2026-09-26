package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LeaderboardPage;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.TransferLimits;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * Player currency routes of knk-web-api ({@code api/currency}, currency-payments
 * IMPLEMENTATION_PLAN.md Phase 3): balances, /baltop, limits, /pay with its confirmation step, and
 * a player's ledger history. Refusals complete exceptionally with a {@link CurrencyException}
 * carrying the API's code (e.g. {@code DailyCapExceeded}); the server decides everything, the
 * plugin only shows what it answers.
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
}
