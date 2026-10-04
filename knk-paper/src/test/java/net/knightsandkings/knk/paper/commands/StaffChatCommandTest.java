package net.knightsandkings.knk.paper.commands;

import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-24: /staffchat reaches in-house holders of knk.staffchat, not only Bukkit (op) holders. */
class StaffChatCommandTest {

    @Test
    void broadcastsToInHouseHoldersAndTheSender() {
        Player sender = online("Sender");
        Player staff = online("Staff");
        Player player = online("Player");
        var command = new StaffChatCommand(
            (p, node) -> CompletableFuture.completedFuture(p == staff),
            Runnable::run,
            () -> List.of(sender, staff, player), p -> { });

        command.onCommand(sender, mock(Command.class), "sc", new String[] {"hello", "team"});

        verify(sender).sendMessage(contains("hello team"));
        verify(staff).sendMessage(contains("hello team"));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void aFailedCheckLeavesThatPlayerOut() {
        Player sender = online("Sender");
        Player staff = online("Staff");
        var command = new StaffChatCommand(
            (p, node) -> CompletableFuture.failedFuture(new IllegalStateException("down")),
            Runnable::run,
            () -> List.of(sender, staff), p -> { });

        command.onCommand(sender, mock(Command.class), "sc", new String[] {"hi"});

        verify(sender).sendMessage(contains("hi"));
        verify(staff, never()).sendMessage(anyString());
    }

    private static Player online(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }
}
