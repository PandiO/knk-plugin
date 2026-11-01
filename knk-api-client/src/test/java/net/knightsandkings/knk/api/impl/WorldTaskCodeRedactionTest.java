package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#180: task codes stay out of the log, and the claim body uses the API's field names. */
class WorldTaskCodeRedactionTest {
    @Test
    void aTaskCodeInTheLookupUrlIsRedacted() {
        assertEquals("http://x/api/WorldTasks/by-link-code/<redacted>",
            BaseApiImpl.loggableUrl("http://x/api/WorldTasks/by-link-code/ABC123"));
        assertEquals("http://x/api/WorldTasks/12/claim", BaseApiImpl.loggableUrl("http://x/api/WorldTasks/12/claim"));
    }

    @Test
    void theClaimBodyNamesWhatTheApiReads() throws Exception {
        String json = new ObjectMapper().writeValueAsString(ClaimTaskDto.byCode("ABC123", "survival-1", "Steve", "u-1"));

        assertTrue(json.contains("\"taskCode\":\"ABC123\""), json);
        assertTrue(json.contains("\"claimedByServerId\":\"survival-1\""), json);
        assertTrue(json.contains("\"claimedByMinecraftUsername\":\"Steve\""), json);
        assertTrue(json.contains("\"claimedByUuid\":\"u-1\""), json);
        assertTrue(json.contains("\"claimMode\":\"code\""), json);
        String headless = new ObjectMapper().writeValueAsString(ClaimTaskDto.headless("survival-1"));
        assertFalse(headless.contains("taskCode"), headless);
        assertTrue(headless.contains("\"claimMode\":\"headless\""), headless);
    }

    @Test
    void aTaskFromTheApiReadsWithItsNewFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String json = "{\"id\":7,\"status\":\"InProgress\",\"taskType\":\"LocationSelection\",\"linkCode\":\"AB12CD\","
            + "\"claimedByUuid\":\"u-1\",\"claimedAt\":\"2026-11-01T12:00:00.0000000\",\"expiresAt\":\"2026-11-01T12:30:00Z\","
            + "\"leaseUntil\":null,\"releaseCount\":1,\"offerState\":\"None\",\"entityType\":\"Town\"}";

        net.knightsandkings.knk.api.dto.WorldTaskDto task = mapper.readValue(json, net.knightsandkings.knk.api.dto.WorldTaskDto.class);

        assertEquals(7, task.id());
        assertEquals("u-1", task.claimedByUuid());
        assertEquals("Town", task.entityType());
        assertFalse(task.isPending());
    }
}
