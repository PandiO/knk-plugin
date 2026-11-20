package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.shop.SiegeShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /siege shop} (siege shop L3, PandiO/knk-workspace#868): with {@code siege.shop.enabled} off it is neither
 * suggested nor in the help and only says the shop is off; with it on it opens the shop of the player's match (A17).
 */
class SiegeCommandShopTest {

    private final SiegeService service = mock(SiegeService.class);
    private final Player player = mock(Player.class);
    private final Command command = mock(Command.class);

    SiegeCommandShopTest() {
        when(player.getUniqueId()).thenReturn(new UUID(0, 1));
        when(service.hasPermission(any(Player.class), anyString())).thenReturn(true);
    }

    private String lastMessage() {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(message.capture());
        String text = PlainTextComponentSerializer.plainText().serialize(message.getValue());
        return text.startsWith("[Siege] ") ? text.substring("[Siege] ".length()) : text;
    }

    @Test
    void withTheShopOffTheSubcommandIsHiddenAndSaysSo() {
        SiegeCommand siege = new SiegeCommand(service);
        assertFalse(siege.onTabComplete(player, command, "siege", new String[] {""}).contains("shop"));

        siege.onCommand(player, command, "siege", new String[] {"shop"});
        assertTrue(lastMessage().endsWith("The siege shop is not enabled on this server."));

        siege.onCommand(player, command, "siege", new String[] {"help"});
        assertFalse(lastMessage().contains("shop"));
    }

    @Test
    void withTheShopOnTheSubcommandOpensTheShop_A17() {
        SiegeShopService shop = mock(SiegeShopService.class);
        when(shop.open(player)).thenReturn(null); // opened
        SiegeCommand siege = new SiegeCommand(service);
        siege.setShop(shop);

        assertTrue(siege.onTabComplete(player, command, "siege", new String[] {"sh"}).contains("shop"));
        siege.onCommand(player, command, "siege", new String[] {"shop"});
        verify(shop).open(player);
        verify(player, never()).sendMessage(any(Component.class));

        when(shop.open(player)).thenReturn(Component.text("The siege shop is open only during a running siege match."));
        siege.onCommand(player, command, "siege", new String[] {"shop"});
        assertEquals("The siege shop is open only during a running siege match.", lastMessage());
        siege.onCommand(player, command, "siege", new String[] {"help"});
        assertTrue(lastMessage().contains("| shop"));
        assertEquals(List.of("shop"), siege.onTabComplete(player, command, "siege", new String[] {"sho"}));
    }
}
