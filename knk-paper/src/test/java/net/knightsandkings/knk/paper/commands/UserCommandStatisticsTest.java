package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-34 link 5 / acceptance criterion 3: {@code /stats [player]} continues with the gameplay
 * statistics the API shows to the viewer - read acting as the player (the console as an anonymous
 * visitor); a failed read only drops those lines.
 */
class UserCommandStatisticsTest {

    private final UsersQueryApi usersQueryApi = mock(UsersQueryApi.class);
    private final UsersDataAccess usersDataAccess = mock(UsersDataAccess.class);
    private final UserCache userCache = mock(UserCache.class);
    private final TitleBracketsDataAccess titleBrackets = mock(TitleBracketsDataAccess.class);
    private final UserCommand command = new UserCommand(Runnable::run, usersQueryApi, usersDataAccess, userCache,
            titleBrackets, mock(VisiblePlayers.class));
    private final List<Integer[]> reads = new ArrayList<>();

    UserCommandStatisticsTest() {
        when(titleBrackets.listAsync()).thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    private static UserSummary user(int id, String name) {
        return new UserSummary(id, name, UUID.nameUUIDFromBytes(name.getBytes()), null, 10, 7, 150, true, false,
                GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, "server-title", 0, null, null, null, false, null, null);
    }

    private static PlayerStatistics bobAsSeenByOthers() {
        return new PlayerStatistics(2, "Bob", "lifetime", null, null, "Europe/Amsterdam", "signedIn",
                new PlayerStatistics.Profile("Squire", 150, 10, 7, Instant.parse("2026-09-01T10:00:00Z"), 3_700, 120),
                List.of(new PlayerStatistics.Metric("active_playtime", "active_playtime", 3700d, "Seconds", "Sum", List.of()),
                        new PlayerStatistics.Metric("pvp_kills", "pvp_kills", null, "Count", "Sum",
                                List.of(new PlayerStatistics.ContextValue("open_world", 4)))),
                null, null);
    }

    private static String joined(CommandSender sender) {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(lines.capture());
        return String.join("\n", lines.getAllValues());
    }

    @Test
    void anotherPlayersStatisticsAreReadActingAsTheViewer() {
        Player alice = mock(Player.class);
        when(alice.getName()).thenReturn("Alice");
        when(alice.getUniqueId()).thenReturn(UUID.randomUUID());
        when(userCache.getStale(alice.getUniqueId())).thenReturn(Optional.of(user(1, "Alice")));
        when(usersDataAccess.getByUsernameAsync("Bob")).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(user(2, "Bob"))));
        command.setStatisticsReader((target, acting) -> {
            reads.add(new Integer[]{target, acting});
            return CompletableFuture.completedFuture(bobAsSeenByOthers());
        });

        command.onCommand(alice, mock(Command.class), "stats", new String[]{"statistics", "Bob"});

        assertEquals(1, reads.size());
        assertEquals(2, reads.get(0)[0]);
        assertEquals(1, reads.get(0)[1]);
        String out = joined(alice);
        assertTrue(out.contains("Active playtime: §f1h 1m §8| §7AFK: §f2m"), out);
        assertTrue(out.contains("First joined: §f1 Sep 2026"), out);
        assertTrue(out.contains("Player kills: §fOpen world 4"), out); // hidden total, visible context
        assertFalse(out.contains("Period:"), out);
    }

    @Test
    void theConsoleReadsAsAnAnonymousVisitor_AndAFailedReadDropsOnlyTheStatisticsLines() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(usersDataAccess.getByUsernameAsync("Bob")).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(user(2, "Bob"))));
        command.setStatisticsReader((target, acting) -> {
            reads.add(new Integer[]{target, acting});
            return CompletableFuture.failedFuture(new RuntimeException("down"));
        });

        command.onCommand(console, mock(Command.class), "user", new String[]{"stats", "Bob"});

        assertEquals(null, reads.get(0)[1]);
        String out = joined(console);
        assertTrue(out.contains("Statistics: §fBob"), out);
        assertFalse(out.contains("Active playtime"), out);
    }

    @Test
    void appendStatistics_AddsTheGroupsWithSomethingVisible() {
        List<String> lines = UserCommand.appendStatistics(List.of("&6--- Statistics: &fBob &6---"), bobAsSeenByOthers());

        assertEquals("&6--- Statistics: &fBob &6---", lines.get(0));
        assertTrue(lines.contains("&6Combat"), lines.toString());
        assertTrue(lines.contains(" &7Player kills: &fOpen world 4"), lines.toString());
        assertFalse(lines.contains("&6Activity"), lines.toString()); // lifetime playtime is already in the profile line
        assertEquals(List.of("x"), UserCommand.appendStatistics(List.of("x"), null));
    }
}
