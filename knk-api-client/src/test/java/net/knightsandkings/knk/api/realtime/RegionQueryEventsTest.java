package net.knightsandkings.knk.api.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** PandiO/knk-workspace#988: the API's region query payload (RegionQueryPayload, camelCase). */
class RegionQueryEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RealtimeEnvelope envelope(String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":4,\"type\":\"RegionGeometryQuery\",\"at\":\"2026-11-22T12:00:00Z\","
                + "\"actor\":null,\"payload\":" + payload + "}", MAPPER);
    }

    @Test
    void parse_ReadsTheQuery_TrimmedWithAnOptionalWorld() {
        var query = RegionQueryEvents.RegionQuery.parse(envelope("{\"queryId\":\" q1 \",\"regionId\":\" siege_spot_3 \",\"world\":\"siege\"}"));
        assertEquals(new RegionQueryEvents.RegionQuery("q1", "siege_spot_3", "siege"), query);
        var noWorld = RegionQueryEvents.RegionQuery.parse(envelope("{\"queryId\":\"q2\",\"regionId\":\"r\",\"world\":null}"));
        assertNull(noWorld.world());
        assertNull(RegionQueryEvents.RegionQuery.parse(envelope("{\"queryId\":\"q3\",\"regionId\":\"r\",\"world\":\" \"}")).world());
    }

    @Test
    void parse_WithoutAQueryIdOrRegion_IsNull() {
        assertNull(RegionQueryEvents.RegionQuery.parse(envelope("{\"regionId\":\"r\"}")));
        assertNull(RegionQueryEvents.RegionQuery.parse(envelope("{\"queryId\":\"q\",\"regionId\":\"  \"}")));
        assertNull(RegionQueryEvents.RegionQuery.parse(envelope("[]")));
        assertNull(RegionQueryEvents.RegionQuery.parse(null));
    }

    @Test
    void theWireNames_MatchTheApi() {
        assertEquals("RegionGeometryQuery", RegionQueryEvents.REGION_GEOMETRY_QUERY);
        assertEquals("RegionGeometryResult", RegionQueryEvents.REGION_GEOMETRY_RESULT);
        assertEquals("SpotSiteCheckQuery", RegionQueryEvents.SPOT_SITE_CHECK_QUERY);
        assertEquals("SpotSiteCheckResult", RegionQueryEvents.SPOT_SITE_CHECK_RESULT);
        assertEquals("not-found", RegionQueryEvents.NOT_FOUND);
    }
}
