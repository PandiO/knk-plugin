package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import net.knightsandkings.knk.paper.navigation.NavigationService;

/**
 * {@code /knk npc nav snapshot <route>} (link B1, PandiO/knk-workspace#670): the region files it picks from the world
 * folder, and the command's answers before it touches the world. The zip's contents are tested in knk-core
 * ({@code NavSnapshotZipTest}, {@code SnapshotBoxTest}, {@code SnapshotPartsTest}).
 */
class NavSnapshotTakerTest {

    @TempDir
    Path dir;

    private final List<String> said = new ArrayList<>();

    private static void touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
    }

    @Test
    void takesTheBlockRegionFilesWhereverTheWorldKeepsThemAndNotTheEntityOrPoiFiles() throws Exception {
        Path world = dir.resolve("world");
        touch(world.resolve("region/r.2.-2.mca"));
        touch(world.resolve("region/r.2.-1.mca"));
        touch(world.resolve("region/r.0.0.mca"));
        touch(world.resolve("entities/r.2.-2.mca"));
        touch(world.resolve("poi/r.2.-1.mca"));
        List<String> notes = new ArrayList<>();

        Map<String, Path> files = NavSnapshotTaker.regionFiles(world, List.of("r.2.-2.mca", "r.2.-1.mca"), notes);

        assertEquals(List.of("world/region/r.2.-1.mca", "world/region/r.2.-2.mca"), List.copyOf(files.keySet()));
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void the26LayoutWithDimensionFoldersIsFoundAndAMissingFileIsNoted() throws Exception {
        Path world = dir.resolve("world");
        touch(world.resolve("dimensions/minecraft/overworld/region/r.2.-2.mca"));
        List<String> notes = new ArrayList<>();

        Map<String, Path> files = NavSnapshotTaker.regionFiles(world, List.of("r.2.-2.mca", "r.2.-1.mca"), notes);

        assertEquals(List.of("world/dimensions/minecraft/overworld/region/r.2.-2.mca"), List.copyOf(files.keySet()));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("r.2.-1.mca not found"), notes.get(0));
    }

    @Test
    void noWorldFolderIsANoteNotAFailure() throws Exception {
        List<String> notes = new ArrayList<>();
        assertTrue(NavSnapshotTaker.regionFiles(dir.resolve("missing"), List.of("r.0.0.mca"), notes).isEmpty());
        assertEquals(1, notes.size());
    }

    private NpcNavigationModule module(boolean enabled, NavigationService roads) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        config.set("npc.navigation.enabled", enabled);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        return NpcNavigationModule.start(plugin, mock(KnkAdminCommand.class), null, null, () -> roads);
    }

    private CommandSender sender() {
        CommandSender sender = mock(CommandSender.class);
        org.mockito.Mockito.doAnswer(call -> said.add(call.getArgument(0))).when(sender).sendMessage(anyString());
        return sender;
    }

    @Test
    void refusedWhileNpcNavigationIsOff() throws Exception {
        NavigationService roads = mock(NavigationService.class);
        NpcNavigationModule module = module(false, roads);
        module.execute(sender(), new String[] {"nav", "snapshot", "quarry-to-keep"});
        assertEquals(List.of("NPC navigation unavailable: npc.navigation.enabled is false"), said);
        org.mockito.Mockito.verifyNoInteractions(roads);
    }

    @Test
    void asksForARouteAndNeedsASavedOneAndRoadNavigation() throws Exception {
        // a server without Citizens: the module starts without an engine, the snapshot still answers
        org.mockbukkit.mockbukkit.MockBukkit.mock();
        try {
            asksForARoute();
        } finally {
            org.mockbukkit.mockbukkit.MockBukkit.unmock();
        }
    }

    private void asksForARoute() throws Exception {
        NpcNavigationModule withRoads = module(true, mock(NavigationService.class));
        CommandSender sender = sender();
        withRoads.execute(sender, new String[] {"nav", "snapshot"});
        assertTrue(said.get(0).startsWith("/knk npc nav snapshot <route>"), said.toString());
        said.clear();
        withRoads.execute(sender, new String[] {"nav", "snapshot", "nowhere"});
        assertEquals(List.of("No usable route nowhere. /knk npc nav route list"), said);

        said.clear();
        module(true, null).execute(sender, new String[] {"nav", "snapshot", "quarry-to-keep"});
        assertTrue(said.get(0).startsWith("Road navigation is not running"), said.toString());
        assertTrue(NpcNavigationModule.NAV_USAGE.contains("snapshot <route>"));
    }
}
