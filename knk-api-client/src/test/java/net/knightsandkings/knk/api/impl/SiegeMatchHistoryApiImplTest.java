package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.ports.api.SiegeMatchHistoryApi.PlayerMatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alpha run 1 (PandiO/knk-workspace#174): the public match list as {@code /siege history} reads it. */
class SiegeMatchHistoryApiImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsTheApiRows() throws Exception {
        String json = """
                [{"id":41,"siegeLobbyName":"Siege - Cinix","siegeScenarioName":"Siege of Cinix","status":"Completed",
                  "startedAt":"2026-10-25T19:00:00Z","endedAt":"2026-10-25T19:10:00Z","endReason":"InstantVictory",
                  "participantCount":6,
                  "team":{"id":202,"name":"Raiders","chatColor":"RED","role":"Attacker","allianceGroup":2,"outcome":"Win"},
                  "participant":{"userId":3,"outcome":"Win","presentAtEnd":true,"kills":4,"deaths":0,"coinsAwarded":300,
                                 "expAwarded":31,"activeSeconds":600,"matchSeconds":600}},
                 {"id":40,"siegeScenarioName":"Harbor","endedAt":null,"team":null,
                  "participant":{"outcome":"Aborted","kills":null,"deaths":null,"expAwarded":0}}]
                """;
        List<PlayerMatch> matches = SiegeMatchHistoryApiImpl.toCore(objectMapper.readValue(json,
                new TypeReference<List<SiegeMatchHistoryApiImpl.SummaryDto>>() { }));

        assertEquals(2, matches.size());
        PlayerMatch first = matches.get(0);
        assertEquals(41L, first.matchId());
        assertEquals(Instant.parse("2026-10-25T19:10:00Z"), first.endedAt());
        assertEquals(("Raiders" + "RED" + "Win"), first.teamName() + first.teamChatColor() + first.outcome());
        assertEquals((Integer) 4, first.kills());
        assertEquals((Integer) 300, first.coinsAwarded());
        assertEquals(31, first.expAwarded());

        PlayerMatch second = matches.get(1);
        assertNull(second.endedAt());
        assertNull(second.teamName());
        assertNull(second.kills());
        assertEquals("Aborted", second.outcome());
    }

    @Test
    void nothingIsAnEmptyList() {
        assertTrue(SiegeMatchHistoryApiImpl.toCore(null).isEmpty());
    }
}
