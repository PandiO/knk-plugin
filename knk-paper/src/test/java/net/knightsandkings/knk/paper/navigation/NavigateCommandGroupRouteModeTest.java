package net.knightsandkings.knk.paper.navigation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;

/**
 * Off-road L7b (PandiO/knk-workspace#852): a permission group's default route mode on {@code /navigate} without a mode
 * word, on the catalogue path ({@code navigation.destination-engine: false}) and through the engine (true). A typed word
 * still needs its node; the group default needs none; no group default is exactly today's call.
 */
class NavigateCommandGroupRouteModeTest {

    private final NavigationService service = mock(NavigationService.class);
    private final NavigationDestinations destinations = mock(NavigationDestinations.class);
    private final Command command = mock(Command.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    /** The players' group defaults as the warm cache would answer them; absent = none or cold. */
    private final Map<Player, RouteMode> groupDefaults = new HashMap<>();
    private final AtomicInteger warmed = new AtomicInteger();
    private NavigateCommand navigate;

    private final NavTarget kardenna = NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna");
    private final Destination kardennaSpawn = Destination.point("Kardenna", "world", 1, 65, 2);

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(true);
        navigate = new NavigateCommand(() -> service, () -> destinations, (p, node) -> false, Runnable::run);
        navigate.setGroupDefaults(groupDefaults::get, p -> warmed.incrementAndGet());
        when(destinations.resolve("Kardenna", "world")).thenReturn(new Resolution(kardenna, List.of(), List.of()));
        when(destinations.locate(eq(kardenna), any(), eq("world")))
            .thenReturn(CompletableFuture.completedFuture(Located.of(kardennaSpawn)));
    }

    private void run(String... args) {
        navigate.onCommand(player, command, "navigate", args);
    }

    // ==================== catalogue path ====================

    @Test
    void withoutAGroupDefaultTheCommandIsTodays() {
        run("Kardenna", "spawn");
        verify(service).navigate(player, kardennaSpawn);
        verify(service, never()).navigate(any(), any(Destination.class), any());
    }

    @Test
    void theGroupDefaultAppliesWithoutTheModeNode() {
        groupDefaults.put(player, RouteMode.SHORTEST);
        run("Kardenna");
        verify(service).navigate(player, kardennaSpawn, RouteMode.SHORTEST);
        verify(service, never()).navigate(any(), any(Destination.class));
        verify(player, never()).hasPermission(RouteMode.SHORTEST.node());
        verify(player, never()).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
    }

    @Test
    void aTypedWordStillNeedsItsNodeAndBeatsTheGroupDefault() {
        groupDefaults.put(player, RouteMode.SHORTEST);
        run("Kardenna", "efficient");
        verify(player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
        verify(service, never()).navigate(any(), any(Destination.class), any());

        when(player.hasPermission(RouteMode.EFFICIENT.node())).thenReturn(true);
        run("Kardenna", "efficient");
        verify(service).navigate(player, kardennaSpawn, RouteMode.EFFICIENT);
        verify(service, never()).navigate(player, kardennaSpawn, RouteMode.SHORTEST);
    }

    @Test
    void aTypedWordEqualToTheGroupDefaultStillNeedsItsNode() {
        groupDefaults.put(player, RouteMode.SHORTEST);
        run("Kardenna", "shortest");
        verify(player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
        verify(service, never()).navigate(any(), any(Destination.class), any());
    }

    @Test
    void theCommandAndCompletionWarmTheCache() {
        when(destinations.complete(org.mockito.ArgumentMatchers.anyList(), eq("world"))).thenReturn(List.of());
        navigate.onTabComplete(player, command, "navigate", new String[] {"Ka"});
        run("Kardenna");
        assertEquals(2, warmed.get());
    }

    // ==================== through the destination engine ====================

    @Test
    void throughTheEngineTheGroupDefaultReachesTheHandlesSession() {
        EngineFixture f = new EngineFixture();
        NavigateCommand engineCommand = new NavigateCommand(() -> service, () -> destinations, (p, node) -> false,
            Runnable::run);
        engineCommand.setEngine(() -> f.engine);
        Map<Player, RouteMode> defaults = new HashMap<>();
        engineCommand.setGroupDefaults(defaults::get, p -> { });

        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna"});
        verify(service).navigate(eq(f.player), any(AllowedDestination.class));
        verify(service, never()).navigate(any(), any(AllowedDestination.class), any());

        defaults.put(f.player, RouteMode.EFFICIENT);
        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna", "spawn"});
        verify(service).navigate(eq(f.player), any(AllowedDestination.class), eq(RouteMode.EFFICIENT));
        verify(f.player, never()).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);

        // a typed word still needs its node
        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna", "shortest"});
        verify(f.player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
        verify(service, never()).navigate(any(), any(AllowedDestination.class), eq(RouteMode.SHORTEST));
        verify(service, times(1)).navigate(any(), any(AllowedDestination.class), any());
    }
}
