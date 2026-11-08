package net.knightsandkings.knk.core.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Minecraft's own files about a player (inventory and position, statistics, advancements), which the plugin does not
 * delete: whether they go is the owner's decision (#66 "Data erasure push", PandiO/knk-workspace#184). After an erasure
 * the files that exist are appended to a list file for the owner, one line per file:
 * <pre>erasure 12	2026-11-05T20:15:00Z	/srv/minecraft/world/players/data/&lt;uuid&gt;.dat</pre>
 * The server log only says how many there are and where the list is (no UUID in the log). Paths: since Minecraft 26.1
 * a player's files are under {@code <level directory>/players/data|stats|advancements}; the older
 * {@code <level directory>/playerdata|stats|advancements} folders are checked as well.
 */
public final class MinecraftPlayerFiles {

    private MinecraftPlayerFiles() {
    }

    /** Every place Minecraft (26.1 and before) keeps a file about {@code uuid}. */
    public static List<Path> candidates(Path levelDirectory, UUID uuid) {
        Objects.requireNonNull(levelDirectory, "levelDirectory");
        Objects.requireNonNull(uuid, "uuid");
        Path players = levelDirectory.resolve("players");
        return List.of(
            players.resolve("data").resolve(uuid + ".dat"),
            players.resolve("data").resolve(uuid + ".dat_old"),
            players.resolve("stats").resolve(uuid + ".json"),
            players.resolve("advancements").resolve(uuid + ".json"),
            levelDirectory.resolve("playerdata").resolve(uuid + ".dat"),
            levelDirectory.resolve("playerdata").resolve(uuid + ".dat_old"),
            levelDirectory.resolve("stats").resolve(uuid + ".json"),
            levelDirectory.resolve("advancements").resolve(uuid + ".json"));
    }

    /** The candidates that exist now. */
    public static List<Path> existing(Path levelDirectory, UUID uuid) {
        List<Path> found = new ArrayList<>();
        for (Path path : candidates(levelDirectory, uuid)) {
            if (Files.isRegularFile(path)) {
                found.add(path);
            }
        }
        return found;
    }

    /** Appends one line per file to {@code listFile} (created when missing); returns how many lines were written. */
    public static int appendToList(Path listFile, long erasureId, Instant at, List<Path> files) throws IOException {
        if (files.isEmpty()) {
            return 0;
        }
        Path parent = listFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder lines = new StringBuilder();
        for (Path file : files) {
            lines.append("erasure ").append(erasureId).append('\t').append(at).append('\t')
                .append(file.toAbsolutePath().normalize()).append(System.lineSeparator());
        }
        Files.writeString(listFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return files.size();
    }
}
