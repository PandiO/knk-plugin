package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.roads.build.TileProposal;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * Smoke test finding G: the action bar of {@code /knk road show} names what the admin looks at -
 * a node pillar near the view ray at any distance, else the edge the ray passes over, else the node
 * the {@code here} commands act on.
 */
class RoadOverlayRendererTest {

    /** A road along z = 0 from Market (0, 64, 0) to the East Gate junction (40, 64, 0). */
    private final RoadNetworkSnapshot snapshot = RoadNetworkSnapshot.builder("world")
        .addNode(new RoadNode(1, 0, 64, 0, RoadNodeKind.JUNCTION, "Market", 1, true))
        .addNode(new RoadNode(2, 40, 64, 0, RoadNodeKind.JUNCTION, null, 1))
        .addEdge(new RoadEdge(10, 1, 2, List.of(new int[] {0, 64, 0}, new int[] {40, 64, 0}), 40, 3, OptionalInt.empty(),
            OptionalInt.empty(), 1.0, EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(),
            RoadEdgeSource.DETECTED, false))
        .build();

    private Optional<String> look(double[] eye, double[] dir, double[] feet) {
        return RoadOverlayRenderer.describeLookedAt(snapshot, eye, dir, feet, 48);
    }

    @Test
    void nodeLabelsShowADesignedPlaza() {
        assertEquals("Node #4 junction \"Brink\" · plaza r12 (locked)", RoadOverlayRenderer.nodeLabel(
            new RoadNode(4, 0, 64, 0, net.knightsandkings.knk.core.domain.roads.RoadNodeKind.JUNCTION, "Brink", 4, true, 12)));
        assertEquals("Node #5 endpoint", RoadOverlayRenderer.nodeLabel(
            new RoadNode(5, 0, 64, 0, net.knightsandkings.knk.core.domain.roads.RoadNodeKind.ENDPOINT, null, 5)));
    }

    @Test
    void aNodeIsNamedAtAnyDistanceAlongTheView() {
        // 30 blocks away (the old rule only looked 12 blocks ahead)
        Optional<String> far = look(new double[] {40.5, 66.6, 30.5}, new double[] {0, 0, -1}, new double[] {40.5, 65, 30.5});
        assertEquals(Optional.of("Node #2 junction"), far);
        // 3 blocks away, looking down at it from above (the old point lay underground)
        Optional<String> below = look(new double[] {0.5, 70, 3.5}, new double[] {0, -2, -1}, new double[] {0.5, 68.4, 3.5});
        assertEquals(Optional.of("Node #1 junction \"Market\" (locked)"), below);
    }

    @Test
    void aPillarBeatsTheEdgeItStandsOn() {
        // Looking along the road at the East Gate junction: the edge runs under the whole ray.
        Optional<String> text = look(new double[] {20.5, 66.6, 0.5}, new double[] {1, 0, 0}, new double[] {20.5, 65, 0.5});
        assertEquals(Optional.of("Node #2 junction"), text);
    }

    @Test
    void theEdgeUnderTheViewIsNamedWhenNoNodeIs() {
        // Looking down at the road's middle from the side.
        Optional<String> text = look(new double[] {20.5, 70, 8.5}, new double[] {0, -6, -8}, new double[] {20.5, 68.4, 8.5});
        assertTrue(text.orElse("").startsWith("Edge #10"), text.toString());
    }

    @Test
    void lookingAtTheSkyNamesTheNodeTheHereCommandsUse() {
        Optional<String> text = look(new double[] {2.5, 66.6, 2.5}, new double[] {0, 1, 0}, new double[] {2.5, 65, 2.5});
        assertEquals(Optional.of("Node #1 junction \"Market\" (locked) (here)"), text);
        assertEquals(Optional.empty(), look(new double[] {20.5, 66.6, 20.5}, new double[] {0, 1, 0}, new double[] {20.5, 65, 20.5}));
    }

    @Test
    void aLookedAtProposalItemIsNamedWithItsNumber() {
        TileProposal.Item added = new TileProposal.Item(3, TileProposal.Kind.EDGE_ADDED, 0,
            new TileProposal.End(2, 40, 64, 0, RoadNodeKind.JUNCTION), new TileProposal.End(0, 40, 64, 30, RoadNodeKind.ENDPOINT),
            List.of(new int[] {40, 64, 0}, new int[] {40, 64, 30}), List.of(), 30, 3, OptionalInt.empty(), List.of(), List.of(),
            List.of(), null, null, "");
        java.util.Map<TileKey, List<TileProposal.Item>> pending = java.util.Map.of(new TileKey("world", 0, 0), List.of(added));

        // Standing at (35, 65, 15) looking east: the ray crosses the new road (x = 40) between its corners.
        Optional<String> label = RoadOverlayRenderer.describeLookedAtProposal(pending, new double[] {35, 66.6, 15.5},
            new double[] {1, -0.1, 0}, 48);

        assertEquals(Optional.of("Proposal 0,0 · item 3: added edge 30 m (#2 → new endpoint)"), label);
        assertEquals(Optional.empty(), RoadOverlayRenderer.describeLookedAtProposal(pending, new double[] {35, 66.6, 15.5},
            new double[] {-1, 0, 0}, 48));
        assertEquals(OverlayColors.PROPOSAL_ADDED, RoadOverlayRenderer.proposalColour(TileProposal.Kind.EDGE_ADDED));
        assertEquals(OverlayColors.PROPOSAL_REMOVED, RoadOverlayRenderer.proposalColour(TileProposal.Kind.NODE_REMOVED));
    }
}
