package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-9: {@code /user statistics|stats [player]}. */
class UserCommandTest {

    private static final List<TitleBracket> BRACKETS = List.of(
            new TitleBracket(1, "Peasant", "Peasant", 0, 10, 0, 0, 0),
            new TitleBracket(2, "Squire", "Maid", 100, 20, 5, 1, 0),
            new TitleBracket(3, "Knight", "Dame", 300, 40, 10, 2, 5));

    private final UsersQueryApi usersQueryApi = mock(UsersQueryApi.class);
    private final UsersDataAccess usersDataAccess = mock(UsersDataAccess.class);
    private final UserCache userCache = mock(UserCache.class);
    private final TitleBracketsDataAccess titleBrackets = mock(TitleBracketsDataAccess.class);
    private final UserCommand command = new UserCommand(Runnable::run, usersQueryApi, usersDataAccess, userCache,
            titleBrackets, () -> List.of("Alice", "Bob"));

    UserCommandTest() {
        when(titleBrackets.listAsync()).thenReturn(CompletableFuture.completedFuture(BRACKETS));
    }

    private static UserSummary user(String name, int coins, int xp, Integer bracketId, ActiveMode mode, boolean fullAccount) {
        return new UserSummary(9, name, UUID.nameUUIDFromBytes(name.getBytes()), null, coins, 7, xp, fullAccount, false,
                GatePassThroughMethod.DEFAULT, mode, bracketId, "server-title", 0, null, null, null, false, null, null);
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        return player;
    }

    private static String joined(CommandSender sender) {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(lines.capture());
        return String.join("\n", lines.getAllValues());
    }

    @Test
    void ownStatisticsShowBalancesProgressAndAccount() {
        Player alice = player("Alice");
        when(usersQueryApi.getByUuid(alice.getUniqueId()))
                .thenReturn(CompletableFuture.completedFuture(user("Alice", 1234, 150, 2, ActiveMode.STAFF, false)));

        command.onCommand(alice, mock(Command.class), "user", new String[]{"stats"});

        String out = joined(alice);
        assertTrue(out.contains("Statistics: §fAlice"), out);
        assertTrue(out.contains("Title: §fSquire"), out);
        assertTrue(out.contains("Coins: §61234"), out);
        assertTrue(out.contains("Knight"), out); // next title
        assertTrue(out.contains("Minecraft only"), out);
        assertTrue(out.contains("Mode: §fStaff"), out);
    }

    @Test
    void ownStatisticsFallBackToTheCacheWhenTheApiIsDown() {
        Player alice = player("Alice");
        when(usersQueryApi.getByUuid(alice.getUniqueId())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        when(userCache.getStale(alice.getUniqueId())).thenReturn(Optional.of(user("Alice", 55, 0, 1, ActiveMode.NONE, true)));

        command.onCommand(alice, mock(Command.class), "user", new String[]{"statistics"});

        String out = joined(alice);
        assertTrue(out.contains("Coins: §655"), out);
        assertTrue(out.contains("web account linked"), out);
        assertFalse(out.contains("Mode:"), out);
    }

    @Test
    void anotherPlayersStatisticsLeaveOutBalancesAndAccount() {
        Player alice = player("Alice");
        when(usersDataAccess.getByUsernameAsync("Bob"))
                .thenReturn(CompletableFuture.completedFuture(FetchResult.hit(user("Bob", 999, 320, 3, ActiveMode.OWNER, true))));

        command.onCommand(alice, mock(Command.class), "user", new String[]{"stats", "Bob"});

        String out = joined(alice);
        assertTrue(out.contains("Statistics: §fBob"), out);
        assertTrue(out.contains("Title: §fKnight"), out);
        assertTrue(out.contains("Experience: §f320"), out);
        assertFalse(out.contains("Coins"), out);
        assertFalse(out.contains("Gems"), out);
        assertFalse(out.contains("Account"), out);
        assertFalse(out.contains("Mode"), out); // would reveal an owner/staff in vanish
    }

    @Test
    void theConsoleSeesEverythingForANamedPlayer() {
        CommandSender console = mock(CommandSender.class);
        when(usersDataAccess.getByUsernameAsync("Bob"))
                .thenReturn(CompletableFuture.completedFuture(FetchResult.hit(user("Bob", 999, 320, 3, ActiveMode.NONE, true))));

        command.onCommand(console, mock(Command.class), "user", new String[]{"stats", "Bob"});

        assertTrue(joined(console).contains("Coins: §6999"));
    }

    @Test
    void anUnknownPlayerIsReported() {
        Player alice = player("Alice");
        when(usersDataAccess.getByUsernameAsync("Nobody"))
                .thenReturn(CompletableFuture.completedFuture(FetchResult.notFound()));

        command.onCommand(alice, mock(Command.class), "user", new String[]{"stats", "Nobody"});

        verify(alice).sendMessage(contains("No player found named 'Nobody'"));
    }

    @Test
    void namingYourselfShowsTheFullView() {
        Player alice = player("Alice");
        when(usersQueryApi.getByUuid(alice.getUniqueId()))
                .thenReturn(CompletableFuture.completedFuture(user("Alice", 42, 0, 1, ActiveMode.NONE, true)));

        command.onCommand(alice, mock(Command.class), "user", new String[]{"stats", "alice"});

        assertTrue(joined(alice).contains("Coins: §642"));
        verify(usersDataAccess, never()).getByUsernameAsync(anyString());
    }

    @Test
    void anythingElsePrintsUsage() {
        Player alice = player("Alice");

        command.onCommand(alice, mock(Command.class), "user", new String[0]);
        command.onCommand(alice, mock(Command.class), "user", new String[]{"list"});

        verify(alice, atLeastOnce()).sendMessage(contains("Usage: /user statistics [player]"));
    }

    @Test
    void tabCompletesSubcommandsThenOnlinePlayers() {
        Player alice = player("Alice");
        assertEquals(List.of("statistics", "stats"), command.onTabComplete(alice, mock(Command.class), "user", new String[]{"st"}));
        assertEquals(List.of("Bob"), command.onTabComplete(alice, mock(Command.class), "user", new String[]{"stats", "b"}));
    }
}
