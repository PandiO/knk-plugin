package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.common.Conditional;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeUpdateResult;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.domain.roads.RoadNodeUpdate;
import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.domain.roads.RoadTileGraph;
import net.knightsandkings.knk.core.domain.roads.RoadTileProposalSummary;
import net.knightsandkings.knk.core.domain.roads.RoadTileState;
import net.knightsandkings.knk.core.domain.roads.RoadTileUpsertResult;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import net.knightsandkings.knk.core.ports.api.RoadNetworkQueryApi;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.TileBuildResult;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Curated tiles (rev. 6 Part B, plan §5.7): the build job's proposal step and the review commands, against mocked
 * API ports. Stored tile 0,0: Boundary #1 (0), Junction #2 (200), Endpoint #3 (300) on z=100, a spur to Endpoint #4
 * (200, z=250); detected edges #10 (1-2), #11 (2-3), #12 (2-4).
 */
class RoadProposalsTest {
    private static final TileKey TILE = new TileKey("world", 0, 0);

    private RoadNetworkQueryApi queryApi;
    private RoadNetworkCommandApi commandApi;
    private RoadNetworkCache cache;
    private RoadProposals proposals;
    private Player player;
    private World world; // kept in a field: Location holds its world weakly

    @BeforeEach
    void setUp() {
        queryApi = mock(RoadNetworkQueryApi.class);
        commandApi = mock(RoadNetworkCommandApi.class);
        cache = mock(RoadNetworkCache.class);
        when(cache.invalidateTile(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(cache.refreshTiles(any())).thenReturn(CompletableFuture.completedFuture(null));
        proposals = new RoadProposals(queryApi, commandApi, cache, BuildParameters::defaults, Runnable::run);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 150, 65, 120));
        when(commandApi.saveProposal(any(), anyInt(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(summary()));
        when(commandApi.upsertTileGraph(any(), anyInt(), anyInt(), any())).thenReturn(CompletableFuture.completedFuture(upsertResult(9)));
        when(queryApi.tileGraph(eq("world"), eq(0), eq(0), isNull()))
            .thenReturn(CompletableFuture.completedFuture(Conditional.modified(stored(), "\"5\"")));
    }

    // ---- fixtures

    private static int[] p(int x, int z) {
        return new int[] {x, 64, z};
    }

    private static RoadEdge edge(int id, int from, int to, int[]... points) {
        return new RoadEdge(id, from, to, List.of(points), 100, 3, OptionalInt.of(1), OptionalInt.empty(), 1.0, Set.of(),
            List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false);
    }

    private static RoadTile tile(int builderVersion) {
        return new RoadTile(1, "world", 0, 0, 5, java.time.OffsetDateTime.now(), builderVersion, false, 100, 4, 3, 1, List.of(),
            RoadTileState.CURATED, java.time.OffsetDateTime.now());
    }

    private static RoadTileGraph stored() {
        return new RoadTileGraph(tile(5),
            List.of(new RoadNode(1, 0, 64, 100, RoadNodeKind.BOUNDARY, null, 1), new RoadNode(2, 200, 64, 100, RoadNodeKind.JUNCTION, null, 1),
                new RoadNode(3, 300, 64, 100, RoadNodeKind.ENDPOINT, null, 1), new RoadNode(4, 200, 64, 250, RoadNodeKind.ENDPOINT, null, 1)),
            List.of(edge(10, 1, 2, p(0, 100), p(200, 100)), edge(11, 2, 3, p(200, 100), p(300, 100)), edge(12, 2, 4, p(200, 100), p(200, 250))));
    }

    /** The build lost the spur (#12, #4) and found a road east of #3 to a new endpoint (400, 100). */
    private static TileBuildResult build() {
        List<TileBuildResult.Node> nodes = List.of(
            new TileBuildResult.Node("b", OptionalInt.of(1), 0, 64, 100, RoadNodeKind.BOUNDARY),
            new TileBuildResult.Node("j", OptionalInt.of(2), 200, 64, 100, RoadNodeKind.JUNCTION),
            new TileBuildResult.Node("e", OptionalInt.of(3), 300, 64, 100, RoadNodeKind.JUNCTION),
            new TileBuildResult.Node("n", OptionalInt.empty(), 400, 64, 100, RoadNodeKind.ENDPOINT));
        List<TileBuildResult.Edge> edges = List.of(
            new TileBuildResult.Edge(OptionalInt.of(10), "b", "j", List.of(p(0, 100), p(200, 100)), 200, 3, OptionalInt.of(1), List.of(), List.of(), List.of()),
            new TileBuildResult.Edge(OptionalInt.of(11), "j", "e", List.of(p(200, 100), p(300, 100)), 100, 3, OptionalInt.of(1), List.of(), List.of(), List.of()),
            new TileBuildResult.Edge(OptionalInt.empty(), "e", "n", List.of(p(300, 100), p(400, 100)), 100, 3, OptionalInt.of(1), List.of(), List.of(), List.of()));
        return new TileBuildResult(6, 900, 1, nodes, edges, List.of());
    }

    /** 1 removed edge #12, 2 removed node #4, 3 added edge #3 → new. */
    private TileProposal proposal(List<Item> rejected) {
        List<Item> items = proposals.diff().compute(stored(), build());
        return new TileProposal(5, 6, "Pandi", 900, 1, List.of(), items, rejected);
    }

    private void storedProposal(TileProposal proposal) {
        when(queryApi.proposal("world", 0, 0)).thenReturn(CompletableFuture.completedFuture(Optional.of(proposal)));
    }

    private static RoadTileProposalSummary summary() {
        return new RoadTileProposalSummary(1, "world", 0, 0, 5, 5, 6, "Pandi", null, null, 1, 2, 0, 0, 0);
    }

    private static RoadTileUpsertResult upsertResult(int version) {
        return new RoadTileUpsertResult(new RoadTile(1, "world", 0, 0, version, java.time.OffsetDateTime.now(), 6, false, 900, 4, 3, 1, List.of(),
            RoadTileState.CURATED, null), 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(7));
    }

    private static List<String> messages(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(captor.capture());
        List<String> out = new ArrayList<>();
        captor.getAllValues().forEach(c -> out.add(PlainTextComponentSerializer.plainText().serialize(c)));
        return out;
    }

    private TileProposal savedProposal() {
        ArgumentCaptor<TileProposal> captor = ArgumentCaptor.forClass(TileProposal.class);
        verify(commandApi, atLeastOnce()).saveProposal(eq("world"), eq(0), eq(0), captor.capture());
        return captor.getValue();
    }

    private TileBuildResult uploaded() {
        ArgumentCaptor<TileBuildResult> captor = ArgumentCaptor.forClass(TileBuildResult.class);
        verify(commandApi).upsertTileGraph(eq("world"), eq(0), eq(0), captor.capture());
        return captor.getValue();
    }

    // ---- the build job's step

    @Test
    void aBuildOfACuratedTileIsStoredAsAProposal() {
        when(queryApi.proposal("world", 0, 0)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        RoadProposals.Created created = proposals.propose(TILE, stored(), build(), "Pandi").join();

        assertFalse(created.nothingToReview());
        assertEquals(List.of(Kind.EDGE_REMOVED, Kind.NODE_REMOVED, Kind.EDGE_ADDED), created.proposal().items().stream().map(Item::kind).toList());
        TileProposal saved = savedProposal();
        assertEquals((5), saved.baseVersion());
        assertEquals((6), saved.builderVersion());
        assertEquals("Pandi", saved.createdBy());
        assertTrue(proposals.cached(TILE).isPresent());
        assertEquals(1, proposals.pendingItems("world").size());
        verify(commandApi, never()).upsertTileGraph(any(), anyInt(), anyInt(), any());
    }

    @Test
    void theRejectedListHidesItsChangesFromTheNextProposal() {
        Item rejectedAddition = proposal(List.of()).items().get(2);
        storedProposal(new TileProposal(4, 5, null, 0, 0, List.of(), List.of(), List.of(rejectedAddition)));

        RoadProposals.Created created = proposals.propose(TILE, stored(), build(), "Pandi").join();

        assertEquals(1, created.hidden());
        assertEquals(2, created.proposal().items().size());
        assertEquals(List.of(rejectedAddition), savedProposal().rejected());
    }

    @Test
    void aBuildWithNothingToReviewSavesNoProposal_andUploadsTheGraphUnchangedWithTheNewVersion() {
        when(queryApi.proposal("world", 0, 0)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        TileBuildResult same = new TileBuildResult(6, 900, 1,
            List.of(new TileBuildResult.Node("b", OptionalInt.of(1), 0, 64, 100, RoadNodeKind.BOUNDARY),
                new TileBuildResult.Node("j", OptionalInt.of(2), 200, 64, 100, RoadNodeKind.JUNCTION),
                new TileBuildResult.Node("e", OptionalInt.of(3), 300, 64, 100, RoadNodeKind.ENDPOINT),
                new TileBuildResult.Node("s", OptionalInt.of(4), 200, 64, 250, RoadNodeKind.ENDPOINT)),
            List.of(new TileBuildResult.Edge(OptionalInt.of(10), "b", "j", List.of(p(0, 100), p(200, 100)), 200, 3, OptionalInt.of(1), List.of(), List.of(), List.of()),
                new TileBuildResult.Edge(OptionalInt.of(11), "j", "e", List.of(p(200, 100), p(300, 100)), 100, 3, OptionalInt.of(1), List.of(), List.of(), List.of()),
                new TileBuildResult.Edge(OptionalInt.of(12), "j", "s", List.of(p(200, 100), p(200, 250)), 150, 3, OptionalInt.of(1), List.of(), List.of(), List.of())),
            List.of());

        RoadProposals.Created created = proposals.propose(TILE, stored(), same, "Pandi").join();
        proposals.uploadUnchanged(TILE, stored(), same).join();

        assertTrue(created.nothingToReview());
        verify(commandApi, never()).saveProposal(any(), anyInt(), anyInt(), any());
        TileBuildResult upload = uploaded();
        assertEquals(6, upload.builderVersion());
        assertEquals(4, upload.nodes().size());
        assertEquals(3, upload.edges().size());
    }

    // ---- accept

    @Test
    void acceptingSomeItemsUploadsThemAndKeepsTheRestUnderTheirNumbers() {
        storedProposal(proposal(List.of()));

        proposals.command(player, new String[] {"accept", "3"});

        TileBuildResult upload = uploaded();
        assertEquals(5, upload.builderVersion()); // not finished: the tile keeps its builder version
        assertEquals(1, upload.edges().stream().filter(e -> e.existingId().isEmpty()).count());
        assertTrue(upload.edges().stream().anyMatch(e -> e.existingId().equals(OptionalInt.of(12)))); // not accepted
        TileProposal left = savedProposal();
        assertEquals(List.of(1, 2), left.items().stream().map(Item::n).toList());
        assertEquals(9, left.baseVersion()); // rebased on the version the upsert returned
        verify(cache).invalidateTile(TILE);
        verify(cache).invalidateTileId("world", 7);
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("accepted item 3 - applied to the road graph")));
    }

    @Test
    void acceptingEverythingFinishesTheReviewWithTheProposalsVersion() {
        storedProposal(proposal(List.of()));

        proposals.command(player, new String[] {"accept", "all", "@0,0"});

        TileBuildResult upload = uploaded();
        assertEquals(6, upload.builderVersion());
        assertEquals(900, upload.cellCount());
        assertFalse(upload.nodes().stream().anyMatch(n -> n.existingId().equals(OptionalInt.of(4))));
        verify(commandApi, never()).saveProposal(any(), anyInt(), anyInt(), any()); // the upsert cleared the items
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("finished: it now counts as built with v6")));
    }

