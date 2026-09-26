package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;

/**
 * KNG-18 Phase 1: vanilla messaging side doors (DESIGN.md §3.3.9) and /msg + /r staying open for a
 * frozen player.
 */
class VanillaMessagingBlockListenerTest {

    private final VanillaMessagingBlockListener listener = new VanillaMessagingBlockListener();
    private final Player player = mock(Player.class);

    VanillaMessagingBlockListenerTest() {
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Alice".getBytes()));
    }

    private PlayerCommandPreprocessEvent event(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void namespacedVanillaMessages_areRewrittenToMsg() {
        for (String label : new String[] {"minecraft:msg", "minecraft:tell", "Minecraft:W"}) {
            PlayerCommandPreprocessEvent event = event("/" + label + " Bob hi there");

            listener.onCommand(event);

            verify(event).setMessage("/msg Bob hi there");
            verify(event, never()).setCancelled(anyBoolean());
        }
    }

    @Test
    void rewriteWithoutArguments_givesMsgUsage() {
        PlayerCommandPreprocessEvent event = event("/minecraft:tell");

        listener.onCommand(event);

        verify(event).setMessage("/msg");
    }

    @Test
    void teamMessagesAndMe_areCancelled() {
        for (String label : new String[] {"teammsg", "tm", "minecraft:teammsg", "minecraft:tm", "me", "minecraft:me", "TM"}) {
            PlayerCommandPreprocessEvent event = event("/" + label + " hi");

            listener.onCommand(event);

            verify(event).setCancelled(true);
            verify(event, never()).setMessage(anyString());
        }
        verify(player, org.mockito.Mockito.times(7)).sendMessage(org.bukkit.ChatColor.RED + "That command is disabled.");
    }

    @Test
    void otherCommands_areUntouched() {
        for (String message : new String[] {"/msg Bob hi", "/tell Bob hi", "/w Bob hi", "/menu", "/meow", "/tmux"}) {
            PlayerCommandPreprocessEvent event = event(message);

            listener.onCommand(event);

            verify(event, never()).setCancelled(anyBoolean());
            verify(event, never()).setMessage(anyString());
        }
    }

    @Test
    void ops_bypass() {
        when(player.isOp()).thenReturn(true);
        PlayerCommandPreprocessEvent tm = event("/tm hi");
        PlayerCommandPreprocessEvent tell = event("/minecraft:tell Bob hi");

        listener.onCommand(tm);
        listener.onCommand(tell);

        verify(tm, never()).setCancelled(anyBoolean());
        verify(tell, never()).setMessage(anyString());
    }

    @Test
    void label_normalisation() {
        assertEquals("minecraft:tell", VanillaMessagingBlockListener.label("/Minecraft:Tell Bob hi"));
        assertEquals("r", VanillaMessagingBlockListener.label("/r"));
        assertEquals("msg", VanillaMessagingBlockListener.label("msg Bob"));
    }

    // ===== frozen players (AdminFreezeListener) =====

    @Test
    void privateMessageCommands_recognisedWithAliasesAndOurNamespace() {
        for (String message : new String[] {"/msg Staff hi", "/MSG Staff hi", "/tell Staff hi", "/w Staff hi", "/m Staff hi",
                "/pm Staff hi", "/message Staff hi", "/whisper Staff hi", "/r ok", "/reply ok", "/knightsandkings:msg Staff hi"}) {
            assertTrue(AdminFreezeListener.isPrivateMessageCommand(message), message);
        }
        for (String message : new String[] {"/spawn", "/minecraft:msg Staff hi", "/staffchat hi", "/menu", "/msgx"}) {
            assertFalse(AdminFreezeListener.isPrivateMessageCommand(message), message);
        }
    }

    @Test
    void frozenPlayer_mayUseMsgAndReply_notOtherCommands() {
        AdminFreezeManager freeze = new AdminFreezeManager();
        freeze.freeze(player.getUniqueId(), "test");
        AdminFreezeListener freezeListener = new AdminFreezeListener(mock(Plugin.class), freeze, mock(UsersDataAccess.class));
        PlayerCommandPreprocessEvent msg = event("/msg Staff sorry");
        PlayerCommandPreprocessEvent spawn = event("/spawn");

        freezeListener.onCommand(msg);
        freezeListener.onCommand(spawn);

        verify(msg, never()).setCancelled(anyBoolean());
        verify(spawn).setCancelled(true);
    }
}
