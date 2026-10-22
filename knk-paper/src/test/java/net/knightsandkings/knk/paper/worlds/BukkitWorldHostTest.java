package net.knightsandkings.knk.paper.worlds;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * KNG-182: Paper 26.1+ keeps every world under the main world's {@code dimensions/<namespace>/<key>} and deletes the
 * old folder once it has imported it, so a configured world is found in either layout.
 */
class BukkitWorldHostTest {

    @TempDir
    Path container;

    private Path level;
    private BukkitWorldHost host;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        level = container.resolve("world_KNK-DEV");
        Server server = mock(Server.class);
        when(server.getWorldContainer()).thenReturn(container.toFile());
        when(server.getLevelDirectory()).thenReturn(level);
        UnsafeValues unsafe = mock(UnsafeValues.class);
        when(unsafe.getMainLevelName()).thenReturn("world_KNK-DEV");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
        host = new BukkitWorldHost(server);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void aPre26WorldFolderInTheContainerCounts() throws IOException {
        Files.createDirectories(container.resolve("hub"));
        Files.createFile(container.resolve("hub").resolve("level.dat"));

        assertTrue(host.folderExists("hub"));
    }

    @Test
    void anImportedWorldIsFoundUnderTheLevelsDimensions() throws IOException {
        Files.createDirectories(level.resolve("dimensions/minecraft/hub/region"));

        assertTrue(host.folderExists("hub"), "after Paper's import the old hub/ folder is gone");
    }

    @Test
    void theDimensionFolderUsesTheKeyPaperDerivesFromTheName() throws IOException {
        Files.createDirectories(level.resolve("dimensions/minecraft/siege_arena"));

        assertTrue(host.folderExists("Siege Arena"));
    }

    @Test
    void noFolderInEitherLayoutIsMissing() throws IOException {
        Files.createDirectories(container.resolve("arena"));
        Files.createDirectories(level.resolve("dimensions/minecraft/hub"));

        assertFalse(host.folderExists("arena"), "a folder without level.dat is not a world");
        assertFalse(host.folderExists("lobby"));
    }
}