    @Test
    void acceptingTheRemovedNodeBringsItsEdge() {
        storedProposal(proposal(List.of()));

        proposals.command(player, new String[] {"accept", "2"});

        assertFalse(uploaded().edges().stream().anyMatch(e -> e.existingId().equals(OptionalInt.of(12))));
        assertEquals(List.of(3), savedProposal().items().stream().map(Item::n).toList());
    }

    @Test
    void aBadSelectionChangesNothing() {
        storedProposal(proposal(List.of()));

        proposals.command(player, new String[] {"accept", "9"});

        verify(commandApi, never()).upsertTileGraph(any(), anyInt(), anyInt(), any());
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("There is no item 9")));
    }

    // ---- reject

    @Test
    void rejectingARemovalConfirmsTheEdgeOrLocksTheNode_andAnAdditionGoesOnTheRejectedList() {
        storedProposal(proposal(List.of()));
        when(commandApi.updateEdge(eq(12), any())).thenReturn(CompletableFuture.completedFuture(mock(RoadEdgeUpdateResult.class)));
        when(commandApi.updateNode(eq(4), any())).thenReturn(CompletableFuture.completedFuture(mock(RoadNode.class)));

        proposals.command(player, new String[] {"reject", "1-3"});

        verify(commandApi).updateEdge(12, RoadEdgeUpdate.confirmed(true));
        verify(commandApi).updateNode(4, RoadNodeUpdate.locked(true));
        TileProposal saved = savedProposal();
        assertTrue(saved.items().isEmpty());
        // Every rejected item is on the list (R1-R3); the removals record the nodes their rejection locked.
        assertEquals(List.of(Kind.EDGE_REMOVED, Kind.NODE_REMOVED, Kind.EDGE_ADDED), saved.rejected().stream().map(Item::kind).toList());
        assertEquals(List.of(2, 4), saved.rejected().get(0).lockedNodeIds());
        assertEquals(List.of(4), saved.rejected().get(1).lockedNodeIds());
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("rejected item 1 → R1, item 2 → R2, item 3 → R3")));
        // Nothing left: the current graph is uploaded with the proposal's figures.
        assertEquals(6, uploaded().builderVersion());
    }

    @Test
    void rejectingPartOfAProposalUploadsNothing() {
        storedProposal(proposal(List.of()));

        proposals.command(player, new String[] {"reject", "added"});

        assertEquals(List.of(1, 2), savedProposal().items().stream().map(Item::n).toList());
        verify(commandApi, never()).upsertTileGraph(any(), anyInt(), anyInt(), any());
    }

    // ---- clear, rejected list, tile state

    @Test
    void clearDropsThePendingItemsButKeepsTheRejectedList() {
        Item rejected = proposal(List.of()).items().get(2);
        storedProposal(proposal(List.of(rejected)));

        proposals.command(player, new String[] {"clear"});

        TileProposal saved = savedProposal();
        assertTrue(saved.items().isEmpty());
        assertEquals(List.of(rejected), saved.rejected());
    }

    @Test
    void unrejectTakesEntriesOffTheRejectedListByPosition() {
        List<Item> items = proposal(List.of()).items();
        storedProposal(new TileProposal(5, 6, null, 0, 0, List.of(), List.of(), List.of(items.get(2), items.get(0))));

        proposals.command(player, new String[] {"unreject", "1"});

        assertEquals(List.of(Kind.EDGE_REMOVED), savedProposal().rejected().stream().map(Item::kind).toList());
    }

    @Test
    void unrejectingAKeptEdgeUnconfirmsItAndUnlocksTheNodesItsRejectionLocked() {
        List<Item> items = proposal(List.of()).items();
        Item keptEdge = items.get(0).withLockedNodes(List.of(4));
        storedProposal(new TileProposal(5, 6, null, 0, 0, List.of(), List.of(), List.of(items.get(2), keptEdge)));
        when(commandApi.updateEdge(eq(12), any())).thenReturn(CompletableFuture.completedFuture(mock(RoadEdgeUpdateResult.class)));
        when(commandApi.updateNode(eq(4), any())).thenReturn(CompletableFuture.completedFuture(mock(RoadNode.class)));

        proposals.command(player, new String[] {"unconfirm", "R2"});

        verify(commandApi).updateEdge(12, RoadEdgeUpdate.confirmed(false));
        verify(commandApi).updateNode(4, RoadNodeUpdate.locked(false));
        verify(commandApi, never()).updateNode(eq(2), any()); // #2 was locked before the rejection: it stays locked
        assertEquals(List.of(Kind.EDGE_ADDED), savedProposal().rejected().stream().map(Item::kind).toList());
        assertTrue(messages(player).stream().anyMatch(m -> m.contains("took back R2 (removed edge #12 (#2 → #4): unconfirmed, unlocked [#4])")));
    }

    @Test
    void rejectedEntriesAreAddressedAsR() {
        assertEquals(List.of("3", "1-3", "1,2", "all", "4"), RoadProposals.rejectedTokens(List.of("R3", "r1-R3", "R1,R2", "all", "4")));
        List<Item> items = proposal(List.of()).items();
        List<String> lines = RoadProposals.rejectedLines(List.of(items.get(2), items.get(0)), TILE).stream()
            .map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
        assertTrue(lines.get(0).startsWith(" R1: added edge 100 m (#3 → new endpoint) - hidden"), lines.get(0));
        assertTrue(lines.get(1).startsWith(" R2: removed edge #12 (#2 → #4) - kept"), lines.get(1));
    }

    @Test
    void uncurateSetsTheTileDetected() {
        when(commandApi.setTileState("world", 2, -2, RoadTileState.DETECTED)).thenReturn(CompletableFuture.completedFuture(tile(5)));

        proposals.tileCommand(player, new String[] {"uncurate", "@2,-2"});

        verify(commandApi).setTileState("world", 2, -2, RoadTileState.DETECTED);
        assertTrue(messages(player).get(0).contains("next build is uploaded directly"));
        verify(cache).refreshTiles("world");
    }

    @Test
    void theTileComesFromTheTokenOrWhereThePlayerStands() {
        List<String> args = new ArrayList<>(List.of("accept", "@2,-2", "3"));
        assertEquals(Optional.of(new TileKey("world", 2, -2)), RoadProposals.takeTile(player, args));
        assertEquals(List.of("accept", "3"), args);
        assertEquals(Optional.of(new TileKey("other", 1, 1)), RoadProposals.takeTile(player, new ArrayList<>(List.of("@other:1,1"))));
        assertEquals(Optional.of(new TileKey("world", 0, 0)), RoadProposals.takeTile(player, new ArrayList<>(List.of("show"))));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertEquals(Optional.empty(), RoadProposals.takeTile(console, new ArrayList<>(List.of("show"))));
        assertEquals(Optional.empty(), RoadProposals.takeTile(player, new ArrayList<>(List.of("@two,1"))));
    }

    @Test
    void theReviewPageNumbersItemsWithTeleportsAndButtons() {
        List<String> lines = RoadProposals.showLines(proposal(List.of()), TILE, 1).stream()
            .map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();

        assertTrue(lines.get(0).contains("Proposal for tile 0,0: 3 change(s) - 1 added, 2 removed"), lines.get(0));
        assertTrue(lines.get(1).contains("[accept all]") && lines.get(1).contains("[reject all]"), lines.get(1));
        assertTrue(lines.get(2).startsWith(" item 1: removed edge #12 (#2 → #4)"), lines.get(2));
        assertTrue(lines.get(2).contains("[accept]") && lines.get(2).contains("[reject]"), lines.get(2));
    }
}
