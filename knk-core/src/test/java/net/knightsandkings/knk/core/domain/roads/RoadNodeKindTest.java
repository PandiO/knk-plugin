package net.knightsandkings.knk.core.domain.roads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadNodeKindTest {

    @Test
    void apiNamesAreTheWebApiEnumNames() {
        assertEquals("Junction", RoadNodeKind.JUNCTION.apiName());
        assertEquals("Endpoint", RoadNodeKind.ENDPOINT.apiName());
        assertEquals("Boundary", RoadNodeKind.BOUNDARY.apiName());
        assertEquals("Anchor", RoadNodeKind.ANCHOR.apiName());
    }

    @Test
    void fromApiNameRoundTripsAndIgnoresCase() {
        for (RoadNodeKind kind : RoadNodeKind.values()) {
            assertEquals(kind, RoadNodeKind.fromApiName(kind.apiName()));
            assertEquals(kind, RoadNodeKind.fromApiName(" " + kind.apiName().toLowerCase() + " "));
        }
    }
}
