package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an admin freeze locks for the frozen player (staff player profile smoke test § 2 step 3, the game half: "a
 * frozen player can't move, chat or use commands"; docs/specs/private-messages/DESIGN.md §3.3.9): walking to another
 * block, chat, commands other than /msg and /r, self-made teleports and damage. A player who isn't frozen keeps all
 * of it. The freeze arriving from the web app (realtime push) and the chat lines in a real client stay manual.
 */
class AdminFreezeListenerTest {

    private final UUID frozenId = UUID.randomUUID();
    private final UUID freeId = UUID.randomUUID();
    // Kept in a field: a Location holds its world only weakly (knk-plugin CLAUDE.md, "Tests").
    private final World world = mock(World.class);
    private AdminFreezeListener listener;
    private Player frozen;
    private Player free;

    @BeforeEach
    void setUp() {
        AdminFreezeManager freezes = mock(AdminFreezeManager.class);
        when(freezes.isFrozen(frozenId)).thenReturn(true);
        when(freezes.isFrozen(freeId)).thenReturn(false);
        listener = new AdminFreezeListener(mock(Plugin.class), freezes, mock(UsersDataAccess.class));
        frozen = player(frozenId);
        free = player(freeId);
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("P-" + id.toString().substring(0, 4));
        return player;
    }

    /** The recipients constructor: the two-argument one asks the (absent) server for its online players. */
    private static PlayerCommandPreprocessEvent command(Player player, String message) {
        return new PlayerCommandPreprocessEvent(player, message, new java.util.HashSet<>());
    }

    @Test
    void aFrozenPlayerCantWalkToAnotherBlock() {
        Location from = new Location(world, 10.5, 64, 10.5);
        PlayerMoveEvent event = new PlayerMoveEvent(frozen, from, new Location(world, 11.5, 64, 10.5));

        listener.onMove(event);

        assertEquals(from, event.getTo());
    }

    @Test
    void aFrozenPlayerCanStillLookAround() {
        Location to = new Location(world, 10.7, 64, 10.2, 90, 15);
        PlayerMoveEvent event = new PlayerMoveEvent(frozen, new Location(world, 10.5, 64, 10.5), to);

        listener.onMove(event);

        assertEquals(to, event.getTo());
    }

    @Test
    void aPlayerWhoIsntFrozenWalksFreely() {
        Location to = new Location(world, 11.5, 64, 10.5);
        PlayerMoveEvent event = new PlayerMoveEvent(free, new Location(world, 10.5, 64, 10.5), to);

        listener.onMove(event);

        assertEquals(to, event.getTo());
    }

    @Test
    void aFrozenPlayersChatIsRefusedWithAReason() {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, frozen, "let me go", Set.of());

        listener.onChat(event);

        assertTrue(event.isCancelled());
        verify(frozen).sendMessage(contains("You are frozen and cannot chat."));
    }

    @Test
    void aPlayerWhoIsntFrozenChats() {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, free, "hello", Set.of());

        listener.onChat(event);

        assertFalse(event.isCancelled());
        verify(free, never()).sendMessage(anyString());
    }

    @Test
    void aFrozenPlayersCommandsAreRefused_ButMsgAndReplyPass() {
        PlayerCommandPreprocessEvent spawn = command(frozen, "/spawn");
        PlayerCommandPreprocessEvent namespaced = command(frozen, "/knightsandkings:navigate town");
        PlayerCommandPreprocessEvent msg = command(frozen, "/msg Staff why?");
        PlayerCommandPreprocessEvent reply = command(frozen, "/r ok");
        PlayerCommandPreprocessEvent namespacedMsg = command(frozen, "/knightsandkings:msg Staff hi");

        listener.onCommand(spawn);
        listener.onCommand(namespaced);
        listener.onCommand(msg);
        listener.onCommand(reply);
        listener.onCommand(namespacedMsg);

        assertTrue(spawn.isCancelled());
        assertTrue(namespaced.isCancelled());
        assertFalse(msg.isCancelled());
        assertFalse(reply.isCancelled());
        assertFalse(namespacedMsg.isCancelled());
        verify(frozen, org.mockito.Mockito.times(2))
            .sendMessage(contains("You are frozen and cannot use commands. You can still /msg staff."));
    }

    @Test
    void aPlayerWhoIsntFrozenRunsCommands() {
        PlayerCommandPreprocessEvent event = command(free, "/spawn");

        listener.onCommand(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void aFrozenPlayerCantTeleportThemselves_ButStaffCanMoveThem() {
        Location from = new Location(world, 0, 64, 0);
        Location to = new Location(world, 50, 64, 50);
        PlayerTeleportEvent pearl = new PlayerTeleportEvent(frozen, from, to, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        PlayerTeleportEvent portal = new PlayerTeleportEvent(frozen, from, to, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
        PlayerTeleportEvent byCommand = new PlayerTeleportEvent(frozen, from, to, PlayerTeleportEvent.TeleportCause.COMMAND);

        listener.onTeleport(pearl);
        listener.onTeleport(portal);
        listener.onTeleport(byCommand);

        assertTrue(pearl.isCancelled());
        assertTrue(portal.isCancelled());
        assertFalse(byCommand.isCancelled());
    }

    @Test
    void aFrozenPlayerTakesNoDamage_OthersDo() {
        EntityDamageEvent onFrozen = mock(EntityDamageEvent.class);
        when(onFrozen.getEntity()).thenReturn(frozen);
        EntityDamageEvent onFree = mock(EntityDamageEvent.class);
        when(onFree.getEntity()).thenReturn(free);

        listener.onDamage(onFrozen);
        listener.onDamage(onFree);

        verify(onFrozen).setCancelled(true);
        verify(onFree, never()).setCancelled(true);
    }
}
