package net.knightsandkings.knk.paper.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.transport.LaneSettings;

/** production.logistics.lanes.* (PandiO/knk-workspace#681): the bundled defaults, a missing block and the values read. */
class LaneConfigTest {

    @Test
    void theBundledConfig_hasTheFeatureOff_withTheDocumentedDefaults() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(LaneConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        LaneSettings settings = LaneConfig.read(bundled);

        assertEquals(LaneSettings.defaults(false), settings);
        assertEquals(60, settings.intervalMinutes());
        assertEquals(100, settings.batch());
        assertEquals(48, settings.maxSnapDistance());
        assertEquals(true, settings.roadConnections());
    }

    @Test
    void aServerWithoutTheBlock_usesTheDefaults_andIsOffWithoutTheSwitch() {
        assertEquals(LaneSettings.defaults(false), LaneConfig.read(new YamlConfiguration()));
        assertEquals(LaneSettings.defaults(false), LaneConfig.read(null));
        YamlConfiguration on = new YamlConfiguration();
        on.set("production.enabled", true);
        assertEquals(LaneSettings.defaults(true), LaneConfig.read(on));
    }

    @Test
    void theValuesAreRead_andClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production.enabled", true);
        config.set("production.logistics.lanes.interval-minutes", 0);
        config.set("production.logistics.lanes.batch", 5000);
        config.set("production.logistics.lanes.max-snap-distance", 24.5);
        config.set("production.logistics.lanes.road-connections", false);

        assertEquals(new LaneSettings(true, 0, 1000, 24.5, false), LaneConfig.read(config));
    }
}
