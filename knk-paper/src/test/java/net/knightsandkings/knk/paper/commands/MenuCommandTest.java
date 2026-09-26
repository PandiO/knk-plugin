package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Content port CP1: {@code /menu} opens the hub as a fresh navigation root. */
class MenuCommandTest {

    @Test
    void opensTheHubWithAnEmptyContext() {
        MenuService service = mock(MenuService.class);
        Player player = mock(Player.class);

        new MenuCommand(() -> service).onCommand(player, mock(Command.class), "menu", new String[0]);

        verify(service).openMenu(player, "main", MenuContextParams.EMPTY);
    }

    @Test
    void consoleAndMissingEngineAreRefused() {
        MenuService service = mock(MenuService.class);
        CommandSender console = mock(CommandSender.class);
        new MenuCommand(() -> service).onCommand(console, mock(Command.class), "menu", new String[0]);
        verify(service, never()).openMenu(any(), anyString(), any());
        verify(console).sendMessage(contains("Only players"));

        Player player = mock(Player.class);
        new MenuCommand(() -> null).onCommand(player, mock(Command.class), "menu", new String[0]);
        verify(player).sendMessage(contains("unavailable"));
    }
}
