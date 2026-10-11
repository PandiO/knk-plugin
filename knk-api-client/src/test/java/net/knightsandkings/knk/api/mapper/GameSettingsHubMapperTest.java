package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkGroupOverride;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;

/** The API's hub block and the groups' outage fields (hub DESIGN §16 Phase 4) reach the plugin. */
class GameSettingsHubMapperTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theHubBlockAndTheGroupOutageFieldsAreRead() throws Exception {
        GameSettingsDto dto = json.readValue("""
            {"joinSpawnMode":"WorldSpawn",
             "groupOverrides":[{"permissionGroupId":3,"precedence":1,"outageAction":"Kick","outageDelaySeconds":30,
                                "exemptInStaffMode":true}],
             "hub":{"domainId":4,"domainName":"Hubtown","worldName":"hub","wgRegionId":"town_4",
                    "spawnReference":{"sourceType":"Location","sourceId":9,
                                      "location":{"x":2.5,"y":65,"z":2.5,"world":"hub"}},
                    "outageAction":"SendToWorldSpawn","outageDelaySeconds":0,
                    "rules":{"dropPickup":false,"healIntervalTicks":40}}}
            """, GameSettingsDto.class);

        KnkGameSettings settings = GameSettingsMapper.toCore(dto);

        KnkHubSettings hub = settings.hub();
        assertTrue(hub.configured());
        assertEquals("town_4", hub.wgRegionId());
        assertEquals("hub", hub.spawn().snapshot().world());
        assertEquals(KnkHubSettings.OutageAction.SEND_TO_WORLD_SPAWN, hub.outageAction());
        assertFalse(hub.rules().dropPickup());
        assertEquals(40, hub.rules().healIntervalTicks());
        assertEquals(10, hub.rules().feedIntervalTicks(), "a field the API did not send keeps the Peaceful default");

        KnkGroupOverride staff = settings.groupOverrides().get(0);
        assertEquals(KnkHubSettings.OutageAction.KICK, staff.outageAction());
        assertEquals(30, staff.outageDelaySeconds());
        assertTrue(staff.exemptInStaffMode());
        assertFalse(staff.exemptInOwnerMode());
    }

    @Test
    void withoutAHubBlockThereIsNoHub() throws Exception {
        KnkGameSettings settings = GameSettingsMapper.toCore(json.readValue("{\"joinSpawnMode\":\"WorldSpawn\"}", GameSettingsDto.class));

        assertFalse(settings.hub().configured());
        assertEquals(KnkHubSettings.OutageAction.SEND_TO_HUB, settings.hub().outageAction());
    }
}
