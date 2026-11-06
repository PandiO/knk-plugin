package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.targeting.TargetKind;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurvivalSettingsTest {

    @Test
    void bundledConfigLoadsTheDefaults() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
            SurvivalSettingsTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        SurvivalSettings settings = SurvivalSettings.fromConfig(config);
        assertEquals(SurvivalSettings.defaults(), settings);
    }

    @Test
    void overridesAreReadAndClamped() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
            siege:
              survival:
                behaviour-ticks: 0
                breach-hit-ticks: 40
                follow-range: 128
                targeting:
                  preference:
                    gate: 0.5
                  aggro-radius: 20
                  min-commitment-millis: 1000
            """);
        SurvivalSettings s = SurvivalSettings.fromConfig(config);
        assertEquals(1, s.behaviourTicks(), "clamped to at least 1");
        assertEquals(40, s.breachHitTicks());
        assertEquals(128, s.followRange());
        assertEquals(0.5, s.targeting().preference(TargetKind.GATE));
        assertEquals(1.0, s.targeting().preference(TargetKind.PLAYER));
        assertEquals(20, s.targeting().aggroRadius());
        assertEquals(1000, s.targeting().minCommitmentMillis());
    }

    @Test
    void theDifficultyOverridesAggroAndLeashWhenSet() {
        SurvivalSettings s = SurvivalSettings.defaults();
        SurvivalConfiguration.DifficultySpec none = SurvivalConfigurationMapper.difficulty(null);
        assertEquals(s.targeting().aggroRadius(), s.policyFor(none).aggroRadius());
        SurvivalConfiguration.DifficultySpec set = new SurvivalConfiguration.DifficultySpec(1, "x", none.scaling(), 50, 1, 1, 500,
            OptionalDouble.of(7), OptionalDouble.of(50));
        assertEquals(7, s.policyFor(set).aggroRadius());
        assertEquals(50, s.policyFor(set).leashDistance());
    }
}
