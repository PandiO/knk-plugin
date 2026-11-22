package net.knightsandkings.knk.paper.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.RegionQueryEvents;
import net.knightsandkings.knk.core.regions.query.RegionShape;
import net.knightsandkings.knk.core.regions.query.RegionShapeLookup;

/**
 * PandiO/knk-workspace#988: the generic region geometry query over the game hub. The answer names the query id, a
 * region that doesn't exist answers not-found, a failing lookup answers error, a query id seen before gets no second
 * answer, and the world the API named is passed to the lookup.
 */
class RegionQueryRealtimeSyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Object[]> sent = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean hubUp = true;
    private RegionShapeLookup lookup;
    private RealtimeDispatcher dispatcher;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        lookup = (regionId, world) -> {
            asked.add(regionId + "@" + world);
            return "siege_spot_3".equals(regionId)
                    ? Optional.of(new RegionShape("siege", "cuboid", 0, 64, 0, 8, 80, 14, List.of()))
                    : Optional.empty();
        };
        dispatcher = new RealtimeDispatcher(logger);
        RegionQueryRealtimeSync.geometry(Runnable::run, (regionId, world) -> lookup.find(regionId, world),
                (method, body) -> {
                    sent.add(new Object[] {method, body});
                    return hubUp;
                }, logger).register(dispatcher);
    }

    private boolean dispatch(String queryId, String regionId, String world) {
        return dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"RegionGeometryQuery\","
                + "\"at\":\"2026-11-22T12:00:00Z\",\"actor\":null,\"payload\":{\"queryId\":\"" + queryId + "\",\"regionId\":\""
                + regionId + "\",\"world\":" + (world == null ? "null" : "\"" + world + "\"") + "}}", MAPPER));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(int i) {
        return (Map<String, Object>) sent.get(i)[1];
    }

    @Test
    void aKnownRegion_IsAnsweredWithItsShape() {
        assertTrue(dispatcher.types().contains(RegionQueryEvents.REGION_GEOMETRY_QUERY));
        assertTrue(dispatch("q1", "siege_spot_3", "siege"));

        assertEquals(1, sent.size());
        assertEquals(RegionQueryEvents.REGION_GEOMETRY_RESULT, sent.get(0)[0]);
        assertEquals("q1", body(0).get("queryId"));
        assertEquals("found", body(0).get("result"));
        assertEquals(14, body(0).get("maxZ"));
        assertEquals(List.of("siege_spot_3@siege"), asked);
    }

    @Test
    void anUnknownRegion_IsNotFound() {
        dispatch("q2", "nowhere", null);
        assertEquals("not-found", body(0).get("result"));
        assertEquals(List.of("nowhere@null"), asked);
    }

    @Test
    void aFailingLookup_AnswersError() {
        lookup = (regionId, world) -> {
            throw new IllegalStateException("WorldGuard isn't loaded");
        };
        dispatch("q3", "siege_spot_3", null);
        assertEquals("error", body(0).get("result"));
        assertTrue(((String) body(0).get("message")).contains("WorldGuard isn't loaded"));
    }

    @Test
    void theSameQueryId_IsAnsweredOnce() {
        dispatch("q4", "siege_spot_3", null);
        dispatch("q4", "siege_spot_3", null);
        assertEquals(1, sent.size());
    }

    @Test
    void aQueryWithoutARegion_IsIgnored() {
        dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":5,\"type\":\"RegionGeometryQuery\",\"at\":null,"
                + "\"actor\":null,\"payload\":{\"queryId\":\"q5\"}}", MAPPER));
        assertTrue(sent.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no usable query id or region id")), warnings.toString());
    }

    @Test
    void theHubDown_IsLogged() {
        hubUp = false;
        dispatch("q6", "siege_spot_3", null);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not sent (hub not connected)")), warnings.toString());
    }
}
