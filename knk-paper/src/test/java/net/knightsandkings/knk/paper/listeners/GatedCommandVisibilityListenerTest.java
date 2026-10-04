package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-24: gated commands are left out of the client's command list for players lacking the
 * in-house node, and the list is re-sent once when the real answer differs from the cold-cache one.
 */
class GatedCommandVisibilityListenerTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Player player = mock(Player.class);
    private GatedCommandVisibilityListener listener;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        listener = new GatedCommandVisibilityListener(CommandPermissions.of(permissible, Runnable::run), Runnable::run);
        listener.gate(List.of("staffchat", "sc"), "knk.staffchat");
        listener.gate(List.of("freeze"), "knk.freeze");
    }

    private Collection<String> send() {
        Collection<String> commands = new ArrayList<>(List.of(
            "msg", "staffchat", "sc", "knightsandkings:staffchat", "knightsandkings:sc", "freeze", "knightsandkings:freeze"));
        listener.onCommandSend(new PlayerCommandSendEvent(player, commands));
        return commands;
    }

    private void live(String node, PermissionDecision decision) {
        when(permissible.checkAsync(any(), eq(node))).thenReturn(CompletableFuture.completedFuture(decision));
    }

    @Test
    void hidesGatedCommandsFromPlayersWithoutTheNode() {
        live("knk.staffchat", PermissionDecision.DENIED);
        live("knk.freeze", PermissionDecision.DENIED);

        assertEquals(List.of("msg"), List.copyOf(send()));
        verify(player, never()).updateCommands();
    }

    @Test
    void showsGatedCommandsToHolders() {
        when(permissible.hasPermission(player, "knk.freeze")).thenReturn(true);
        live("knk.staffchat", PermissionDecision.DENIED);
        live("knk.freeze", PermissionDecision.ALLOWED);

        assertEquals(List.of("msg", "freeze", "knightsandkings:freeze"), List.copyOf(send()));
        verify(player, never()).updateCommands();
    }

    @Test
    void resendsOnceWhenTheColdCacheWasWrong() {
        // Join: nothing cached, so the cache-only check says no, but the real answer is yes.
        when(permissible.checkAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(PermissionDecision.ALLOWED));

        assertEquals(List.of("msg"), List.copyOf(send()));
        verify(player, times(1)).updateCommands();

        // The re-sent list uses the real answers, and agrees with the next refresh: no loop.
        assertEquals(7, send().size());
        verify(player, times(1)).updateCommands();
    }
}
