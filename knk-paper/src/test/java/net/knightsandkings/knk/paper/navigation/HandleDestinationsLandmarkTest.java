package net.knightsandkings.knk.paper.navigation;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.engine.LandmarkProvider;
import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * A landmark's place as the session's {@link Destination} (off-road phase 5.1, PandiO/knk-workspace#708, A13; since
 * PandiO/knk-workspace#985 a point or an area of regions, LD08, LD22).
 */
class HandleDestinationsLandmarkTest {

    private static Destination of(Landmark landmark) {
        return HandleDestinations.of(LandmarkProvider.candidate(landmark), Candidate.Mode.DEFAULT, "world");
    }

    /** A14 (#985, LD22): the area's sub-regions travel with the region destination. */
    @Test
    void anAreaIsItsMainRegion_WithTheOtherRegionsAsArrival() {
        Destination d = of(new Landmark(1, "Tower", "world", 10, 65, 10, List.of("domain_1", "domain_1_sub_2"), null, null,
            true));
        assertEquals(Destination.Kind.REGION, d.kind());
        assertEquals("domain_1", d.regionId());
        assertTrue(d.isArea());
        assertEquals(List.of("domain_1_sub_2"), d.areaRegionIds());
        assertFalse(Destination.region("Town", "world", "town_1").isArea(), "a domain's region is not an area");
        assertNull(Destination.point("x", "world", 1, 2, 3).withAreaRegions(List.of("a")).areaRegionIds(), "REGION only");
        assertFalse(d.sameAs(d.withAreaRegions(List.of())), "the area's regions count in sameAs");
    }

    @Test
    void noExtentIsAPlainPoint_aRegionExtentIsTheRegion() {
        Destination point = of(new Landmark(2, "Well", "world", 1, 65, 1, List.of(), null, null, true));
        assertNull(point.arrivalRadius());
        assertFalse(point.withinArrivalRadius(1, 65, 1), "arrive-distance decides");
        Destination region = of(new Landmark(3, "Hall", "world", 1, 65, 1, List.of("hall"), null, null, true));
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
