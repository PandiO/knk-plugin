package net.knightsandkings.knk.paper.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.currency.Balances;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlert;
import net.knightsandkings.knk.core.domain.currency.CurrencyAlertPage;
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LedgerLine;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.ReversalOutcome;
import net.knightsandkings.knk.core.domain.currency.TransferLock;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.CurrencyApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * KNG-21 Phase 3: /pay's plugin side - ids from the cache, vanish-safe name lookups, nothing sent
 * for a malformed amount or a missing node, server answers turned into the configured messages.
 */
class PlayerCurrencyServiceTest {

    private final CurrencyApi api = mock(CurrencyApi.class);
    private final UsersDataAccess usersDataAccess = mock(UsersDataAccess.class);
    private final UserCache userCache = new UserCache(Duration.ofMinutes(15));
    private final Map<String, Player> online = new HashMap<>();
    private final Map<String, Boolean> nodes = new HashMap<>();
    private final Player alice = player("alice", 1, 5000);
    private final Player bob = player("bob", 2, 100);
    private final List<String> aliceSees = new ArrayList<>();
    /** Nodes whose check can't be made (knk-web-api down). */
    private final java.util.Set<String> unreachable = new java.util.HashSet<>();

    private final PlayerCurrencyService service = new PlayerCurrencyService(
        Runnable::run, api, usersDataAccess, userCache,
        (player, node) -> CompletableFuture.completedFuture(decide(node)),
        new VisiblePlayers(online::get, () -> (Collection<Player>) online.values()),
        CurrencySettings.defaults(), Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));

    private PermissionDecision decide(String node) {
        return unreachable.contains(node) ? PermissionDecision.UNAVAILABLE : PermissionDecision.of(nodes.getOrDefault(node, true));
    }

    PlayerCurrencyServiceTest() {
        nodes.put(PlayerCurrencyService.PAY_BYPASS_NODE, false);
        doCapture(alice, aliceSees);
    }

    private Player player(String name, int id, int coins) {
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.canSee(any())).thenReturn(true);
        online.put(name, player);
        userCache.put(new UserSummary(id, name, uuid, coins));
        return player;
    }

    private static void doCapture(Player player, List<String> into) {
        org.mockito.Mockito.doAnswer(inv -> into.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        org.mockito.Mockito.doAnswer(inv -> into.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument(0))))
            .when(player).sendMessage(any(Component.class));
    }

    private static TransferOutcome completed(long amount, long senderCoinsAfter) {
        return new TransferOutcome(TransferOutcome.Status.COMPLETED, "01M3", false, BalanceCurrency.COINS, amount, 0, 1, "alice", 2, "bob",
            new Balances(1, senderCoinsAfter, 0, 0), null);
    }

    private static CompletableFuture<TransferOutcome> failed(String code, Map<String, Object> details) {
        return CompletableFuture.failedFuture(new CurrencyException(new CurrencyError(code, code + ": no", details), 422, null));
    }

    @Test
    void pay_sendsTheCachedIdsAndShowsTheServersBalance() {
        when(api.transfer(1, 2, BalanceCurrency.COINS, 1200, false)).thenReturn(CompletableFuture.completedFuture(completed(1200, 3800)));

        service.pay(alice, "bob", "1200", BalanceCurrency.COINS);

        verify(api).transfer(1, 2, BalanceCurrency.COINS, 1200, false);
        assertEquals("§aSent §61,200 coins §ato §ebob§a. Balance: §63,800", aliceSees.get(0));
        assertEquals(3800, userCache.getStale(alice.getUniqueId()).orElseThrow().coins());
    }

    @Test
    void aVanishedPlayer_isLookedUpLikeAnOfflineOne() {
        when(alice.canSee(bob)).thenReturn(false);
        UUID bobUuid = bob.getUniqueId(); // read before when(...): calling a mock inside a stubbing is a Mockito misuse
        UserSummary bobAccount = new UserSummary(2, "bob", bobUuid, 100);
        when(usersDataAccess.getByUsernameAsync("bob")).thenReturn(CompletableFuture.completedFuture(
            FetchResult.<UserSummary>missFetched(bobAccount)));
        when(api.transfer(1, 2, BalanceCurrency.COINS, 50, false)).thenReturn(CompletableFuture.completedFuture(completed(50, 4950)));

        service.pay(alice, "bob", "50", BalanceCurrency.COINS);

        verify(usersDataAccess).getByUsernameAsync("bob");
        assertTrue(aliceSees.get(0).startsWith("§aSent"));
        assertEquals(List.of(), service.visiblePlayers().names(alice, "b"));
    }

    @Test
    void anUnknownName_sendsNothing() {
        when(usersDataAccess.getByUsernameAsync("ghost")).thenReturn(CompletableFuture.completedFuture(FetchResult.<UserSummary>notFound()));

        service.pay(alice, "ghost", "50", BalanceCurrency.COINS);

        verify(api, never()).transfer(anyInt(), anyInt(), any(), anyLong(), anyBoolean());
        assertEquals("§cNo player found named §eghost§c.", aliceSees.get(0));
    }

    @Test
    void malformedAmounts_andSelfPayments_neverReachTheApi() {
        for (String amount : List.of("-5", "0", "1.5", "1e3", "1,000", "99999999999")) {
            service.pay(alice, "bob", amount, BalanceCurrency.COINS);
        }
        service.pay(alice, "ALICE", "5", BalanceCurrency.COINS);

        verify(api, never()).transfer(anyInt(), anyInt(), any(), anyLong(), anyBoolean());
        assertEquals(7, aliceSees.size());
        assertEquals("§cYou can't pay yourself.", aliceSees.get(6));
    }

    @Test
    void withoutTheNode_nothingIsSent() {
        nodes.put(PlayerCurrencyService.PAY_NODE, false);

        service.pay(alice, "bob", "5", BalanceCurrency.COINS);

        verify(api, never()).transfer(anyInt(), anyInt(), any(), anyLong(), anyBoolean());
        assertEquals("§cYou don't have permission to do that.", aliceSees.get(0));
    }

    @Test
    void anUncheckableNode_saysTheEconomyServiceIsDown_notNoPermission() {
        unreachable.add(PlayerCurrencyService.PAY_NODE);
        unreachable.add(PlayerCurrencyService.BALANCE_NODE);

        service.pay(alice, "bob", "5", BalanceCurrency.COINS);
        service.balance(alice, null);

        verify(api, never()).transfer(anyInt(), anyInt(), any(), anyLong(), anyBoolean());
        verify(api, never()).getBalances(anyInt());
        assertEquals("§cThe economy service can't be reached right now — try again in a moment. Nothing was paid.", aliceSees.get(0));
        assertEquals("§cThe economy service can't be reached right now — try again in a moment.", aliceSees.get(1));
    }

    @Test
    void anUnreachableApi_isNotReportedAsAnUnknownPlayer() {
        when(alice.canSee(bob)).thenReturn(false); // looked up through the API
        when(usersDataAccess.getByUsernameAsync("bob")).thenReturn(CompletableFuture.completedFuture(
            FetchResult.<UserSummary>error(new java.io.IOException("Connection refused"))));
        when(api.getBalances(1)).thenReturn(CompletableFuture.failedFuture(
            new RuntimeException("Currency request failed", new java.io.IOException("Connection refused"))));

        service.balance(alice, "bob");
        service.balance(alice, null);

        assertEquals("§cThe economy service can't be reached right now — try again in a moment.", aliceSees.get(0));
        assertEquals("§cThe economy service can't be reached right now — try again in a moment.", aliceSees.get(1));
    }

    @Test
    void gemsNeedTheirOwnNode() {
        nodes.put(PlayerCurrencyService.PAY_GEMS_NODE, false);

        service.pay(alice, "bob", "5", BalanceCurrency.GEMS);

        verify(api, never()).transfer(anyInt(), anyInt(), any(), anyLong(), anyBoolean());
    }

    @Test
    void refusals_useTheConfiguredMessages() {
        when(api.transfer(eq(1), eq(2), any(), anyLong(), anyBoolean()))
            .thenReturn(failed(CurrencyError.DAILY_CAP_EXCEEDED, Map.of("remaining", 1500, "resetsAt", "2026-09-26T15:30:00Z")))
            .thenReturn(failed(CurrencyError.NEW_ACCOUNT_RESTRICTED, Map.of("hours", 48, "title", "Peasant")))
            .thenReturn(failed(CurrencyError.NOT_TRANSFERABLE, Map.of()))
            .thenReturn(CompletableFuture.failedFuture(new CurrencyException(
                new CurrencyError(CurrencyError.UNKNOWN_OUTCOME, "no answer", Map.of()), -1, null)));

        service.pay(alice, "bob", "5000", BalanceCurrency.COINS);
        service.pay(alice, "bob", "10", BalanceCurrency.COINS);
        service.pay(alice, "bob", "10", BalanceCurrency.GEMS);
        service.pay(alice, "bob", "10", BalanceCurrency.COINS);

        assertEquals("§cDaily limit reached - you can send §61,500 §cmore coins (resets in 3h 30m).", aliceSees.get(0));
        assertEquals("§cYour account must be 48h old and reach Peasant before sending coins.", aliceSees.get(1));
        assertEquals("§cGems can't be transferred.", aliceSees.get(2));
        assertEquals("§cWe couldn't confirm the payment. Check §e/transactions §cbefore trying again.", aliceSees.get(3));
    }

    @Test
    void largePayments_askForConfirmation_andConfirmUsesThePendingId() {
        PendingTransfer pending = new PendingTransfer("01M3FHX1ZDRTAB76K01ZYK0PCZ", "Pending", BalanceCurrency.COINS, 150_000, 0, 2, "bob",
            Instant.parse("2026-09-26T12:01:00Z"), 60);
        when(api.transfer(1, 2, BalanceCurrency.COINS, 150_000, false)).thenReturn(CompletableFuture.completedFuture(
            new TransferOutcome(TransferOutcome.Status.PENDING_CONFIRMATION, null, false, BalanceCurrency.COINS, 150_000, 0, 1, "alice", 2, "bob",
                new Balances(1, 500_000, 0, 0), pending)));
        when(api.confirmTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ", false))
            .thenReturn(CompletableFuture.completedFuture(completed(150_000, 350_000)));

        service.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        service.confirm(alice, null);

        assertEquals("Send 150,000 coins to bob? [Confirm] [Cancel] (expires in 60s)", aliceSees.get(0));
        verify(api).confirmTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ", false);
        assertTrue(aliceSees.get(1).startsWith("§aSent §6150,000 coins"));

        service.confirm(alice, null); // the prompt is used up
        assertEquals("§cYou have no payment waiting for confirmation.", aliceSees.get(2));
    }

    @Test
    void joinBalanceLine_isTheBalanceLine_readFresh_orTheJoinNumbersWhenTheApiIsDown() {
        when(api.getBalances(1)).thenReturn(CompletableFuture.completedFuture(new Balances(1, 12_345, 67, 900)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

        assertEquals("§7Balance: §612,345 coins §7| §b67 gems", service.joinBalanceLine(alice.getUniqueId(), 1, 5000, 0).join());
        assertEquals(12_345, userCache.getStale(alice.getUniqueId()).orElseThrow().coins());
        assertEquals("§7Balance: §65,000 coins §7| §b3 gems", service.joinBalanceLine(alice.getUniqueId(), 1, 5000, 3).join());
    }

    @Test
    void aStaleConfirm_saysThePaymentWasAlreadySent_notSentAgain() {
        PendingTransfer pending = new PendingTransfer("01M3FHX1ZDRTAB76K01ZYK0PCZ", "Pending", BalanceCurrency.COINS, 150_000, 0, 2, "bob",
            Instant.parse("2026-09-26T12:01:00Z"), 60);
        when(api.transfer(1, 2, BalanceCurrency.COINS, 150_000, false)).thenReturn(CompletableFuture.completedFuture(
            new TransferOutcome(TransferOutcome.Status.PENDING_CONFIRMATION, null, false, BalanceCurrency.COINS, 150_000, 0, 1, "alice", 2, "bob",
                new Balances(1, 500_000, 0, 0), pending)));
        TransferOutcome replay = new TransferOutcome(TransferOutcome.Status.COMPLETED, "01M3", true, BalanceCurrency.COINS, 150_000, 0,
            1, "alice", 2, "bob", new Balances(1, 350_000, 0, 0), null);
        when(api.confirmTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ", false))
            .thenReturn(CompletableFuture.completedFuture(completed(150_000, 350_000)))
            .thenReturn(CompletableFuture.completedFuture(replay))
            .thenReturn(failed(CurrencyError.PENDING_TRANSFER_CLOSED, Map.of()));

        service.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        service.confirm(alice, "01M3FHX1ZDRTAB76K01ZYK0PCZ");
        service.confirm(alice, "01M3FHX1ZDRTAB76K01ZYK0PCZ"); // the [Confirm] button clicked again
        service.confirm(alice, "01M3FHX1ZDRTAB76K01ZYK0PCZ"); // e.g. cancelled: not open any more

        assertTrue(aliceSees.get(1).startsWith("§aSent §6150,000 coins"), aliceSees.get(1));
        assertEquals("§7That payment was already sent.", aliceSees.get(2));
        assertEquals("§cThat payment request is no longer open.", aliceSees.get(3));
    }

    @Test
    void aReplayedAnswerForTheOpenPrompt_isStillShownAsSent() {
        // The first attempt went through but its answer was lost; the client's retry gets the replay.
        PendingTransfer pending = new PendingTransfer("01M3FHX1ZDRTAB76K01ZYK0PCZ", "Pending", BalanceCurrency.COINS, 150_000, 0, 2, "bob",
            Instant.parse("2026-09-26T12:01:00Z"), 60);
        when(api.transfer(1, 2, BalanceCurrency.COINS, 150_000, false)).thenReturn(CompletableFuture.completedFuture(
            new TransferOutcome(TransferOutcome.Status.PENDING_CONFIRMATION, null, false, BalanceCurrency.COINS, 150_000, 0, 1, "alice", 2, "bob",
                new Balances(1, 500_000, 0, 0), pending)));
        when(api.confirmTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ", false)).thenReturn(CompletableFuture.completedFuture(
            new TransferOutcome(TransferOutcome.Status.COMPLETED, "01M3", true, BalanceCurrency.COINS, 150_000, 0,
                1, "alice", 2, "bob", new Balances(1, 350_000, 0, 0), null)));

        service.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        service.confirm(alice, null);

        assertTrue(aliceSees.get(1).startsWith("§aSent §6150,000 coins"), aliceSees.get(1));
    }

    /** A scheduler whose tasks run only when the test says so. */
    private static final class ManualScheduler implements PlayerCurrencyService.Scheduler {
        final List<Runnable> due = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();
        int cancelled;

        @Override
        public Runnable runLater(Duration delay, Runnable task) {
            delays.add(delay);
            Runnable[] slot = {task};
            due.add(() -> {
                if (slot[0] != null) {
                    slot[0].run();
                }
            });
            return () -> {
                slot[0] = null;
                cancelled++;
            };
        }

        void runAll() {
            new ArrayList<>(due).forEach(Runnable::run);
        }
    }

    private PlayerCurrencyService serviceWith(ManualScheduler scheduler) {
        return new PlayerCurrencyService(Runnable::run, api, usersDataAccess, userCache,
            (player, node) -> CompletableFuture.completedFuture(decide(node)),
            new VisiblePlayers(online::get, () -> (Collection<Player>) online.values()),
            new CurrencySettings(Map.of(), 0, 10, 8), Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC), scheduler);
    }

    private void askForConfirmation(int expiresInSeconds) {
        PendingTransfer pending = new PendingTransfer("01M3FHX1ZDRTAB76K01ZYK0PCZ", "Pending", BalanceCurrency.COINS, 150_000, 0, 2, "bob",
            Instant.parse("2026-09-26T12:01:00Z"), expiresInSeconds);
        when(api.transfer(1, 2, BalanceCurrency.COINS, 150_000, false)).thenReturn(CompletableFuture.completedFuture(
            new TransferOutcome(TransferOutcome.Status.PENDING_CONFIRMATION, null, false, BalanceCurrency.COINS, 150_000, 0, 1, "alice", 2, "bob",
                new Balances(1, 500_000, 0, 0), pending)));
    }

    @Test
    void anUnansweredConfirmation_tellsTheSenderItExpired_afterTheApisWindow() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerCurrencyService withTimer = serviceWith(scheduler);
        askForConfirmation(45);

        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        assertEquals(List.of(Duration.ofSeconds(46)), scheduler.delays); // the API's 45 s plus a second of grace
        scheduler.runAll();

        assertEquals("§7Your payment of §6150,000 coins §7to §ebob §7expired — nothing was paid.", aliceSees.get(1));
        withTimer.confirm(alice, null); // the prompt is gone
        assertEquals("§cYou have no payment waiting for confirmation.", aliceSees.get(2));
    }

    @Test
    void aConfirmedCancelledOrReplacedPrompt_getsNoExpiryNotice() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerCurrencyService withTimer = serviceWith(scheduler);
        askForConfirmation(60);
        when(api.confirmTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ", false))
            .thenReturn(CompletableFuture.completedFuture(completed(150_000, 350_000)));
        when(api.cancelTransfer(1, "01M3FHX1ZDRTAB76K01ZYK0PCZ")).thenReturn(CompletableFuture.completedFuture(
            new PendingTransfer("01M3FHX1ZDRTAB76K01ZYK0PCZ", "Cancelled", BalanceCurrency.COINS, 150_000, 0, 2, "bob", null, 0)));

        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        withTimer.confirm(alice, null);                                  // confirmed
        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        withTimer.cancel(alice, null);                                   // cancelled
        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);   // replaced by a newer prompt
        int seen = aliceSees.size();
        scheduler.runAll();

        assertEquals(seen + 1, aliceSees.size(), aliceSees.toString()); // only the newest prompt's notice
        assertTrue(aliceSees.get(seen).contains("expired"), aliceSees.get(seen));
    }

    @Test
    void aLeavingPlayersPrompt_isDropped() {
        ManualScheduler scheduler = new ManualScheduler();
        PlayerCurrencyService withTimer = serviceWith(scheduler);
        askForConfirmation(60);

        withTimer.pay(alice, "bob", "150000", BalanceCurrency.COINS);
        org.bukkit.event.player.PlayerQuitEvent quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(alice);
        withTimer.onQuit(quit);
        scheduler.runAll();

        assertEquals(1, scheduler.cancelled);
        assertEquals(1, aliceSees.size()); // just the prompt
    }

    @Test
    void ledgerLines_sayWhoAndWhy() {
        LedgerLine sent = new LedgerLine(1, "01M3", Instant.parse("2026-09-26T19:08:00Z"), BalanceCurrency.COINS, -1000, 2000, 1000,
            "Transfer", "PLAYER_TRANSFER", "Player transfer", "Player", "alice", null, 2, "bob");
        LedgerLine salary = new LedgerLine(2, "01M4", Instant.parse("2026-09-26T20:00:00Z"), BalanceCurrency.COINS, 650, 1000, 1650,
            "Grant", "SALARY", "Salary payout", "System", null, "SalaryService", null, null);

        assertEquals("§809-26 19:08 §c-1,000 coins §7to bob §8-> §f1,000", service.ledgerLine(sent));
        assertEquals("§809-26 20:00 §a+650 coins §7Salary payout §8-> §f1,650", service.ledgerLine(salary));
    }

    @Test
    void placeholderValues_cantCarryFormatting() {
        assertEquals("§aHi §eBobckid", CurrencySettings.fill("§aHi §e{p}", "p", "Bob§ckid"));
        assertEquals("x alb y", CurrencySettings.fill("x {v} y", "v", "a§lb"));
    }

    // ===== Staff: /knk currency (Phase 4) =====

    @Test
    void staffReverse_needsANoteAndTheNode_thenShowsTheServersResult() {
        service.staffReverse(alice, "01M3", "oops", false);
        assertEquals("§cSay why in at least 10 characters.", aliceSees.get(0));

        nodes.put(PlayerCurrencyService.CURRENCY_REVERSE_NODE, false);
        service.staffReverse(alice, "01M3", "Paid twice by a bug", false);
        assertEquals("§cYou don't have permission to do that.", aliceSees.get(1));
        verify(api, never()).reverseTransaction(anyInt(), anyString(), anyString(), anyBoolean());

        nodes.put(PlayerCurrencyService.CURRENCY_REVERSE_NODE, true);
        when(api.reverseTransaction(1, "01M3ABC", "Paid twice by a bug", true)).thenReturn(CompletableFuture.completedFuture(
            new ReversalOutcome("01M3ABC", "01M4XYZ", false, true, List.of(new ReversalOutcome.Leg(2, BalanceCurrency.COINS, -60, 0)),
                Map.of(2, new Balances(2, 0, 0, 0)))));

        service.staffReverse(alice, "tx01m3abc", "  Paid twice by a bug ", true);

        verify(api).reverseTransaction(1, "01M3ABC", "Paid twice by a bug", true);
        assertTrue(aliceSees.get(2).contains("01M3ABC") && aliceSees.get(2).contains("01M4XYZ"), aliceSees.get(2));
        assertTrue(aliceSees.get(3).contains("-60 coins"), aliceSees.get(3));
        assertEquals(0, userCache.getStale(bob.getUniqueId()).orElseThrow().coins()); // bob's cached balance follows the server
    }

    @Test
    void staffUserHistory_isGatedOnTheUserHistoryNode_throughThePermissionCheck() {
        nodes.put(PlayerCurrencyService.USER_HISTORY_NODE, false);
        service.staffUserHistory(alice, "bob", null, 1);
        assertEquals("§cYou don't have permission to do that.", aliceSees.get(0));
        verify(api, never()).getTransactions(anyInt(), any(), anyInt(), anyInt());

        nodes.put(PlayerCurrencyService.USER_HISTORY_NODE, true);
        when(api.getTransactions(2, BalanceCurrency.COINS, 1, 8)).thenReturn(CompletableFuture.completedFuture(
            new net.knightsandkings.knk.core.domain.currency.LedgerPage(List.of(), 0, 1, 8)));
        service.staffUserHistory(alice, "bob", BalanceCurrency.COINS, 1);
        verify(api).getTransactions(2, BalanceCurrency.COINS, 1, 8);
    }

    @Test
    void staffReverse_ofAnAlreadyReversedTransaction_saysWhenAndByWhom() {
        when(api.reverseTransaction(anyInt(), anyString(), anyString(), anyBoolean())).thenReturn(CompletableFuture.failedFuture(
            new CurrencyException(new CurrencyError("AlreadyReversed", "AlreadyReversed: Transaction 01M3 was already reversed.", Map.of(
                "reversalTransactionPublicId", "01M4", "reversedAt", "2026-09-27T10:15:00Z", "reversedByUserId", 42,
                "reversedByUsername", "Owner")), 409, null)));

        service.staffReverse(alice, "01M3", "Paid twice by a bug", false);

        assertEquals("§eTransaction §601M3§e was already reversed on §f2026-09-27 10:15 UTC§e by §fOwner§e (reversal §601M4§e).",
            aliceSees.get(0));
        assertEquals(1, aliceSees.size());
    }

    @Test
    void staffReverse_aReplayedAnswer_isNeverShownAsAFreshReversal() {
        when(api.reverseTransaction(1, "01M3", "Paid twice by a bug", false)).thenReturn(CompletableFuture.completedFuture(
            new ReversalOutcome("01M3", "01M4", true, false, List.of(new ReversalOutcome.Leg(2, BalanceCurrency.COINS, -60, 0)), Map.of())));

        service.staffReverse(alice, "01M3", "Paid twice by a bug", false);

        assertEquals(List.of("§7Transaction §e01M3§7 was already reversed (reversal §e01M4§7) - nothing changed now."), aliceSees);
    }

    @Test
    void staffReverse_refusalShowsTheServersMessage() {
        when(api.reverseTransaction(anyInt(), anyString(), anyString(), anyBoolean())).thenReturn(CompletableFuture.failedFuture(
            new CurrencyException(new CurrencyError("AlreadyReversed", "AlreadyReversed: Transaction 01M3 was already reversed by 01M4.", Map.of()), 409, null)));

        service.staffReverse(alice, "01M3", "Paid twice by a bug", false);

        assertEquals("§cTransaction 01M3 was already reversed by 01M4.", aliceSees.get(0));
    }

    @Test
    void staffLockAndUnlock_resolveThePlayer_andNeedTheLockNode() {
        when(api.lockTransfers(1, 2, "suspected alt funnel")).thenReturn(CompletableFuture.completedFuture(
            new TransferLock(2, "bob", true, "suspected alt funnel", Instant.parse("2026-09-26T12:00:00Z"))));
        when(api.unlockTransfers(1, 2)).thenReturn(CompletableFuture.completedFuture(new TransferLock(2, "bob", false, null, null)));

        service.staffLock(alice, "bob", " suspected alt funnel ");
        service.staffUnlock(alice, "bob");
        nodes.put(PlayerCurrencyService.CURRENCY_LOCK_NODE, false);
        service.staffUnlock(alice, "bob");

        assertEquals("§aLocked §ebob§a's payments: §fsuspected alt funnel", aliceSees.get(0));
        assertTrue(aliceSees.get(1).contains("bob"), aliceSees.get(1));
        assertEquals("§cYou don't have permission to do that.", aliceSees.get(2));
        verify(api).unlockTransfers(1, 2);
    }

    @Test
    void staffHistory_linesCarryTheirTransactionId() {
        when(api.getTransactions(2, BalanceCurrency.COINS, 1, 8)).thenReturn(CompletableFuture.completedFuture(new net.knightsandkings.knk.core.domain.currency.LedgerPage(
            List.of(new LedgerLine(1, "01M3ABC", Instant.parse("2026-09-26T11:00:00Z"), BalanceCurrency.COINS, 50, 0, 50, "Grant", "SALARY",
                "Salary", "System", null, "SalaryService", null, null)), 1, 1, 8)));

        service.staffHistory(alice, "bob", BalanceCurrency.COINS, 1);

        assertTrue(aliceSees.get(1).endsWith("#01M3ABC"), aliceSees.get(1));
    }

    // ===== Phase 5: currency alerts =====

    private static CurrencyAlert alert(long id, boolean acked) {
        return new CurrencyAlert(id, "R3", "Funnel", "High", "Received transfers from 5 new accounts", 2, "bob", null,
            Instant.parse("2026-09-26T11:30:00Z"), acked ? Instant.parse("2026-09-26T11:45:00Z") : null, acked ? "mod" : null);
    }

    @Test
    void staffAlerts_listsTheOpenAlertsWithAnAckButton_andNeedsTheNode() {
        when(api.getAlerts(false, 1, 8)).thenReturn(CompletableFuture.completedFuture(
            new CurrencyAlertPage(List.of(alert(7, false)), 1, 1, 8, 1)));
        when(api.getAlerts(true, 2, 8)).thenReturn(CompletableFuture.completedFuture(
            new CurrencyAlertPage(List.of(alert(6, true)), 9, 2, 8, 1)));

        service.staffAlerts(alice, false, 1);
        service.staffAlerts(alice, true, 2);
        nodes.put(PlayerCurrencyService.CURRENCY_ALERTS_NODE, false);
        service.staffAlerts(alice, false, 1);

        assertEquals("§6--- Currency alerts (open) - page 1/1, 1 open ---", aliceSees.get(0));
        assertEquals("#7 09-26 11:30 High R3 bob: Received transfers from 5 new accounts [ack]", strip(aliceSees.get(1)));
        assertEquals("§6--- Currency alerts (all) - page 2/2, 1 open ---", aliceSees.get(2));
        assertTrue(strip(aliceSees.get(3)).endsWith("(acked by mod)"), aliceSees.get(3));
        assertEquals("§cYou don't have permission to do that.", aliceSees.get(4));
        verify(api, org.mockito.Mockito.times(2)).getAlerts(anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void staffAcknowledgeAlert_sendsTheStaffMember_andExplainsAMissingAlert() {
        when(api.acknowledgeAlert(1, 7)).thenReturn(CompletableFuture.completedFuture(alert(7, true)));
        when(api.acknowledgeAlert(1, 8)).thenReturn(CompletableFuture.failedFuture(
            new CurrencyException(new CurrencyError("AlertNotFound", "Currency alert 8 doesn't exist.", Map.of()), 404, null)));

        service.staffAcknowledgeAlert(alice, "#7");
        service.staffAcknowledgeAlert(alice, "8");
        service.staffAcknowledgeAlert(alice, "seven");

        assertEquals("§aAlert §e#7§a (R3) acknowledged.", aliceSees.get(0));
        assertEquals("§cNo currency alert §e#8§c.", aliceSees.get(1));
        assertEquals("§cThe alert id is a number, e.g. /knk currency alerts ack 12.", aliceSees.get(2));
    }

    @Test
    void staffAcknowledgeAlert_fromTheConsole_isRefusedWithoutCallingTheApi() {
        org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);

        service.staffAcknowledgeAlert(console, "7");

        verify(api, never()).acknowledgeAlert(anyInt(), anyLong());
        verify(console).sendMessage(CurrencySettings.defaults().message("alert-ack-console"));
    }

    private static String strip(String text) {
        return text.replaceAll("§.", "");
    }
}

