package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.navigation.NavigationService;

/**
 * PandiO/knk-workspace#360: with {@code npc.navigation.enabled: false} (the shipped default) nothing of NPC
 * navigation runs. This test has no Bukkit server: any scheduler task, listener registration, Citizens call or
 * world query would fail it, and road navigation is never asked for.
 */
class NpcNavigationModuleDisabledTest {

    @TempDir
    Path dataFolder;

    private final List<String> said = new ArrayList<>();

    private final NavigationService roadNavigation = mock(NavigationService.class);

    private NpcNavigationModule startWithShippedConfig(List<String> navigationAsked) throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            shipped.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(shipped);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        return NpcNavigationModule.start(plugin, mock(KnkAdminCommand.class), null, null, () -> {
            navigationAsked.add("asked");
            return roadNavigation;
        });
    }

    private CommandSender sender() {
        CommandSender sender = mock(CommandSender.class);
        org.mockito.Mockito.doAnswer(call -> said.add(call.getArgument(0))).when(sender).sendMessage(anyString());
        return sender;
    }

    @Test
    void nothingRunsWhileTheFlagIsOff() throws Exception {
        List<String> navigationAsked = new ArrayList<>();
        NpcNavigationModule module = startWithShippedConfig(navigationAsked);
        CommandSender sender = sender();

        assertTrue(module.status(sender).get(0).startsWith("NPC navigation: unavailable (npc.navigation.enabled is false)"));
        assertFalse(module.metrics().isEnabled(), "no instrument counts");

        for (String action : NpcNavigationModule.NAV_ACTIONS) {
            said.clear();
            assertTrue(module.execute(sender, new String[] {"nav", action, "50"}));
            assertEquals(List.of("NPC navigation unavailable: npc.navigation.enabled is false"), said, action);
        }
        said.clear();
        module.execute(sender, new String[] {"test", "spawn", "5"});
        assertEquals(List.of("NPC navigation unavailable: npc.navigation.enabled is false"), said);

        assertTrue(navigationAsked.isEmpty(), "road navigation is not touched");
        // PandiO/knk-workspace#361: no rule-change listener, no access rules port, no rule checks
        org.mockito.Mockito.verifyNoInteractions(roadNavigation);
        assertEquals(0, module.metrics().total(net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.RULE_CHECK));
        // PandiO/knk-workspace#377: "group" is one of the actions refused above; no group service, no group event
        assertTrue(NpcNavigationModule.NAV_ACTIONS.contains("group"));
        for (String event : List.of("GROUP_FOLD", "GROUP_REFORM", "GROUP_REJOIN", "GROUP_REGROUP", "GROUP_LOST")) {
            assertEquals(0, module.metrics().total(
                net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.valueOf(event)), event);
        }
        said.clear();
        assertTrue(module.execute(sender, new String[] {"nav", "group", "50", "escort-ring"}));
        assertEquals(List.of("NPC navigation unavailable: npc.navigation.enabled is false"), said);
        assertEquals(List.of("column", "ranks", "cluster", "escort-ring"),
            module.complete(sender, new String[] {"nav", "group", "50", ""}), "completion needs nothing running either");
        assertTrue(module.status(sender).stream().noneMatch(line -> line.contains("groups")), "no route NPC line");
        assertFalse(new File(dataFolder.toFile(), NpcRouteStore.FILE).exists(), "no files");
        assertFalse(new File(dataFolder.toFile(), "npc-logs").exists());
        module.stop(); // and stopping it needs no server either
        org.mockito.Mockito.verifyNoInteractions(roadNavigation);
    }

    @Test
    void theCommandIsStillRegisteredSoStatusCanSayWhy() throws Exception {
        YamlConfiguration empty = new YamlConfiguration();
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(empty);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        KnkAdminCommand knk = mock(KnkAdminCommand.class);

        NpcNavigationModule.start(plugin, knk, null, null);

        ArgumentCaptor<net.knightsandkings.knk.paper.commands.CommandMetadata> metadata =
            ArgumentCaptor.forClass(net.knightsandkings.knk.paper.commands.CommandMetadata.class);
        verify(knk, atLeastOnce()).registerSubcommand(metadata.capture(), any(), any());
        assertEquals("npc", metadata.getValue().name());
        assertTrue(metadata.getValue().usage().contains("nav"));
    }
}
