package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.ClanDtos.ClanDto;
import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import net.knightsandkings.knk.core.domain.clan.KnkClan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 1: GET /api/Clans/{id} JSON (knk-web-api ClanReadDto) to the core record. */
class ClanMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsFullClanWithOrderedBanner() throws Exception {
        String json = """
                {
                  "id": 3, "name": "Cinix Garrison", "isNpc": true, "chatColor": "GOLD",
                  "bannerDesignId": 1,
                  "bannerDesign": {
                    "id": 1, "name": "Crown", "baseColor": "RED", "exceedsSurvivalLoomLimit": false,
                    "layers": [
                      { "id": 8, "bannerDesignId": 1, "sortOrder": 1, "patternKey": "minecraft:border", "color": "BLACK" },
                      { "id": 7, "bannerDesignId": 1, "sortOrder": 0, "patternKey": "minecraft:stripe_top", "color": "YELLOW" }
                    ]
                  },
                  "defaultForTownId": 7, "defaultForTownName": "Cinix"
                }
                """;

        KnkClan clan = ClanMapper.toCore(objectMapper.readValue(json, ClanDto.class));

        assertEquals(3, clan.id());
        assertTrue(clan.npc());
        assertEquals("GOLD", clan.chatColor());
        assertEquals(7, clan.defaultForTownId());
        assertEquals("RED", clan.bannerDesign().baseColor());
        assertEquals(List.of("minecraft:stripe_top", "minecraft:border"),
                clan.bannerDesign().layers().stream().map(KnkBannerLayer::patternKey).toList());
    }

    @Test
    void toleratesMissingOptionalParts() throws Exception {
        KnkClan clan = ClanMapper.toCore(objectMapper.readValue(
                "{ \"id\": 4, \"name\": \"Adhoc\", \"bannerDesignId\": 2 }", ClanDto.class));

        assertFalse(clan.npc());
        assertEquals("WHITE", clan.chatColor());
        assertNull(clan.bannerDesign());
        assertNull(clan.defaultForTownId());
    }
}
