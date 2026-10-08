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

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

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
        assertEquals(List.of("tile", "tiles"), command.complete(player, new String[] {"til"}));
        assertEquals(List.of("curate", "uncurate"), command.complete(player, new String[] {"tile", ""}));
        assertEquals(List.of("reject", "rejected"), command.complete(player, new String[] {"proposal", "rej"}));
        assertEquals(List.of("added", "all"), command.complete(player, new String[] {"proposal", "accept", "a"}));
        assertEquals(List.of("confirm"), command.complete(player, new String[] {"edge", "conf"}));
    }

    @Test
    void tilesRefreshesTheTileListBeforePrinting_soAStateChangeShows() {
        // Smoke test 2026-10-05: after uncurate + a direct build the tile was Curated in the DB, but the list still
        // showed it uncurated (the cached tile list was only refreshed every 10 minutes).
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        java.util.List<net.knightsandkings.knk.core.domain.roads.RoadTile> list = new java.util.ArrayList<>(List.of(tile(
            net.knightsandkings.knk.core.domain.roads.RoadTileState.DETECTED)));
        when(cache.tiles("world")).thenAnswer(i -> List.copyOf(list));
        when(cache.snapshot("world")).thenReturn(RoadNetworkSnapshot.empty("world"));
        when(cache.refreshTiles("world")).thenAnswer(i -> {
            list.set(0, tile(net.knightsandkings.knk.core.domain.roads.RoadTileState.CURATED));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        command.execute(player, new String[] {"tiles"});

        verify(cache).refreshTiles("world");
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().map(RoadAdminCommandTest::plain).anyMatch(l -> l.startsWith(" 1,-2 ") && l.contains(" curated")),
            captor.getAllValues().stream().map(RoadAdminCommandTest::plain).toList().toString());
    }

    private static net.knightsandkings.knk.core.domain.roads.RoadTile tile(net.knightsandkings.knk.core.domain.roads.RoadTileState state) {
        return new net.knightsandkings.knk.core.domain.roads.RoadTile(3, "world", 1, -2, 12, java.time.OffsetDateTime.now(), 5, false,
            1611, 7, 7, 2, List.of(), state, null);
    }

    @Test
    void nodePruneAndUnpruneTakeAnIdOrTheRightNodeHere() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 10.5, 65, 10.5));
        // A junction under the player, a dead end 3 blocks away, a tombstone 4 blocks away.
        RoadNetworkSnapshot snapshot = RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 10, 64, 10, RoadNodeKind.JUNCTION, null, 1))
            .addNode(new RoadNode(2, 13, 64, 10, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(3, 10, 64, 14, RoadNodeKind.PRUNED, null, 3, true))
            .build();
        when(cache.snapshot("world")).thenReturn(snapshot);
        when(commandApi.pruneNode(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new RoadNode(2, 13, 64, 10, RoadNodeKind.PRUNED, null, 2, true)));
        when(commandApi.unpruneNode(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));

        command.execute(player, new String[] {"node", "prune"});
        verify(commandApi).pruneNode(2);
        command.execute(player, new String[] {"node", "prune", "77"});
        verify(commandApi).pruneNode(77);
        command.execute(player, new String[] {"node", "unprune"});
        verify(commandApi).unpruneNode(3);

        assertEquals(Optional.of(1), RoadAdminCommand.nodeHere(snapshot, player).map(RoadNode::id), "the other commands never pick a tombstone");
        assertEquals(List.of("prune"), command.complete(player, new String[] {"node", "pr"}));
    }

    @Test
    void nodePlazaAndUnplazaTakeAnIdOrTheNodeHere_AndMoveNeedsAnId() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 10.5, 65, 10.5));
        // A dead end under the player, a junction 3 blocks away, a plaza centre (radius 12) 10 blocks away.
        RoadNetworkSnapshot snapshot = RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 10, 64, 10, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(2, 13, 64, 10, RoadNodeKind.JUNCTION, null, 1))
            .addNode(new RoadNode(3, 20, 64, 10, RoadNodeKind.ANCHOR, "Square", 1, true, 12))
            .build();
        when(cache.snapshot("world")).thenReturn(snapshot);
        when(commandApi.updateNode(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new RoadNode(2, 13, 64, 10, RoadNodeKind.JUNCTION, null, 1, true, 8)));

        command.execute(player, new String[] {"node", "plaza", "8"});
        verify(commandApi).updateNode(2, net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate.plaza(8)); // a junction, not the dead end
        command.execute(player, new String[] {"node", "plaza", "6", "3"});
        verify(commandApi).updateNode(3, net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate.plaza(6));
        command.execute(player, new String[] {"node", "unplaza"});
        verify(commandApi).updateNode(3, net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate.noPlaza()); // standing in its plaza

        org.mockito.Mockito.clearInvocations(player);
        command.execute(player, new String[] {"node", "plaza", "40"});
        assertTrue(lastMessage(player).contains("/knk road node plaza <radius 1-32> [id]"), lastMessage(player));
        org.mockito.Mockito.clearInvocations(player);
        command.execute(player, new String[] {"node", "move"});
        assertTrue(lastMessage(player).contains("/knk road node move <id>"), lastMessage(player));
        assertEquals(List.of("plaza"), command.complete(player, new String[] {"node", "pl"}));
    }

    private static net.knightsandkings.knk.core.domain.roads.RoadEdge edge(int id, int from, int to, int[] a, int[] b,
                                                                          net.knightsandkings.knk.core.domain.roads.RoadEdgeSource source) {
        return new net.knightsandkings.knk.core.domain.roads.RoadEdge(id, from, to, List.of(a, b), 10, 3,
            java.util.OptionalInt.empty(), java.util.OptionalInt.empty(), 1, java.util.Set.of(), List.of(), List.of(), List.of(), source, false);
    }

    @Test
    void edgePruneTakesAnIdOrTheEdgeHere_AndAJunctionIsPrunedOnlyAfterConfirming() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 10.5, 65, 10.5));
        var detected = net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.DETECTED;
        // Junction #1 under the player: detected edges #11 (west), #12 (east), a recorded edge #13 (south);
        // tombstone #4 of a pruned edge 3 blocks away.
        RoadNetworkSnapshot snapshot = RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 10, 64, 10, RoadNodeKind.JUNCTION, null, 1))
            .addNode(new RoadNode(2, 0, 64, 10, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(3, 20, 64, 10, RoadNodeKind.ENDPOINT, null, 1))
            .addNode(new RoadNode(5, 10, 64, 30, RoadNodeKind.ANCHOR, null, 1, true))
            .addNode(new RoadNode(4, 13, 64, 13, RoadNodeKind.PRUNED_EDGE, null, 4, true))
            .addEdge(edge(11, 1, 2, new int[] {10, 64, 10}, new int[] {0, 64, 10}, detected))
            .addEdge(edge(12, 1, 3, new int[] {10, 64, 10}, new int[] {20, 64, 10}, detected))
            .addEdge(edge(13, 1, 5, new int[] {10, 64, 10}, new int[] {10, 64, 30}, net.knightsandkings.knk.core.domain.roads.RoadEdgeSource.RECORDED))
            .build();
        when(cache.snapshot("world")).thenReturn(snapshot);
        when(commandApi.pruneEdges(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
            new net.knightsandkings.knk.core.domain.roads.RoadEdgePruneResult(
                List.of(new RoadNode(40, 5, 64, 10, RoadNodeKind.PRUNED_EDGE, null, 40, true)), List.of(2))));
        when(commandApi.unpruneNode(org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));

        command.execute(player, new String[] {"edge", "prune", "11"});
        verify(commandApi).pruneEdges(List.of(11));
        command.execute(player, new String[] {"edge", "prune", "here"});
        verify(commandApi, org.mockito.Mockito.times(2)).pruneEdges(any()); // one of the edges meeting under the feet
        org.mockito.Mockito.clearInvocations(commandApi);

        command.execute(player, new String[] {"node", "prune", "1"});
        verify(commandApi, never()).pruneEdges(any());
        verify(commandApi, never()).pruneNode(org.mockito.ArgumentMatchers.anyInt());
        command.execute(player, new String[] {"node", "prune", "1", "confirm"});
        verify(commandApi).pruneEdges(List.of(11, 12)); // the recorded edge stays

        command.execute(player, new String[] {"node", "unprune"});
        verify(commandApi).unpruneNode(4);
        assertEquals(List.of("prune"), command.complete(player, new String[] {"edge", "pr"}));
    }

    @Test
    void aNamedJunctionIsNotPruned() {
        when(player.hasPermission(RoadAdminCommand.NODE)).thenReturn(true);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 10.5, 65, 10.5));
        when(cache.snapshot("world")).thenReturn(RoadNetworkSnapshot.builder("world")
            .addNode(new RoadNode(1, 10, 64, 10, RoadNodeKind.JUNCTION, "Market", 1))
            .build());

        command.execute(player, new String[] {"node", "prune", "1", "confirm"});

        verify(commandApi, never()).pruneEdges(any());
        assertTrue(lastMessage(player).contains("unname"));
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
