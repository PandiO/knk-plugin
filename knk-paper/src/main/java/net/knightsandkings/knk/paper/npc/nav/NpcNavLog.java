package net.knightsandkings.knk.paper.npc.nav;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * NPC navigation's run files in {@code plugins/<plugin>/npc-logs/} (next to the event audit's reports): one log per
 * server start with every stuck event and its position (BEHAVIOR_MODEL NAV-17: hard spots show up per place), the
 * stats printed by {@code /knk npc nav stats}, and per-NPC trace files. The first lines of a kind also go to the
 * console. Main thread; lines are short and appended as they come.
 */
public final class NpcNavLog {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Lines also printed to the console before the log goes file-only. */
    static final int CONSOLE_LINES = 20;

    private final Path folder;
    private final Logger logger;
    private Path file;
    private int consoleLines;
    private boolean failed;

    public NpcNavLog(Path dataFolder, Logger logger) {
        this.folder = dataFolder.resolve("npc-logs");
        this.logger = logger;
    }

    /** The log file's name, or null before the first line. */
    public String fileName() {
        return file == null ? null : file.getFileName().toString();
    }

    /** A line for the file; the first {@link #CONSOLE_LINES} since the last {@link #rearm} also on the console. */
    public void event(String line) {
        if (consoleLines++ < CONSOLE_LINES) {
            logger.info("[NPC] " + line);
        }
        fileOnly(line);
    }

    /** Lets the next {@link #CONSOLE_LINES} events through to the console again (a new run). */
    public void rearm() {
        consoleLines = 0;
    }

    public void fileOnly(String line) {
        if (file == null) {
            file = newFile("npc-nav");
        }
        append(file, LocalDateTime.now().format(LINE_TIME) + " " + line);
    }

    public void lines(List<String> lines) {
        lines.forEach(this::fileOnly);
    }

    /** A new file {@code <time>_<name>.log} in the folder (not created until the first append). */
    public Path newFile(String name) {
        return folder.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + name.replaceAll("[^A-Za-z0-9_-]", "-") + ".log");
    }

    public void append(Path target, String line) {
        if (failed) {
            return;
        }
        try {
            Files.createDirectories(folder);
            Files.writeString(target, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            failed = true; // say it once; navigation goes on without the file
            logger.warning("[NPC] could not write " + target + ": " + e.getMessage());
        }
    }
}
