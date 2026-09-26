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
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LedgerLine;
import net.knightsandkings.knk.core.domain.currency.PendingTransfer;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
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

    private final PlayerCurrencyService service = new PlayerCurrencyService(
        Runnable::run, api, usersDataAccess, userCache,
        (player, node) -> CompletableFuture.completedFuture(nodes.getOrDefault(node, true)),
        new VisiblePlayers(online::get, () -> (Collection<Player>) online.values()),
        CurrencySettings.defaults(), Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC));

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
        when(usersDataAccess.getByUsernameAsync("bob")).thenReturn(CompletableFuture.completedFuture(
            FetchResult.<UserSummary>missFetched(new UserSummary(2, "bob", bob.getUniqueId(), 100))));
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
}
