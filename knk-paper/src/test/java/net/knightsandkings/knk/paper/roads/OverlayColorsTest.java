package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import org.junit.jupiter.api.Test;

/** DESIGN §7 overlay colours: street hash, unlabelled grey, stale orange, closed red. */
class OverlayColorsTest {

    private static RoadEdge edge(OptionalInt street, Set<RoadEdgeFlag> flags, boolean stale, List<Integer> gates) {
        return new RoadEdge(1, 1, 2, List.of(new int[] {0, 0, 0}, new int[] {1, 0, 0}), 1, 1, OptionalInt.empty(), street, 1, flags,
            gates, List.of(), List.of(), RoadEdgeSource.DETECTED, stale);
    }

    @Test
    void statusBeatsStreetAndStreetsAreStable() {
        assertEquals(OverlayColors.CLOSED, OverlayColors.edge(edge(OptionalInt.of(4), EnumSet.of(RoadEdgeFlag.CLOSED), true, List.of())));
        assertEquals(OverlayColors.STALE, OverlayColors.edge(edge(OptionalInt.of(4), Set.of(), true, List.of())));
        assertEquals(OverlayColors.NO_GPS, OverlayColors.edge(edge(OptionalInt.of(4), EnumSet.of(RoadEdgeFlag.NO_GPS), false, List.of())));
        assertEquals(OverlayColors.UNLABELLED, OverlayColors.edge(edge(OptionalInt.empty(), Set.of(), false, List.of())));
        int street4 = OverlayColors.edge(edge(OptionalInt.of(4), Set.of(), false, List.of()));
        assertEquals(OverlayColors.street(4), street4);
        assertNotEquals(OverlayColors.street(4), OverlayColors.street(5));
        assertNotEquals(OverlayColors.UNLABELLED, street4);
        assertEquals(Optional.of("closed"), OverlayColors.status(edge(OptionalInt.empty(), EnumSet.of(RoadEdgeFlag.CLOSED), false, List.of())));
        assertTrue(OverlayColors.hasGateMarker(edge(OptionalInt.empty(), Set.of(), false, List.of(7))));
    }

    @Test
    void nodesByKindAndName() {
        assertEquals(OverlayColors.NAMED_NODE, OverlayColors.node(RoadNodeKind.JUNCTION, true));
        assertEquals(OverlayColors.JUNCTION, OverlayColors.node(RoadNodeKind.JUNCTION, false));
        assertEquals(OverlayColors.ANCHOR, OverlayColors.node(RoadNodeKind.ANCHOR, false));
        assertEquals(0xE5, OverlayColors.red(OverlayColors.CLOSED));
        assertEquals(0x39, OverlayColors.green(OverlayColors.CLOSED));
        assertEquals(0x35, OverlayColors.blue(OverlayColors.CLOSED));
    }
}
