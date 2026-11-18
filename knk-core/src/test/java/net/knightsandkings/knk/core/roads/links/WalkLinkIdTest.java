package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.roads.route.RoutingView;
import net.knightsandkings.knk.core.roads.route.WalkLink;
import net.knightsandkings.knk.core.roads.route.WalkLinkId;

/**
 * Off-road phase 2 (#705), decision O17: walk links have their own id space, disjoint from stored road ids, the routing
 * view's cut pieces and split nodes (2 000 000 000 upward) and the router's negative virtual nodes (REV7 § 7.2).
 */
class WalkLinkIdTest {

    @Test
    void everyLinkIdLiesAboveEveryIntId() {
        int[] nodes = {0, 1, 17, RoutingView.FIRST_SYNTHETIC_ID - 1, RoutingView.FIRST_SYNTHETIC_ID,
            RoutingView.FIRST_SYNTHETIC_ID + 123_456, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int from : nodes) {
            for (int to : nodes) {
                if (from == to) {
                    continue;
                }
                WalkLinkId id = WalkLinkId.of(from, to);
                assertTrue(id.value() >= WalkLinkId.FIRST_VALUE, id.toString());
                assertTrue(id.value() > Integer.MAX_VALUE, "above stored ids and the cut pieces: " + id);
                assertTrue(id.value() > 0, "never one of the router's negative virtual nodes: " + id);
                assertEquals(from, id.fromNode());
                assertEquals(to, id.toNode());
            }
        }
    }

    @Test
    void linksAreDirectionalAndDistinct() {
        WalkLinkId ab = WalkLinkId.of(2, 5);
        assertNotEquals(ab, WalkLinkId.of(5, 2));
        assertEquals(WalkLinkId.of(5, 2), ab.reversed());
        assertNotEquals(WalkLinkId.of(2, 6), WalkLinkId.of(3, 5));
        assertNotEquals(WalkLinkId.of(0, Integer.MAX_VALUE), WalkLinkId.of(Integer.MAX_VALUE, 0));
    }

    @Test
    void negativeOrSameNodesAndSmallValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> WalkLinkId.of(-1, 3), "a virtual node is no link end");
        assertThrows(IllegalArgumentException.class, () -> WalkLinkId.of(3, -2));
        assertThrows(IllegalArgumentException.class, () -> WalkLinkId.of(3, 3));
        assertThrows(IllegalArgumentException.class, () -> new WalkLinkId(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new WalkLinkId(-5));
    }

    @Test
    void theProbeEdgeOfALinkIsBelowEveryEdgeId() {
        WalkLink link = CourtyardNetwork.courtyardLink(java.util.List.of(), java.util.List.of());
        assertEquals(WalkLink.PROBE_EDGE_ID, link.tagsEdge().id());
        assertTrue(link.tagsEdge().id() < 0, "stored edges and pieces are never negative");
    }
}
