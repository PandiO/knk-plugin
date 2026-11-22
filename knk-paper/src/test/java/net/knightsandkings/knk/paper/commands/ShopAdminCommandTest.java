package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.production.shop.ShopService;

/** PandiO/knk-workspace#687: {@code /knk shop <id>} opens a shop's menu; off, non-player and bad ids are answered. */
class ShopAdminCommandTest {

    @Test
    void metadata_usesTheProductionNode() {
        assertEquals("knk.admin.world.production", ShopAdminCommand.metadata().permission());
        assertEquals("shop", ShopAdminCommand.metadata().name());
    }

    @Test
    void off_saysProductionIsOff() {
        CommandSender sender = mock(CommandSender.class);
        new ShopAdminCommand(() -> null).execute(sender, new String[] {"5"});
        verify(sender).sendMessage(org.mockito.ArgumentMatchers.contains("production.enabled: false"));
    }

    @Test
    void opensTheShopForAPlayer() {
        ShopService service = mock(ShopService.class);
        Player player = mock(Player.class);
        assertTrue(new ShopAdminCommand(() -> service).execute(player, new String[] {"5"}));
        verify(service).open(player, 5);
    }

    @Test
    void refusesConsoleAndBadIds() {
        ShopService service = mock(ShopService.class);
        CommandSender console = mock(CommandSender.class);
        new ShopAdminCommand(() -> service).execute(console, new String[] {"5"});
        verify(console).sendMessage(org.mockito.ArgumentMatchers.contains("Only players"));
        Player player = mock(Player.class);
        for (String[] args : new String[][] {{}, {"x"}, {"0"}, {"5", "6"}}) {
            new ShopAdminCommand(() -> service).execute(player, args);
        }
        verify(service, never()).open(any(), anyInt());
    }
}
