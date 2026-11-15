package net.knightsandkings.knk.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftPlayerFilesTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    @Test
    void findsThe26Point1AndTheOlderLayout_AndListsThemForTheOwner() throws Exception {
        Path level = dir.resolve("world");
        Path dat = touch(level.resolve("players/data/" + PLAYER + ".dat"));
        Path stats = touch(level.resolve("players/stats/" + PLAYER + ".json"));
        Path legacy = touch(level.resolve("playerdata/" + PLAYER + ".dat"));
        touch(level.resolve("players/data/" + UUID.randomUUID() + ".dat"));

        List<Path> found = MinecraftPlayerFiles.existing(level, PLAYER);

        assertEquals(List.of(dat, stats, legacy), found);
        Path list = dir.resolve("plugins/KnightsAndKings/erasure-minecraft-files.txt");
        assertEquals(3, MinecraftPlayerFiles.appendToList(list, 12, Instant.parse("2026-11-05T20:15:00Z"), found));
        assertEquals(0, MinecraftPlayerFiles.appendToList(list, 13, Instant.parse("2026-11-05T20:16:00Z"), List.of()));
        List<String> lines = Files.readAllLines(list);
        assertEquals(3, lines.size());
        assertEquals("erasure 12\t2026-11-05T20:15:00Z\t" + dat.toAbsolutePath().normalize(), lines.get(0));
        assertTrue(Files.exists(dat), "the plugin never deletes Minecraft's own files");
    }

    @Test
    void nothingThere_NothingListed() {
        assertTrue(MinecraftPlayerFiles.existing(dir.resolve("world"), PLAYER).isEmpty());
        assertEquals(8, MinecraftPlayerFiles.candidates(dir, PLAYER).size());
        assertFalse(MinecraftPlayerFiles.candidates(dir, PLAYER).stream().anyMatch(Files::exists));
    }

    private static Path touch(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "x");
        return path;
    }
}
