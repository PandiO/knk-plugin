package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.engine.LandmarkProvider;
import net.knightsandkings.knk.core.navigation.model.Landmark;

/** A landmark's place as the session's {@link Destination} (off-road phase 5.1, PandiO/knk-workspace#708, A13). */
class HandleDestinationsLandmarkTest {

    private static Destination of(Landmark landmark) {
        return HandleDestinations.of(LandmarkProvider.candidate(landmark), Candidate.Mode.DEFAULT, "world");
    }

    @Test
    void aRadiusExtentGivesThePointItsArrivalRadius() {
        Destination d = of(new Landmark(1, "Square", "world", 10, 65, 10, Landmark.Extent.RADIUS, null, 6.0, null, null, true));
        assertEquals(Destination.Kind.POINT, d.kind());
        assertEquals(6.0, d.arrivalRadius());
        assertTrue(d.withinArrivalRadius(15, 65, 10));
        assertFalse(d.withinArrivalRadius(17, 65, 10));
    }

    @Test
    void noExtentIsAPlainPoint_aRegionExtentIsTheRegion() {
        Destination point = of(new Landmark(2, "Well", "world", 1, 65, 1, Landmark.Extent.NONE, null, null, null, null, true));
        assertNull(point.arrivalRadius());
        assertFalse(point.withinArrivalRadius(1, 65, 1), "arrive-distance decides");
        Destination region = of(new Landmark(3, "Hall", "world", 1, 65, 1, Landmark.Extent.REGION, "hall", null, null, null, true));
        assertEquals(Destination.Kind.REGION, region.kind());
        assertEquals("hall", region.regionId());
        assertEquals("world", region.world());
    }

    @Test
    void theRadiusCountsInSameAs() {
        Destination a = Destination.point("x", "world", 1, 2, 3);
        assertTrue(a.sameAs(Destination.point("x", "world", 1, 2, 3)));
        assertFalse(a.sameAs(a.withArrivalRadius(4.0)));
        assertNull(Destination.region("x", "world", "r").withArrivalRadius(4.0).arrivalRadius(), "POINT only");
    }
}
