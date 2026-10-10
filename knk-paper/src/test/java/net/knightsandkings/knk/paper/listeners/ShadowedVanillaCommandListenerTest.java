package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.junit.jupiter.api.Test;

/**
 * KNG-25: /minecraft:tell and /minecraft:w are left out of the client's command tree (so the client
 * stops signing a message the server doesn't expect signed), and ops are routed to /minecraft:msg.
 */
class ShadowedVanillaCommandListenerTest {

    private final ShadowedVanillaCommandListener listener = new ShadowedVanillaCommandListener();
    private final Player player = mock(Player.class);

    private PlayerCommandPreprocessEvent event(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void shadowedVanillaRedirectsAreNotSentToClients() {
        Collection<String> commands = new ArrayList<>(List.of(
            "msg", "tell", "w", "minecraft:msg", "minecraft:tell", "minecraft:w", "knightsandkings:tell", "minecraft:give"));

        listener.onCommandSend(new PlayerCommandSendEvent(player, commands));

        assertEquals(List.of("msg", "tell", "w", "minecraft:msg", "knightsandkings:tell", "minecraft:give"), List.copyOf(commands));
    }

    @Test
    void opsAreRoutedToVanillaMsgSoSelectorsKeepWorking() {
        when(player.isOp()).thenReturn(true);

        PlayerCommandPreprocessEvent tell = event("/minecraft:tell @a test");
        listener.onCommand(tell);
        verify(tell).setMessage("/minecraft:msg @a test");

        PlayerCommandPreprocessEvent w = event("/Minecraft:W __pandi__ hi there");
        listener.onCommand(w);
        verify(w).setMessage("/minecraft:msg __pandi__ hi there");

        PlayerCommandPreprocessEvent bare = event("/minecraft:w");
        listener.onCommand(bare);
        verify(bare).setMessage("/minecraft:msg");
    }

    @Test
    void nonOpsAndOtherCommandsAreLeftAlone() {
        PlayerCommandPreprocessEvent nonOp = event("/minecraft:tell Bob hi");
        listener.onCommand(nonOp);
        verify(nonOp, never()).setMessage(anyString());

        when(player.isOp()).thenReturn(true);
        for (String message : List.of("/tell Bob hi", "/w Bob hi", "/minecraft:msg @a hi", "/msg Bob hi")) {
            PlayerCommandPreprocessEvent other = event(message);
            listener.onCommand(other);
            verify(other, never()).setMessage(anyString());
        }
    }
}
