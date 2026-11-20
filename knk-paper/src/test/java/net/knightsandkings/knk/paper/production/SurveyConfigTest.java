package net.knightsandkings.knk.paper.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.survey.SurveySettings;

/** production.surveys.* (PandiO/knk-workspace#679): the bundled defaults, a missing block and the values read. */
class SurveyConfigTest {

    @Test
    void theBundledConfig_hasTheFeatureOff_withTheDocumentedDefaults() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(SurveyConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        SurveySettings settings = SurveyConfig.read(bundled);

        assertEquals(SurveySettings.defaults(false), settings);
        assertEquals(5, settings.pollMinutes());
        assertEquals(2, settings.chunksPerTick());
        assertEquals(256, settings.maxAreaChunks());
        assertFalse(settings.scheduledLoadsChunks());
    }

    @Test
    void aServerWithoutTheBlock_usesTheDefaults_andIsOffWithoutTheSwitch() {
        assertEquals(SurveySettings.defaults(false), SurveyConfig.read(new YamlConfiguration()));
        assertEquals(SurveySettings.defaults(false), SurveyConfig.read(null));
    }

    @Test
    void theValuesAreRead() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                production:
                  enabled: true
                  surveys:
                    poll-minutes: 0
                    batch: 5
                    chunks-per-tick: 4
                    max-area-blocks: 1000
                    max-area-chunks: 9
                    scheduled-loads-chunks: true
                    notable-change-blocks: 0
                    notable-cooldown-minutes: 2
                    areas-refresh-minutes: 1
                """);
        SurveySettings settings = SurveyConfig.read(config);

        assertTrue(settings.enabled());
        assertEquals(new SurveySettings(true, 0, 5, 4, 1000, 9, true, 0, 2, 1), settings);
    }
}
