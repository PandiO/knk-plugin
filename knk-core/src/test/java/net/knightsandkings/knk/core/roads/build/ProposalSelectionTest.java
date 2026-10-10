package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.TileProposal.End;
import net.knightsandkings.knk.core.roads.build.TileProposal.Item;
import net.knightsandkings.knk.core.roads.build.TileProposal.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan §5.7, decision D3: all, numbers and ranges, kinds - mixed. */
class ProposalSelectionTest {
    private static final End A = new End(1, 0, 64, 0, RoadNodeKind.JUNCTION);
    private static final End B = new End(2, 10, 64, 0, RoadNodeKind.ENDPOINT);

    private static Item edge(int n, Kind kind) {
        return new Item(n, kind, kind == Kind.EDGE_ADDED ? 0 : 50 + n, A, B, List.of(new int[] {0, 64, 0}, new int[] {10, 64, 0}),
            List.of(), 10, 3, OptionalInt.empty(), List.of(), List.of(), List.of(), null, null, "");
    }

    private static Item node(int n, Kind kind) {
        return new Item(n, kind, 0, null, null, List.of(), List.of(), 0, 0, OptionalInt.empty(), List.of(), List.of(), List.of(),
            B, kind == Kind.NODE_MOVED ? new int[] {12, 64, 0} : null, "");
    }

    private static final List<Item> ITEMS = List.of(edge(1, Kind.EDGE_REMOVED), node(2, Kind.NODE_REMOVED), edge(3, Kind.EDGE_ADDED),
        edge(4, Kind.EDGE_ADDED), edge(5, Kind.EDGE_CHANGED), node(6, Kind.NODE_MOVED), edge(7, Kind.EDGE_ADDED));

    private static Set<Integer> parse(String... tokens) {
        ProposalSelection.Result result = ProposalSelection.parse(List.of(tokens), ITEMS);
        assertTrue(result.ok(), result.error());
        return result.numbers();
    }

    @Test
    void allNumbersRangesAndKinds() {
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7), parse("all"));
        assertEquals(Set.of(1, 4, 5, 6), parse("1", "4-6"));
        assertEquals(Set.of(1, 3), parse("1,3"));
        assertEquals(Set.of(3, 4, 7), parse("added"));
        assertEquals(Set.of(1, 2), parse("removed"));
        assertEquals(Set.of(5, 6), parse("changed", "moved"));
        assertEquals(Set.of(2, 3, 4, 7), parse("added", "2"));
    }

    @Test
    void unknownNumbersBackwardRangesAndWordsAreRefused() {
        assertFalse(ProposalSelection.parse(List.of("9"), ITEMS).ok());
        assertEquals("There is no item 9 (items: 1-7).", ProposalSelection.parse(List.of("9"), ITEMS).error());
        assertFalse(ProposalSelection.parse(List.of("5-3"), ITEMS).ok());
        assertFalse(ProposalSelection.parse(List.of("everything"), ITEMS).ok());
        assertFalse(ProposalSelection.parse(List.of(), ITEMS).ok());
        assertFalse(ProposalSelection.parse(List.of("moved"), ITEMS.subList(0, 3)).ok());
    }

    @Test
    void removingANodeBringsItsEdgesAlong() {
        Item removedEdgeOfB = edge(1, Kind.EDGE_REMOVED);
        Item removedB = node(2, Kind.NODE_REMOVED);

        assertEquals(Set.of(1, 2), TileDiff.withDependencies(List.of(removedEdgeOfB, removedB), Set.of(2)));
        assertEquals(Set.of(1), TileDiff.withDependencies(List.of(removedEdgeOfB, removedB), Set.of(1)));
    }
}
