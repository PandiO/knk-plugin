package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioHost;
import net.knightsandkings.knk.core.scenario.ScenarioRegistry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaperScenariosTest {

    @Test
    void theShippedConfigKeepsScenariosOff() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml on the test classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            ScenarioSettings settings = ScenarioSettings.from(config);
            assertFalse(settings.enabled(), "testing.scenarios.enabled must ship as false");
            assertEquals(ScenarioSettings.DEFAULTS, settings);
        }
    }

    @Test
    void aConfigWithoutTheSectionIsOff() {
        assertEquals(ScenarioSettings.DEFAULTS, ScenarioSettings.from(new YamlConfiguration()));
    }

    @Test
    void settingsAreReadFromTheSection() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("testing.scenarios.enabled", true);
        config.set("testing.scenarios.world", "scenarios");
        config.set("testing.scenarios.origin.x", -5000);
        config.set("testing.scenarios.origin.y", 80);
        config.set("testing.scenarios.origin.z", 7000);

        assertEquals(new ScenarioSettings(true, "scenarios", -5000, 80, 7000), ScenarioSettings.from(config));
    }

    @Test
    void theRegistryHoldsTheShippedScenarios() {
        List<String> names = PaperScenarios.registry().all().stream().map(Scenario::name).toList();
        assertEquals(List.of("gate-open-close", "gate-closing-moves-entity-out", "global-region-explosion"), names);
    }

    @SuppressWarnings("unchecked")
    private static ScenarioCommand command() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        return new ScenarioCommand(plugin, PaperScenarios.registry(), mock(ScenarioHost.class));
    }

    @Test
    void tabCompletionOffersActionsThenScenarioNames() {
        ScenarioCommand command = command();
        CommandSender sender = mock(CommandSender.class);

        assertEquals(List.of("list", "run"), command.complete(sender, new String[] {""}));
        assertEquals(List.of("run"), command.complete(sender, new String[] {"r"}));
        assertEquals(List.of("gate-open-close", "gate-closing-moves-entity-out"),
            command.complete(sender, new String[] {"run", "gate"}));
        assertTrue(command.complete(sender, new String[] {"run", ""}).contains(ScenarioRegistry.ALL));
    }

    @Test
    void anUnknownScenarioIsRefusedWithoutStartingARun() {
        ScenarioCommand command = command();
        CommandSender sender = mock(CommandSender.class);

        assertTrue(command.execute(sender, new String[] {"run", "nope"}));
        verify(sender).sendMessage(contains("Unknown scenario 'nope'"));
    }

    @Test
    void listNamesEveryScenario() {
        ScenarioCommand command = command();
        CommandSender sender = mock(CommandSender.class);

        command.execute(sender, new String[] {"list"});
        verify(sender).sendMessage(contains("Scenarios (3)"));
        verify(sender).sendMessage(contains("global-region-explosion"));
    }
}
