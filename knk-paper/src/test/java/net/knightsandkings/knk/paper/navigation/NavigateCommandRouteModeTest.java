package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.navigation.destinations.AllowedDestination;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;

/**
 * Off-road phase 4 (PandiO/knk-workspace#707, DESIGN § 4.4, O24, O25): {@code /navigate <destination>
 * [shortest|efficient|preferred] [spawn|region]} - parsing, one node per typed word, no node for the default, tab
 * completion of the held words only - on the catalogue path ({@code navigation.destination-engine: false}) and through
 * the engine (true).
 */
class NavigateCommandRouteModeTest {

    private final NavigationService service = mock(NavigationService.class);
    private final NavigationDestinations destinations = mock(NavigationDestinations.class);
    private final Command command = mock(Command.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    /** The mode nodes the player holds in the in-house model (KnkPermissible's cache). */
    private final Set<String> inHouse = new HashSet<>();
    private NavigateCommand navigate;

    private final NavTarget kardenna = NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna");
    private final Destination kardennaSpawn = Destination.point("Kardenna", "world", 1, 65, 2);

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission(NavigateCommand.NODE)).thenReturn(true);
        navigate = new NavigateCommand(() -> service, () -> destinations, (p, node) -> inHouse.contains(node),
            Runnable::run);
        when(destinations.resolve("Kardenna", "world")).thenReturn(new Resolution(kardenna, List.of(), List.of()));
        when(destinations.locate(eq(kardenna), any(), eq("world")))
            .thenReturn(CompletableFuture.completedFuture(Located.of(kardennaSpawn)));
    }

    private void run(String... args) {
        navigate.onCommand(player, command, "navigate", args);
    }

    // ==================== parsing ====================

    @Test
    void theModeWordStandsBeforeTheTrailingSpawnOrRegion() {
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("Kardenna Mill", Mode.REGION),
            RouteMode.SHORTEST), NavigateCommand.parseRoute(new String[] {"Kardenna", "Mill", "shortest", "region"}));
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("Kardenna", Mode.DEFAULT),
            RouteMode.EFFICIENT), NavigateCommand.parseRoute(new String[] {"Kardenna", "EFFICIENT"}));
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("Kardenna", Mode.SPAWN), null),
            NavigateCommand.parseRoute(new String[] {"Kardenna", "spawn"}), "no mode word: none");
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("Kardenna", Mode.DEFAULT), null),
            NavigateCommand.parseRoute(new String[] {"Kardenna"}));
    }

    @Test
    void aLoneWordIsAlwaysAName() {
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("shortest", Mode.DEFAULT), null),
            NavigateCommand.parseRoute(new String[] {"shortest"}));
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("preferred", Mode.REGION), null),
            NavigateCommand.parseRoute(new String[] {"preferred", "region"}), "the mode word needs a destination before it");
        assertEquals(new NavigateCommand.ParsedRoute(new NavigateCommand.Parsed("Kardenna region", Mode.DEFAULT),
            RouteMode.SHORTEST), NavigateCommand.parseRoute(new String[] {"Kardenna", "region", "shortest"}),
            "spawn/region stay last: after the mode word they are part of the name");
    }

    // ==================== nodes (catalogue path) ====================

    @Test
    void withoutAModeWordTheCommandIsTodaysAndAsksForNoModeNode() {
        run("Kardenna", "spawn");
        verify(service).navigate(player, kardennaSpawn);
        verify(service, never()).navigate(any(), any(Destination.class), any());
        for (RouteMode mode : RouteMode.values()) {
            verify(player, never()).hasPermission(mode.node());
        }
    }

    @Test
    void aTypedWordNeedsItsNode() {
        run("Kardenna", "shortest", "spawn");
        verify(service, never()).navigate(any(), any(Destination.class), any());
        verify(service, never()).navigate(any(), any(Destination.class));
        verify(player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);

        when(player.hasPermission(RouteMode.SHORTEST.node())).thenReturn(true);
        run("Kardenna", "shortest", "spawn");
        verify(service).navigate(player, kardennaSpawn, RouteMode.SHORTEST);
        verify(destinations, org.mockito.Mockito.times(2)).locate(kardenna, Mode.SPAWN, "world");
    }

    @Test
    void eachWordHasItsOwnNodeAndAnInHouseGrantCounts() {
        inHouse.add(RouteMode.EFFICIENT.node());
        run("Kardenna", "efficient");
        verify(service).navigate(player, kardennaSpawn, RouteMode.EFFICIENT);

        run("Kardenna", "shortest");
        verify(service, never()).navigate(player, kardennaSpawn, RouteMode.SHORTEST);

        run("Kardenna", "preferred");
        verify(service, never()).navigate(player, kardennaSpawn, RouteMode.PREFERRED);
        inHouse.add(RouteMode.PREFERRED.node());
        run("Kardenna", "preferred");
        verify(service).navigate(player, kardennaSpawn, RouteMode.PREFERRED);
    }

    @Test
    void withTheInHouseChecksTheTypedWordWaitsForTheirAnswer() {
        // KnKPlugin sets the in-house checks; without an in-house model (bukkitOnly) a Bukkit grant passes, else refused
        navigate.setModePermissions(CommandPermissions.bukkitOnly());
        run("Kardenna", "shortest");
        verify(player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
        verify(service, never()).navigate(any(), any(Destination.class), any());

        when(player.hasPermission(RouteMode.SHORTEST.node())).thenReturn(true);
        run("Kardenna", "shortest");
        verify(service).navigate(player, kardennaSpawn, RouteMode.SHORTEST);
    }

    // ==================== completion (catalogue path) ====================

    @Test
    void completionOffersOnlyTheWordsThePlayerHolds() {
        when(destinations.complete(anyList(), eq("world"))).thenReturn(List.of());
        when(destinations.isComplete(anyList(), eq("world"))).thenAnswer(inv -> {
            List<String> words = inv.getArgument(0);
            return words.equals(List.of("Kardenna"));
        });

        assertEquals(List.of("spawn", "region"), navigate.onTabComplete(player, command, "navigate",
            new String[] {"Kardenna", ""}), "no mode node: exactly today's list");

        when(player.hasPermission(RouteMode.SHORTEST.node())).thenReturn(true);
        inHouse.add(RouteMode.PREFERRED.node());
        assertEquals(List.of("spawn", "region", "shortest", "preferred"), navigate.onTabComplete(player, command,
            "navigate", new String[] {"Kardenna", ""}));
        assertEquals(List.of("spawn", "shortest"), navigate.onTabComplete(player, command, "navigate",
            new String[] {"Kardenna", "s"}));
        assertEquals(List.of("spawn", "region"), navigate.onTabComplete(player, command, "navigate",
            new String[] {"Kardenna", "shortest", ""}), "after a mode word: the trailing words only");
        assertEquals(List.of(), navigate.onTabComplete(player, command, "navigate",
            new String[] {"Kardenna", "shortest", "e"}));
    }

    // ==================== through the destination engine ====================

    @Test
    void throughTheEngineTheWordReachesTheHandlesSession() {
        EngineFixture f = new EngineFixture();
        NavigateCommand engineCommand = new NavigateCommand(() -> service, () -> destinations, (p, node) -> false,
            Runnable::run);
        engineCommand.setEngine(() -> f.engine);

        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna", "efficient", "spawn"});
        verify(service, never()).navigate(any(), any(AllowedDestination.class), any());
        verify(f.player).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);

        when(f.player.hasPermission(RouteMode.EFFICIENT.node())).thenReturn(true);
        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna", "efficient", "spawn"});
        ArgumentCaptor<AllowedDestination> handle = ArgumentCaptor.forClass(AllowedDestination.class);
        verify(service).navigate(eq(f.player), handle.capture(), eq(RouteMode.EFFICIENT));
        assertEquals("domain:Town:1", handle.getValue().candidateKey());
        assertEquals(Candidate.Mode.SPAWN, handle.getValue().mode());

        engineCommand.onCommand(f.player, command, "navigate", new String[] {"Kardenna"});
        verify(service).navigate(eq(f.player), any(AllowedDestination.class));
        verify(destinations, never()).resolve(anyString(), anyString());
    }

    @Test
    void throughTheEngineCompletionOffersTheHeldWords() {
        EngineFixture f = new EngineFixture();
        NavigateCommand engineCommand = new NavigateCommand(() -> service, () -> destinations, (p, node) -> false,
            Runnable::run);
        engineCommand.setEngine(() -> f.engine);
        assertEquals(List.of("spawn", "region"), engineCommand.onTabComplete(f.player, command, "navigate",
            new String[] {"Kardenna", ""}));
        when(f.player.hasPermission(RouteMode.EFFICIENT.node())).thenReturn(true);
        assertEquals(List.of("spawn", "region", "efficient"), engineCommand.onTabComplete(f.player, command, "navigate",
            new String[] {"Kardenna", ""}));
        assertEquals(List.of("spawn", "region"), engineCommand.onTabComplete(f.player, command, "navigate",
            new String[] {"Kardenna", "efficient", ""}));
    }
}
