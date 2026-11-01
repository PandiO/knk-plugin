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
}
