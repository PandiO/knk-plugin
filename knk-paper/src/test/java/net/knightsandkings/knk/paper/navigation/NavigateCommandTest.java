package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** /navigate parsing, permission, ambiguity, the unknown-name hint, stop, and tab completion (DESIGN §6.1). */
class NavigateCommandTest {

    private final NavigationService service = mock(NavigationService.class);
    private final NavigationDestinations destinations = mock(NavigationDestinations.class);
    private final Command command = mock(Command.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private NavigateCommand navigate;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(true);
        navigate = new NavigateCommand(() -> service, () -> destinations, (p, node) -> false, Runnable::run);
    }

    private String lastMessage() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        return PlainTextComponentSerializer.plainText().serialize(captor.getValue());
    }

    @Test
    void parsingSplitsTheTrailingMode() {
        assertEquals(new NavigateCommand.Parsed("Kardenna Mill", Mode.REGION), NavigateCommand.parse(new String[] {"Kardenna", "Mill", "region"}));
        assertEquals(new NavigateCommand.Parsed("Kardenna", Mode.SPAWN), NavigateCommand.parse(new String[] {"Kardenna", "SPAWN"}));
        assertEquals(new NavigateCommand.Parsed("region", Mode.DEFAULT), NavigateCommand.parse(new String[] {"region"}),
            "a lone word is a name, never a mode");
    }

    @Test
    void onlyPlayersWithTheNodeMayNavigate() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertTrue(navigate.onCommand(console, command, "navigate", new String[] {"Kardenna"}));
        verify(console).sendMessage(any(Component.class));

        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(false);
        navigate.onCommand(player, command, "navigate", new String[] {"Kardenna"});
        assertTrue(lastMessage().contains("permission"));
        verify(service, never()).navigate(any(), any());
    }

    @Test
    void noArgumentsShowsTheStatusAndStopStopsOrSaysSo() {
        navigate.onCommand(player, command, "navigate", new String[0]);
        verify(service).status(player);

        when(service.stop(player)).thenReturn(false);
        navigate.onCommand(player, command, "navigate", new String[] {"stop"});
        assertTrue(lastMessage().contains("not navigating"));
    }

    @Test
    void anAmbiguousNameListsTheQualifiedChoices() {
        NavTarget town = NavTarget.domain(NavTarget.Type.TOWN, 1, "Market");
        NavTarget district = NavTarget.domain(NavTarget.Type.DISTRICT, 2, "Market");
        when(destinations.resolve("Market", "world")).thenReturn(new Resolution(null, List.of(town, district), List.of()));

        navigate.onCommand(player, command, "navigate", new String[] {"Market"});

        String message = lastMessage();
        assertTrue(message.contains("Several places are called \"Market\""));
        assertTrue(message.contains("town:Market") && message.contains("district:Market"));
        verify(service, never()).navigate(any(), any());
    }

    @Test
    void anUnknownNameSuggestsSimilarOnes() {
        when(destinations.resolve("Kard", "world")).thenReturn(new Resolution(null, List.of(),
            List.of(NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna"))));

        navigate.onCommand(player, command, "navigate", new String[] {"Kard"});

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
        List<String> texts = captor.getAllValues().stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
        assertTrue(texts.get(0).contains("No place called \"Kard\""));
        assertTrue(texts.get(1).contains("Did you mean: Kardenna"));
    }

    @Test
    void aResolvedTargetIsLocatedAndHandedToTheService() {
        NavTarget town = NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna");
        Destination destination = Destination.point("Kardenna", "world", 1, 65, 2);
        when(destinations.resolve("Kardenna", "world")).thenReturn(new Resolution(town, List.of(), List.of()));
        when(destinations.locate(town, Mode.REGION, "world")).thenReturn(CompletableFuture.completedFuture(Located.of(destination)));

        navigate.onCommand(player, command, "navigate", new String[] {"Kardenna", "region"});

        verify(service).navigate(player, destination);
    }

    @Test
    void aTargetWithoutALocationIsRefusedWithItsName() {
        NavTarget ruin = NavTarget.domain(NavTarget.Type.STRUCTURE, 9, "Ruin");
        when(destinations.resolve("Ruin", "world")).thenReturn(new Resolution(ruin, List.of(), List.of()));
        when(destinations.locate(ruin, Mode.DEFAULT, "world"))
            .thenReturn(CompletableFuture.completedFuture(Located.failed(Located.Failure.NO_LOCATION)));

        navigate.onCommand(player, command, "navigate", new String[] {"Ruin"});

        assertTrue(lastMessage().contains("Ruin has no location"));
        verify(service, never()).navigate(any(), any());
    }

    @Test
    void tabCompletionOffersStopNamesAndTheModes() {
        when(destinations.complete(anyList(), eq("world"))).thenReturn(List.of("Kardenna"));
        when(destinations.isComplete(anyList(), eq("world"))).thenReturn(true);

        assertEquals(List.of("stop", "Kardenna"), navigate.onTabComplete(player, command, "navigate", new String[] {"s"}));
        assertEquals(List.of("Kardenna", "region"), navigate.onTabComplete(player, command, "navigate", new String[] {"Kardenna", "r"}));

        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(false);
        assertEquals(List.of(), navigate.onTabComplete(player, command, "navigate", new String[] {"K"}));
    }

    @Test
    void withoutTheServicesTheCommandSaysNavigationIsUnavailable() {
        NavigateCommand disabled = new NavigateCommand(() -> null, () -> null, null, Runnable::run);

        disabled.onCommand(player, command, "navigate", new String[] {"Kardenna"});

        assertTrue(lastMessage().contains("not available"));
        assertEquals(List.of(), disabled.onTabComplete(player, command, "navigate", new String[] {"K"}));
        verify(destinations, never()).resolve(anyString(), anyString());
    }
}
