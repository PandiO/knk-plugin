package net.knightsandkings.knk.paper.production.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.terminal.TerminalSettings;

/** production.storage.* (PandiO/knk-workspace#74): the bundled defaults, a missing block and the values read. */
class StorageTerminalConfigTest {

    @Test
    void theBundledConfig_hasTheFeatureOff_withTheDocumentedDefaults() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(StorageTerminalConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));

        TerminalSettings settings = StorageTerminalConfig.read(bundled);

        assertEquals(TerminalSettings.defaults(false), settings);
        assertEquals(5, settings.refreshMinutes());
        assertEquals(30, settings.displayRefreshSeconds());
    }

    @Test
    void aServerWithoutTheBlock_usesTheDefaults_andIsOffWithoutTheSwitch() {
        assertEquals(TerminalSettings.defaults(false), StorageTerminalConfig.read(new YamlConfiguration()));
        assertEquals(TerminalSettings.defaults(false), StorageTerminalConfig.read(null));
        YamlConfiguration on = new YamlConfiguration();
        on.set("production.enabled", true);
        assertEquals(TerminalSettings.defaults(true), StorageTerminalConfig.read(on));
    }

    @Test
    void theValuesAreRead_andClamped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("production.enabled", true);
        config.set("production.storage.refresh-minutes", 0);
        config.set("production.storage.display-refresh-seconds", 90);

        assertEquals(new TerminalSettings(true, 1, 90), StorageTerminalConfig.read(config));
    }
}
