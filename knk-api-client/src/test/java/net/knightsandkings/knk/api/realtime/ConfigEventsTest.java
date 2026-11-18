package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#182: the S3 payloads as knk-web-api sends them (camelCase, Realtime/Contracts/ConfigContracts.cs)
 * parse into the records the handlers use; anything unusable is null.
 */
class ConfigEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RealtimeEnvelope envelope(String type, String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":4,\"type\":\"" + type + "\",\"at\":\"2026-11-02T12:00:00Z\","
            + "\"actor\":{\"userId\":9,\"source\":\"web\"},\"payload\":" + payload + "}", mapper);
    }

    @Test
    void entityChanged() {
        var event = ConfigEvents.EntityChanged.parse(envelope(ConfigEvents.ENTITY_CHANGED,
            "{\"type\":\"Town\",\"id\":4,\"op\":\"Created\",\"key\":\"town_4\"}"));
        assertEquals(new ConfigEvents.EntityChanged("Town", 4, ConfigEvents.CREATED, "town_4"), event);
        assertFalse(event.all());

        var all = ConfigEvents.EntityChanged.parse(envelope(ConfigEvents.ENTITY_CHANGED,
            "{\"type\":\"MinecraftMaterialRef\",\"id\":null,\"op\":\"Updated\",\"key\":null}"));
        assertTrue(all.all());
        assertNull(all.key());

        assertNull(ConfigEvents.EntityChanged.parse(envelope(ConfigEvents.ENTITY_CHANGED, "{\"id\":4}")));
        assertNull(ConfigEvents.EntityChanged.parse(envelope(ConfigEvents.ENTITY_CHANGED, "{\"type\":\"Town\",\"id\":0}")));
    }

    @Test
    void gateChanged() {
        var event = ConfigEvents.GateChanged.parse(envelope(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":3,\"op\":\"Deleted\"}"));
        assertEquals(new ConfigEvents.GateChanged(3, ConfigEvents.DELETED), event);
        assertTrue(event.deleted());
        assertNull(ConfigEvents.GateChanged.parse(envelope(ConfigEvents.GATE_CHANGED, "{\"op\":\"Updated\"}")));
    }

    @Test
    void domainAccessScopeAndRoads() {
        assertEquals(new ConfigEvents.DomainAccessChanged(8),
            ConfigEvents.DomainAccessChanged.parse(envelope(ConfigEvents.DOMAIN_ACCESS_CHANGED, "{\"domainId\":8}")));
        assertNull(ConfigEvents.DomainAccessChanged.parse(envelope(ConfigEvents.DOMAIN_ACCESS_CHANGED, "{}")));
        assertEquals("hub", ConfigEvents.ScopeChanged.parse(envelope(ConfigEvents.GAME_SETTINGS_CHANGED, "{\"scope\":\"hub\"}")).scope());
        assertEquals("world", ConfigEvents.RoadNetworkChanged.parse(envelope(ConfigEvents.ROAD_NETWORK_CHANGED, "{\"world\":\"world\"}")).world());
        assertNull(ConfigEvents.RoadNetworkChanged.parse(envelope(ConfigEvents.ROAD_NETWORK_CHANGED, "{\"world\":null}")).world());
    }

    @Test
    void worldsReportUsesTheRestBodyNames() throws Exception {
        Map<String, Object> body = ConfigEvents.worldsReport(" survival ",
            List.of(ConfigEvents.world("world", "world", "NORMAL", true, 2, true)));
        assertEquals("{\"serverId\":\"survival\",\"runtimeWorlds\":[{\"worldName\":\"world\",\"folderName\":\"world\","
            + "\"environment\":\"NORMAL\",\"loaded\":true,\"playerCount\":2,\"isPrimary\":true}]}", mapper.writeValueAsString(body));
        assertNull(ConfigEvents.worldsReport("  ", List.of()).get("serverId"));
    }
}
