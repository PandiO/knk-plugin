package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.WalkLinkSource;

/** Off-road phase 2 (#705), A03: which pairs of road nodes may get a walk link (DESIGN § 5.2.1, O28). */
class WalkLinkCandidatesTest {

    private final CourtyardNetwork net = new CourtyardNetwork();

    @Test
    void aRoadMoreThanTheRatioTimesTheStraightDistanceWithinRangeIsACandidate() {
        WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
        List<WalkLinkSource.Candidate> fromA = candidates.from(CourtyardNetwork.A);
        assertEquals(List.of(CourtyardNetwork.B), fromA.stream().map(WalkLinkSource.Candidate::to).toList(),
            "A -> B: 80 by road for 20 straight; A -> Q 50 for 36 is not; S 60 away is beyond the range");
        assertEquals(20, fromA.get(0).straight(), 1e-9);
        assertEquals(List.of(CourtyardNetwork.A),
            candidates.from(CourtyardNetwork.B).stream().map(WalkLinkSource.Candidate::to).toList(),
            "both ways; H (50 away) is beyond 40");
    }

    @Test
    void theRatioDecides() {
        // A -> B is 4 times the straight distance: a ratio of 4 or more leaves it out
        assertTrue(new WalkLinkCandidates(net.snapshot, 40, 4.0).from(CourtyardNetwork.A).isEmpty());
        assertEquals(1, new WalkLinkCandidates(net.snapshot, 40, 3.9).from(CourtyardNetwork.A).size());
    }

    @Test
    void theRangeDecides() {
        assertTrue(new WalkLinkCandidates(net.snapshot, 19, 2.0).from(CourtyardNetwork.A).isEmpty(), "B is 20 away");
        // with a range of 60 the road distance search reaches farther: S (60 straight, 60 by road) is still no candidate,
        // H (67 from A) is beyond it; B -> H (50, joined directly) is not either
        WalkLinkCandidates wide = new WalkLinkCandidates(net.snapshot, 60, 2.0);
        assertEquals(List.of(CourtyardNetwork.B), wide.from(CourtyardNetwork.A).stream().map(WalkLinkSource.Candidate::to)
            .toList());
    }

    @Test
    void splitNodesOtherComponentsAndPlumbingTakeNoPart() {
        WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
        assertTrue(candidates.from(CourtyardNetwork.G).isEmpty(), "a split node is never a link end");
        assertTrue(candidates.from(CourtyardNetwork.A).stream().noneMatch(c -> c.to() == CourtyardNetwork.G));
        assertTrue(candidates.from(CourtyardNetwork.A).stream().noneMatch(c -> c.to() == CourtyardNetwork.X),
            "X is 5 away but in another component");
        assertTrue(candidates.from(999).isEmpty(), "an unknown node");
    }

    @Test
    void roadDistancesFollowOneWayEdgesAndStopAtTheBound() {
        WalkLinkCandidates candidates = new WalkLinkCandidates(net.snapshot, 40, 2.0);
        var d = candidates.roadDistances(CourtyardNetwork.A, 80);
        assertEquals(80, d.get(CourtyardNetwork.B), 1e-9);
        assertEquals(60, d.get(CourtyardNetwork.S), 1e-9);
        assertTrue(!d.containsKey(CourtyardNetwork.T), "T is 140 away by road");
    }

    @Test
    void badSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new WalkLinkCandidates(net.snapshot, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new WalkLinkCandidates(net.snapshot, 40, 1));
    }
}
