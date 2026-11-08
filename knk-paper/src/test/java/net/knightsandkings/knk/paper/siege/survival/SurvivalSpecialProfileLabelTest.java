package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.paper.siege.survival.SurvivalConfiguration.SpecialProfileLabel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Link S8b: the special profile's API key and name travel with the configuration (checkpoint key, announcement name). */
class SurvivalSpecialProfileLabelTest {

    @Test
    void labelsAreMappedByProfileIdWithFallbacks() throws Exception {
        String body = "{\"siegeScenarioId\": 1, \"minPlayers\": 1, \"humanTeamId\": 1, \"npcTeamId\": 2, \"monsterTypes\": [{\"entityType\": \"ZOMBIE\"}],"
            + " \"specialWaves\": { \"profiles\": [ { \"id\": 3, \"key\": \"wolves\", \"name\": \"Wolves\", \"monsterTypes\": [{\"entityType\": \"WOLF\"}] },"
            + " { \"id\": 4, \"key\": \"boss\", \"name\": null, \"monsterTypes\": [{\"entityType\": \"ZOMBIE\"}] }, { \"id\": null, \"key\": \"x\" } ], \"entries\": [] },"
            + " \"readiness\": {\"isReady\": true}}";
        SurvivalConfiguration c = SurvivalConfigurationMapper.map(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(body, ConfigurationDto.class));
        assertEquals(2, c.specialProfileLabels().size());
        assertEquals(new SpecialProfileLabel("wolves", "Wolves"), c.specialProfileLabels().get("3"));
        assertEquals("wolves", c.specialProfileKey("3"));
        assertEquals("Wolves", c.specialProfileName("3"));
        assertEquals("boss", c.specialProfileKey("4"));
        assertEquals("boss", c.specialProfileName("4"), "no name: the key");
        assertEquals("9", c.specialProfileKey("9"));
        assertEquals("9", c.specialProfileName("9"));
        assertTrue(c.specialProfiles().containsKey("3"));
        SurvivalConfiguration none = SurvivalModeHooksTest.survivalConfig(1, true);
        assertTrue(none.specialProfileLabels().isEmpty());
    }
}
