package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;
import net.knightsandkings.knk.paper.currency.VisiblePlayers;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.BackService;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportRequestService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;

/**
 * KNG-107: tab completion of the standalone commands offers only what the sender can run - nothing
 * without a node, exactly what one node allows - from the cached check (running still checks for real).
 */
class CompletionPermissionsTest {

    private final Set<String> granted = new HashSet<>();
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Map<String, Player> online = Map.of("alice", alice, "bob", bob);
    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
            name -> online.get(name.toLowerCase()), () -> List.of(alice, bob));
    private final VisibleTargetResolver targets = new VisibleTargetResolver(
            name -> online.get(name.toLowerCase()), () -> List.of(alice, bob), player -> false);
    private final Command command = mock(Command.class);

    CompletionPermissionsTest() {
        when(alice.canSee(any(Player.class))).thenReturn(true);
        when(permissible.hasPermission(any(Player.class), anyString()))
                .thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv ->
                CompletableFuture.completedFuture(PermissionDecision.of(granted.contains(inv.<String>getArgument(1)))));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    // ===== /ownermode, /staffmode =====

    @Test
    void mode_onlyForHoldersOfTheModeNode() {
        ModeCommand staffMode = new ModeCommand(mock(ModeService.class), ActiveMode.STAFF,
                (sender, node) -> granted.contains(node));
        assertEquals(List.of(), staffMode.onTabComplete(alice, command, "staffmode", new String[] {""}));
        granted.add(ModeService.OWNER_NODE);
        assertEquals(List.of(), staffMode.onTabComplete(alice, command, "staffmode", new String[] {""}));
        granted.add(ModeService.STAFF_NODE);
        assertTrue(staffMode.onTabComplete(alice, command, "staffmode", new String[] {""}).contains("on"));
    }

    // ===== /pay, /balance, /baltop, /transactions =====

    private PlayerCurrencyService currency() {
        PlayerCurrencyService currency = mock(PlayerCurrencyService.class);
        when(currency.holds(any(), anyString())).thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        when(currency.visiblePlayers()).thenReturn(new VisiblePlayers(name -> online.get(name.toLowerCase()), () -> List.of(alice, bob)));
        return currency;
    }

    @Test
    void currencyCommands_withoutNodes_suggestNothing() {
        PlayerCurrencyService currency = currency();
        assertEquals(List.of(), new PayCommand(currency).onTabComplete(alice, command, "pay", new String[] {""}));
        assertEquals(List.of(), new PayCommand(currency).onTabComplete(alice, command, "pay", new String[] {"Bob", "5", ""}));
        assertEquals(List.of(), new BalanceCommand(currency).onTabComplete(alice, command, "balance", new String[] {""}));
        assertEquals(List.of(), new BaltopCommand(currency).onTabComplete(alice, command, "baltop", new String[] {""}));
        assertEquals(List.of(), new TransactionsCommand(currency).onTabComplete(alice, command, "transactions", new String[] {""}));
    }

    @Test
    void currencyCommands_withTheOwnNodeOnly_noOtherPlayersOrGems() {
        PlayerCurrencyService currency = currency();
        granted.add(PlayerCurrencyService.PAY_NODE);
        granted.add(PlayerCurrencyService.TRANSACTIONS_NODE);
        granted.add(PlayerCurrencyService.BALANCE_NODE);
        assertEquals(List.of("coins"), new PayCommand(currency).onTabComplete(alice, command, "pay", new String[] {"Bob", "5", ""}));
        assertEquals(List.of("Bob"), new PayCommand(currency).onTabComplete(alice, command, "pay", new String[] {"b"}));
        assertEquals(List.of(), new BalanceCommand(currency).onTabComplete(alice, command, "balance", new String[] {"b"}));
        assertEquals(List.of(), new TransactionsCommand(currency).onTabComplete(alice, command, "transactions", new String[] {"b"}));
        assertEquals(List.of("coins"), new TransactionsCommand(currency).onTabComplete(alice, command, "transactions", new String[] {"c"}));

        granted.add(PlayerCurrencyService.BALANCE_OTHERS_NODE);
        assertEquals(List.of("Bob"), new BalanceCommand(currency).onTabComplete(alice, command, "balance", new String[] {"b"}));
    }

    @Test
    void currencyHolds_consoleAlways_playerThroughTheCachedCheck() {
        PlayerCurrencyService currency = new PlayerCurrencyService(Runnable::run, null, null, null,
                (player, node) -> CompletableFuture.completedFuture(PermissionDecision.ALLOWED),
                null, null, java.time.Clock.systemUTC());
        assertTrue(currency.holds(mock(CommandSender.class), PlayerCurrencyService.PAY_NODE));
        assertEquals(false, currency.holds(alice, PlayerCurrencyService.PAY_NODE));
        currency.setCachedPermissions((player, node) -> granted.contains(node));
        granted.add(PlayerCurrencyService.PAY_NODE);
        assertTrue(currency.holds(alice, PlayerCurrencyService.PAY_NODE));
    }

    // ===== /socialspy =====

    @Test
    void socialSpy_onlyForHolders() {
        SocialSpyCommand spy = new SocialSpyCommand(support, mock(net.knightsandkings.knk.paper.user.SpyService.class),
                java.util.logging.Logger.getAnonymousLogger());
        assertEquals(List.of(), spy.onTabComplete(alice, command, "socialspy", new String[] {""}));
        granted.add(PrivateMessageNodes.SOCIAL_SPY);
        assertEquals(List.of("on", "off"), spy.onTabComplete(alice, command, "socialspy", new String[] {""}));
    }

    // ===== /tpa, /tpahere =====

    @Test
    void tpa_namesOnlyWithTheRequestNode_acceptAndDenyAlways() {
        TeleportRequestService requests = mock(TeleportRequestService.class);
        TeleportRequestCommand tpa = new TeleportRequestCommand(TeleportRequestCommand.Form.TPA, support, targets, requests);
        TeleportRequestCommand tpahere = tpa.withForm(TeleportRequestCommand.Form.TPAHERE);

        assertEquals(List.of("accept"), tpa.onTabComplete(alice, command, "tpa", new String[] {"a"}));
        assertEquals(List.of(), tpahere.onTabComplete(alice, command, "tpahere", new String[] {"b"}));

        granted.add(TeleportNodes.REQUEST);
        assertTrue(tpa.onTabComplete(alice, command, "tpa", new String[] {"b"}).contains("Bob"));
        assertEquals(List.of(), tpahere.onTabComplete(alice, command, "tpahere", new String[] {"b"}));

        granted.add(TeleportNodes.REQUEST_HERE);
        assertEquals(List.of("Bob"), tpahere.onTabComplete(alice, command, "tpahere", new String[] {"b"}));
    }

    // ===== /back =====

    @Test
    void back_otherPlayersOnlyWithTheStaffNode_silentFlagOnlyWithItsNode() {
        BackCommand back = new BackCommand(support, mock(BackService.class), (sender, name, then) -> { }, targets, player -> false);
        assertEquals(List.of(), back.onTabComplete(alice, command, "back", new String[] {"b"}));

        granted.add(TeleportNodes.STAFF_BACK_OTHERS);
        assertEquals(List.of("Bob"), back.onTabComplete(alice, command, "back", new String[] {"b"}));
        assertEquals(List.of(), back.onTabComplete(alice, command, "back", new String[] {"Bob", ""}));

        granted.add(TeleportNodes.STAFF_SILENT);
        assertEquals(List.of("-s"), back.onTabComplete(alice, command, "back", new String[] {"Bob", ""}));
    }
}
