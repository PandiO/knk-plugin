package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskLeaseDto;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#181: the WT2 payloads as knk-web-api sends them (camelCase, WorldTaskContracts.cs). */
class WorldTaskEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String UUID_A = "00000000-0000-0000-0000-00000000000a";

    private RealtimeEnvelope envelope(String type, String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"" + type + "\",\"payload\":" + payload + "}", mapper);
    }

    @Test
    void offeredReadsEveryField() {
        var offered = WorldTaskEvents.Offered.parse(envelope("WorldTaskOffered",
            "{\"taskId\":7,\"uuid\":\"" + UUID_A + "\",\"taskType\":\"LocationSelection\",\"fieldName\":\"Location\","
                + "\"label\":\"Town › Location\",\"entityType\":\"Town\",\"mode\":\"accept\",\"fromUsername\":\"Alice\",\"timeoutSeconds\":120}"));

        assertEquals(7, offered.taskId());
        assertEquals(UUID.fromString(UUID_A), offered.uuid());
        assertEquals("Town › Location", offered.label());
        assertEquals("Alice", offered.fromUsername());
        assertEquals(120, offered.timeoutSeconds());
        assertFalse(offered.autoClaim());
    }

    @Test
    void offeredDefaults_AndRefusesWhatItCantUse() {
        var auto = WorldTaskEvents.Offered.parse(envelope("WorldTaskOffered",
            "{\"taskId\":8,\"uuid\":\"" + UUID_A + "\",\"mode\":\"auto-claim\"}"));
        assertTrue(auto.autoClaim());
        assertEquals("world task #8", auto.label());
        assertEquals(120, auto.timeoutSeconds());

        assertNull(WorldTaskEvents.Offered.parse(envelope("WorldTaskOffered", "{\"taskId\":8,\"uuid\":\"nope\"}")));
        assertNull(WorldTaskEvents.Offered.parse(envelope("WorldTaskOffered", "{\"uuid\":\"" + UUID_A + "\"}")));
    }

    @Test
    void cancelledWithdrawnAndCreated() {
        var cancelled = WorldTaskEvents.Cancelled.parse(envelope("WorldTaskCancelled",
            "{\"taskId\":5,\"status\":\"Pending\",\"uuid\":null,\"reason\":\"Replaced\"}"));
        assertEquals(5, cancelled.taskId());
        assertEquals("Pending", cancelled.status());
        assertNull(cancelled.uuid());

        assertEquals(6, WorldTaskEvents.OfferWithdrawn.parse(envelope("WorldTaskOfferWithdrawn",
            "{\"taskId\":6,\"uuid\":\"" + UUID_A + "\"}")).taskId());
        assertEquals("GateBlockScan", WorldTaskEvents.Created.parse(envelope("WorldTaskCreated",
            "{\"taskId\":9,\"taskType\":\"GateBlockScan\"}")).taskType());
        assertNull(WorldTaskEvents.Created.parse(envelope("WorldTaskCreated", "{}")));
    }

    @Test
    void offerClaimAndHandBackBodies() throws Exception {
        assertEquals("{\"claimedByServerId\":\"s1\",\"claimedByMinecraftUsername\":\"Steve\",\"claimedByUuid\":\"" + UUID_A
                + "\",\"claimMode\":\"offer\"}",
            mapper.writeValueAsString(ClaimTaskDto.byOffer("s1", "Steve", UUID_A)));
        assertEquals("{\"serverId\":\"s1\",\"reason\":\"r\",\"handBack\":true}",
            mapper.writeValueAsString(new WorldTaskLeaseDto("s1", "r", true)));
        assertEquals("{\"serverId\":\"s1\",\"reason\":\"r\"}", mapper.writeValueAsString(new WorldTaskLeaseDto("s1", "r")));
    }
}
