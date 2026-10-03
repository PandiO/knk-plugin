package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.LeaderboardsApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-34 link 5: {@code /leaderboard [board] [period]} ({@code /lb}). */
class LeaderboardCommandTest {

    private final LeaderboardsApi api = mock(LeaderboardsApi.class);
    private final UUID aliceId = UUID.randomUUID();
    private final List<LeaderboardBoard> boards = List.of(
            new LeaderboardBoard("active_playtime", "active_playtime", null, "Active playtime", "Seconds", List.of(), true),
            new LeaderboardBoard("pvp_kills@siege", "pvp_kills", "siege", "Player kills — Siege", "Count", List.of(), false));
    private final LeaderboardCommand command = new LeaderboardCommand(api, () -> CompletableFuture.completedFuture(boards),
            () -> boards, uuid -> uuid.equals(aliceId) ? 9 : null, Runnable::run);

    private Player alice() {
        Player alice = mock(Player.class);
        when(alice.getUniqueId()).thenReturn(aliceId);
        return alice;
    }

    private static String joined(CommandSender sender) {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).sendMessage(lines.capture());
        return String.join("\n", lines.getAllValues());
    }

    private static LeaderboardView view(LeaderboardView.ViewerEntry viewer) {
        return new LeaderboardView("pvp_kills@siege", "Player kills — Siege", "Count", "monthly", null, Instant.now(), 12,
                List.of(new LeaderboardView.Entry(1, 4, "dave", 1500), new LeaderboardView.Entry(2, 9, "Alice", 30)), viewer);
    }

    @Test
    void aBoardPrintsTheTopAndTheViewersRank() {
        Player alice = alice();
        when(api.getBoard("pvp_kills@siege", "monthly", 10, 9)).thenReturn(CompletableFuture.completedFuture(
                view(new LeaderboardView.ViewerEntry(2, 30))));

        command.onCommand(alice, mock(Command.class), "lb", new String[]{"pvp_kills@siege", "MONTHLY"});

        String out = joined(alice);
        assertTrue(out.contains("Player kills — Siege §7(this month)"), out);
        assertTrue(out.contains("#1 §fdave §7- §f1,500"), out);
        assertTrue(out.contains("You: §e#2 §7of 12 - §f30"), out);
    }

    @Test
    void noArgumentsOpensTheMenuForPlayers_AndListsBoardsForTheConsole() {
        Player alice = alice();
        @SuppressWarnings("unchecked")
        Consumer<Player> opener = mock(Consumer.class);
        command.setMenuOpener(opener);

        command.onCommand(alice, mock(Command.class), "leaderboard", new String[0]);
        verify(opener).accept(alice);

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        command.onCommand(console, mock(Command.class), "leaderboard", new String[0]);
        String out = joined(console);
        assertTrue(out.contains("pvp_kills@siege"), out);
    }

    @Test
    void badInputAndRefusalsAreExplained() {
        Player alice = alice();
        command.onCommand(alice, mock(Command.class), "lb", new String[]{"active_playtime", "daily"});
        when(api.getBoard("nope", "weekly", 10, 9)).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(new ApiException("u", 404, "Not Found", ""))));
        command.onCommand(alice, mock(Command.class), "lb", new String[]{"nope"});
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(api.getBoard("pvp_kills@siege", "weekly", 10, null)).thenReturn(CompletableFuture.failedFuture(
                new RuntimeException(new ApiException("u", 401, "Unauthorized", ""))));
        command.onCommand(console, mock(Command.class), "lb", new String[]{"pvp_kills@siege"});

        String out = joined(alice);
        assertTrue(out.contains("Period must be weekly, monthly or lifetime."), out);
        assertTrue(out.contains("No leaderboard 'nope'."), out);
        assertTrue(joined(console).contains("Only players can see that leaderboard."));
        verify(api, never()).getBoard("active_playtime", "daily", 10, 9);
    }

    @Test
    void tabCompletesBoardsThenPeriods() {
        assertEquals(List.of("pvp_kills@siege"), command.onTabComplete(alice(), mock(Command.class), "lb", new String[]{"pv"}));
        assertEquals(List.of("monthly"), command.onTabComplete(alice(), mock(Command.class), "lb", new String[]{"pvp_kills", "mo"}));
    }
}
