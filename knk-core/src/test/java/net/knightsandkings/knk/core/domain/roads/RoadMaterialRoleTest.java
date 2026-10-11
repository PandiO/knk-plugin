package net.knightsandkings.knk.core.domain.roads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoadMaterialRoleTest {

    @Test
    void apiNamesMatchTheWebApiEnum() {
        assertEquals("Surface", RoadMaterialRole.SURFACE.apiName());
        assertEquals("Edge", RoadMaterialRole.EDGE.apiName());
        assertEquals("Accent", RoadMaterialRole.ACCENT.apiName());
        assertEquals("Overlay", RoadMaterialRole.OVERLAY.apiName());
    }

    @Test
    void fromApiNameRoundTripsAndIgnoresCase() {
        for (RoadMaterialRole role : RoadMaterialRole.values()) {
            assertEquals(role, RoadMaterialRole.fromApiName(role.apiName()));
        }
        assertEquals(RoadMaterialRole.EDGE, RoadMaterialRole.fromApiName(" edge "));
        assertThrows(IllegalArgumentException.class, () -> RoadMaterialRole.fromApiName("Kerb"));
    }
}
