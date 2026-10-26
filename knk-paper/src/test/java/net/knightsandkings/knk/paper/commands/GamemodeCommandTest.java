package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.commands.support.CommandNodes;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#158: /gamemode - mode parsing, the node per mode and the extra node for another player. */
class GamemodeCommandTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final List<String> checkedNodes = new ArrayList<>();
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Map<String, Player> online = Map.of("alice", alice, "bob", bob);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
            name -> online.get(name.toLowerCase()), () -> List.of(alice, bob), () -> List.of("Alice", "Bob"));
    private final GamemodeCommand command = new GamemodeCommand(support);

    GamemodeCommandTest() {
        when(alice.canSee(bob)).thenReturn(true);
        when(permissible.hasPermission(any(Player.class), anyString()))
                .thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv -> {
            String node = inv.getArgument(1);
            checkedNodes.add(node);
            return CompletableFuture.completedFuture(PermissionDecision.of(granted.contains(node)));
        });
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "gamemode", args);
    }

    @Test
    void setsYourOwnModeOnThatModesNode() {
        granted.add("knk.gamemode.creative");

        run(alice, "c");

        assertEquals(List.of("knk.gamemode.creative"), checkedNodes);
        verify(alice).setGameMode(GameMode.CREATIVE);
    }

    @Test
    void anotherModesNodeIsNotEnough() {
        granted.add("knk.gamemode.spectator");

        run(alice, "creative");

        verify(alice, never()).setGameMode(any());
        verify(alice).sendMessage(contains("don't have permission"));
    }

    @Test
    void anotherPlayerNeedsTheModeAndTheOthersNode() {
        granted.add("knk.gamemode.survival");

        run(alice, "survival", "Bob");

        assertEquals(List.of("knk.gamemode.survival", "knk.gamemode.others"), checkedNodes);
        verify(bob, never()).setGameMode(any());

        granted.add(GamemodeCommand.NODE_OTHERS);
        run(alice, "0", "Bob");

        verify(bob).setGameMode(GameMode.SURVIVAL);
        verify(bob).sendMessage(contains("Alice"));
    }

    @Test
    void namingYourselfIsTheSelfForm() {
        granted.add("knk.gamemode.spectator");

        run(alice, "sp", "alice");

        assertEquals(List.of("knk.gamemode.spectator"), checkedNodes);
        verify(alice).setGameMode(GameMode.SPECTATOR);
    }

    @Test
    void anUnknownModeChecksNothing() {
        run(alice, "hardcore");

        assertTrue(checkedNodes.isEmpty());
        verify(alice).sendMessage(contains("Unknown game mode"));
    }

    @Test
    void theConsoleNeedsATargetAndSkipsTheNodes() {
        CommandSender console = mock(CommandSender.class);

        run(console, "adventure");
        run(console, "adventure", "Bob");

        verify(console).sendMessage(contains("Only players"));
        verify(bob).setGameMode(GameMode.ADVENTURE);
        assertTrue(checkedNodes.isEmpty());
    }

    @Test
    void modeWords() {
        assertEquals(GameMode.SURVIVAL, GamemodeCommand.parseMode("S"));
        assertEquals(GameMode.CREATIVE, GamemodeCommand.parseMode("1"));
        assertEquals(GameMode.ADVENTURE, GamemodeCommand.parseMode("adventure"));
        assertEquals(GameMode.SPECTATOR, GamemodeCommand.parseMode("3"));
        assertNull(GamemodeCommand.parseMode("Bob"));
    }

    @Test
    void tabCompletionOffersOnlyHeldModesAndPlayersWithTheOthersNode() {
        granted.add("knk.gamemode.survival");
        granted.add("knk.gamemode.spectator");

        assertEquals(List.of("survival", "spectator"), command.onTabComplete(alice, mock(Command.class), "gm", new String[] {""}));
        assertEquals(List.of(), command.onTabComplete(alice, mock(Command.class), "gm", new String[] {"survival", ""}));
    }

    @Test
    void theCommandIsHiddenFromPlayersWithoutAnyGamemodeNode() {
        assertEquals(GamemodeCommand.NODES, CommandNodes.anyOf("gamemode"));
        assertTrue(GamemodeCommand.NODES.contains("knk.gamemode.creative"));
    }
}
