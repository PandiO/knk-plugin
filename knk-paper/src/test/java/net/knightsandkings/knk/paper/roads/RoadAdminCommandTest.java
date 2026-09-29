package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.ports.api.StreetsQueryApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * /knk road: permission denied, usage lines, tab completion, and the pure helpers. Written for the
 * developer's local build (paper-api is not resolvable in the cloud).
 */
class RoadAdminCommandTest {
    private RoadNetworkQueryApi queryApi;
    private RoadNetworkCommandApi commandApi;
    private RoadNetworkCache cache;
    private RoadAdminCommand command;
    private Player player;

    @BeforeEach
    void setUp() {
        queryApi = mock(RoadNetworkQueryApi.class);
        commandApi = mock(RoadNetworkCommandApi.class);
        cache = mock(RoadNetworkCache.class);
        when(cache.profiles()).thenReturn(List.of(profile(1, "Kardenna main street"), profile(2, "Trail")));
        command = new RoadAdminCommand(queryApi, commandApi, mock(StreetsQueryApi.class), Runnable::run,
            (p, node) -> false, () -> cache, () -> null, () -> null, () -> null, () -> null);
        player = mock(Player.class);
    }

    private static RoadProfile profile(int id, String name) {
        return new RoadProfile(id, name, RoadClass.ROAD, 1.0, List.of(), 2, 5, 10, true, List.of(), null, null, null);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String lastMessage(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captor.capture());
        return plain(captor.getValue());
    }

    @Test
    void deniesAPlayerWithoutTheNode() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(false);

        assertTrue(command.execute(player, new String[] {"tiles"}));

        assertTrue(lastMessage(player).contains("permission"));
        verify(queryApi, never()).profiles();
        assertEquals(List.of(), command.complete(player, new String[] {"t"}), "no completion without the node either");
    }

    @Test
    void theConsoleAlwaysPassesAndGetsUsage() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        command.execute(console, new String[0]);

        assertTrue(lastMessage(console).startsWith("[Road] Usage: /knk road <survey|profile|build"));
    }

    @Test
    void reportsNavigationDisabledWhenNothingIsWired() {
        RoadAdminCommand disabled = new RoadAdminCommand(queryApi, commandApi, null, Runnable::run, null,
            () -> null, () -> null, () -> null, () -> null, () -> null);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        disabled.execute(console, new String[] {"tiles"});

        assertTrue(lastMessage(console).contains("disabled"));
    }

    @Test
    void subcommandUsageLines() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);

        command.execute(player, new String[] {"seed"});
        assertTrue(lastMessage(player).contains("/knk road seed add [note] | remove <id> | list"));
    }

    @Test
    void tabCompletionFollowsTheSubcommandTree() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);

        assertEquals(List.of("seed", "show", "status", "street", "survey"), command.complete(player, new String[] {"s"}));
        assertEquals(List.of("start", "stop"), command.complete(player, new String[] {"survey", "st"}));
        assertEquals(List.of("\"Kardenna main street\""), command.complete(player, new String[] {"survey", "start", "Kar"}));
        assertEquals(List.of("Trail"), command.complete(player, new String[] {"profile", "show", "tr"}));
        assertEquals(List.of("close", "cost"), command.complete(player, new String[] {"edge", "set", "12", "c"}));
        assertEquals(List.of("Accent"), command.complete(player, new String[] {"profile", "role", "1", "STONE", "a"}));
        assertEquals(List.of(), command.complete(player, new String[] {"reload", "x"}));
    }

    @Test
    void pureHelpers() {
        assertEquals("Kardenna main street", RoadAdminCommand.joinQuoted(new String[] {"start", "\"Kardenna", "main", "street\""}, 1));
        assertEquals("Trail", RoadAdminCommand.unquote("Trail"));
        assertEquals(Optional.of(2), RoadAdminCommand.findProfile(cache.profiles(), "trail").map(RoadProfile::id));
        assertEquals(Optional.of(1), RoadAdminCommand.findProfile(cache.profiles(), "1").map(RoadProfile::id));
        assertTrue(RoadAdminCommand.findProfile(cache.profiles(), "99").isEmpty());
        assertTrue(RoadAdminCommand.isOn("on") && RoadAdminCommand.isOn("TRUE") && !RoadAdminCommand.isOn("off"));
        assertEquals(Integer.valueOf(12), RoadAdminCommand.parseInt("12"));
        assertEquals(null, RoadAdminCommand.parseInt("here"));
        assertEquals("\"Market Street\"", RoadAdminCommand.quoteIfSpaced("Market Street"));
    }
}
