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
        assertEquals(List.of("gate-open-close", "gate-closing-moves-entity-out", "global-region-explosion",
            "gate-closing-keeps-mobs-on-their-side", "gate-deep-door-moves-mob-out", "gate-closing-moves-item-out",
            "gate-closing-moves-vehicle-with-rider", "gate-side-by-side-doors", "gate-opening-leaves-neighbours",
            "gate-blocks-never-suffocate", "lootbox-area-rename-keeps-drawn-bounds", "lootbox-area-rerun-replaces-in-place",
            "temp-region-sweep-keeps-lootbox-area-region", "lootbox-spawns-on-ground-in-range", "lootbox-spawns-under-roof",
            "lootbox-switched-off-spawns-nothing", "npc-corners-line", "walk-trail-stairhouse", "walk-trail-cursor-stairhouse", "plaza-line-brink",
            "plaza-line-cinix-south-square", "plaza-line-cinix-northern-gate-square", "npc-corners-walk", "npc-stuck-ladder",
            "npc-group-narrows", "npc-group-gate-finish-trip", "npc-group-access-change", "npc-group-realtime-entry",
            "npc-exit-denied-leader", "npc-group-exit-denied", "npc-group-cache-refresh-held",
            "npc-ledge-corner",
            "npc-landing-ranks",
            "npc-walks-road-route", "npc-keeps-to-road-around-obstacle", "npc-group-keeps-spacing",
            "npc-stairhouse-singles", "npc-stairhouse-singles-collision-on", "npc-stairhouse-group",
            "npc-stairhouse-group-collision-on", "npc-stairhouse-climb", "npc-replay-quarry-to-keep",
            "npc-offroad-detour", "npc-offroad-upstairs", "npc-offroad-ravine", "npc-replay-keep-tower", "npc-ladder-hatch",
            "npc-road-goal-dead-end", "npc-replay-keep-hall", "npc-jagged-straight", "npc-jagged-straight-collision-on",
            "npc-doorway-crowd", "npc-doorway-crowd-collision-on", "npc-plaza-line", "npc-plaza-line-collision-on",
            "npc-collision-policy", "npc-guard-ledge-push", "npc-door-column",
            "player-command-needs-grant", "player-heal-needs-grant", "player-chat-group-colours", "player-join-loading-hold", "player-menu-click",
            "player-tp-into-closed-gate", "player-rejoin-in-closed-gate", "territory-border-crossing", "rural-structure-region",
            "production-area-survey", "storage-display-double-chest", "player-kit-overflow-inbox",
            "player-inbox-claim-partial"), names);
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
        assertEquals(List.of("lootbox-area-rename-keeps-drawn-bounds", "lootbox-area-rerun-replaces-in-place"),
            command.complete(sender, new String[] {"run", "lootbox-area"}));
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
        verify(sender).sendMessage(contains("Scenarios (71)"));
        verify(sender).sendMessage(contains("global-region-explosion"));
        verify(sender).sendMessage(contains("player-menu-click"));
    }
}
