package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.knightsandkings.knk.paper.production.shop.ShopDisplayModule;

/**
 * Display stalls P12 (PandiO/knk-workspace#250): {@code /knk shop display add|remove|pin|list|reload}, under the same node
 * as {@code /knk shop} (L26); off, console, bad ids and the API's refusals are answered.
 */
class ShopDisplayCommandTest {

    private static final DisplaySpot SPOT = new DisplaySpot(3, 5, "world", 10, 64, -2, DisplaySpot.Facing.WEST, null, 1, "Front", true);

    private final ShopDisplayModule module = mock(ShopDisplayModule.class);
    private final ShopAdminCommand command = new ShopAdminCommand(() -> null, () -> module);

    @Test
    void addRegistersTheBlockLookedAt_withTheLabel() {
        Player player = mock(Player.class);
        when(module.register(player, 5, "Front shelf")).thenReturn(CompletableFuture.completedFuture(SPOT));
        command.execute(player, new String[] {"display", "add", "5", "Front", "shelf"});
        verify(module).register(player, 5, "Front shelf");
        verify(player).sendMessage(contains("registered for shop #5, facing west"));
    }

    @Test
    void addTellsTheApisRefusal_andNeedsAPlayerAndAShopId() {
        Player player = mock(Player.class);
        when(module.register(player, 5, null)).thenReturn(CompletableFuture.failedFuture(new ApiException("http://api.test", 409,
                "Conflict", "{\"error\":\"The block world 10 64 -2 is already a display spot.\"}")));
        command.execute(player, new String[] {"display", "add", "5"});
        verify(player).sendMessage(contains("already a display spot"));

        CommandSender console = mock(CommandSender.class);
        command.execute(console, new String[] {"display", "add", "5"});
        verify(console).sendMessage(contains("Only players"));
        command.execute(player, new String[] {"display", "add", "x"});
        verify(player).sendMessage(contains("Usage: /knk shop display add"));
    }

    @Test
    void removeTakesAnIdOrTheSpotLookedAt() {
        Player player = mock(Player.class);
        when(module.remove(3)).thenReturn(CompletableFuture.completedFuture(SPOT));
        command.execute(player, new String[] {"display", "remove", "3"});
        verify(module).remove(3);

        when(module.target(player)).thenReturn(Optional.of(SPOT));
        command.execute(player, new String[] {"display", "remove"});
        verify(module, org.mockito.Mockito.times(2)).remove(3);

        Player other = mock(Player.class);
        when(module.target(other)).thenReturn(Optional.empty());
        command.execute(other, new String[] {"display", "remove"});
        verify(other).sendMessage(contains("Usage: /knk shop display remove"));
    }

    @Test
    void pinSetsOrClearsTheEntry() {
        CommandSender sender = mock(CommandSender.class);
        DisplaySpot pinned = new DisplaySpot(3, 5, "world", 10, 64, -2, null, 41, 1, null, true);
        when(module.pin(3, 41)).thenReturn(CompletableFuture.completedFuture(pinned));
        when(module.pin(eq(3), isNull())).thenReturn(CompletableFuture.completedFuture(SPOT));
        command.execute(sender, new String[] {"display", "pin", "3", "41"});
        verify(sender).sendMessage(contains("now shows catalogue entry 41"));
        command.execute(sender, new String[] {"display", "pin", "3", "none"});
        verify(sender).sendMessage(contains("filled by the shop again"));
        command.execute(sender, new String[] {"display", "pin", "3"});
        verify(sender).sendMessage(contains("Usage: /knk shop display pin"));
    }

    @Test
    void listAndReload() {
        CommandSender sender = mock(CommandSender.class);
        when(module.describe(5)).thenReturn(List.of("#3 (world 10 64 -2) facing west: Bread"));
        command.execute(sender, new String[] {"display", "list", "5"});
        verify(sender).sendMessage(contains("facing west: Bread"));
        when(module.describe(6)).thenReturn(List.of());
        command.execute(sender, new String[] {"display", "list", "6"});
        verify(sender).sendMessage(contains("has no display spots"));
        when(module.reload("command")).thenReturn(CompletableFuture.completedFuture(4));
        command.execute(sender, new String[] {"display", "reload"});
        verify(sender).sendMessage(contains("Display spots re-read: 4"));
    }

    @Test
    void offOrUnknown_isAnswered() {
        CommandSender sender = mock(CommandSender.class);
        new ShopAdminCommand(() -> null).execute(sender, new String[] {"display", "list", "5"});
        verify(sender).sendMessage(contains("production.enabled: false"));
        command.execute(sender, new String[] {"display", "dance"});
        verify(sender).sendMessage(contains("Usage: /knk shop display"));
        verify(module, never()).remove(anyInt());
        verify(module, never()).register(any(), anyInt(), any());
    }

    @Test
    void completesTheDisplayActions() {
        CommandSender sender = mock(CommandSender.class);
        assertEquals(List.of("display"), command.complete(sender, new String[] {"d"}));
        assertEquals(List.of("pin"), command.complete(sender, new String[] {"display", "pi"}));
        assertEquals(List.of("none"), command.complete(sender, new String[] {"display", "pin", "3", ""}));
    }
}
