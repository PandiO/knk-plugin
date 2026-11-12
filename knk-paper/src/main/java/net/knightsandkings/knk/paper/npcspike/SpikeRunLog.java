package net.knightsandkings.knk.paper.npcspike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * #243 spike (throwaway, never merged): the spike's own log files, so test results don't have to be copied from the
 * console. {@code plugins/<plugin>/npcspike-logs/}:
 * <ul>
 *   <li>one file per run, {@code <date>_<time>_<name>.log}: every {@code [NpcSpike]} line, the npcspike and gate
 *       commands issued, a status line every minute, and the full {@code stats} output;</li>
 *   <li>{@code results.csv}: one row per labelled {@code stats}, across all runs (header written once).</li>
 * </ul>
 * A run starts on a spawn with no spike NPCs present, or with {@code /knk npcspike run <name>}, and ends on
 * {@code clear}. Lines outside a run go to a {@code session} file. Main thread; small synchronous appends.
 */
final class SpikeRunLog {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final Plugin plugin;
    private final Logger logger;
    private final Path folder;
    private Path current;
    private String runName;
    private boolean failed;

    SpikeRunLog(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.folder = plugin.getDataFolder().toPath().resolve("npcspike-logs");
    }

    /** The current run's file name, or null outside a run. */
    String runFile() {
        return current == null ? null : current.getFileName().toString();
    }

    String runName() {
        return runName;
    }

    /** Ends the current run (if any) and starts a new file named after {@code name}. */
    void startRun(String name, List<String> settings) {
        endRun("new run started");
        runName = name.replaceAll("[^A-Za-z0-9_.-]", "-");
        current = folder.resolve(LocalDateTime.now().format(FILE_TIME) + "_" + runName + ".log");
        write(current, "# npcspike run " + runName + " started " + LocalDateTime.now());
        write(current, "# plugin " + plugin.getName() + " " + plugin.getDescription().getVersion()
            + " | server " + Bukkit.getVersion()
            + " | Citizens " + citizensVersion()
            + " | view distance " + Bukkit.getViewDistance() + ", simulation distance " + Bukkit.getSimulationDistance()
            + " | players online " + Bukkit.getOnlinePlayers().size());
        settings.forEach(line -> write(current, "# " + line));
        logger.info("[NpcSpike] run log: npcspike-logs/" + current.getFileName());
    }

    /** Ends the current run; later lines go to the session file until the next run. */
    void endRun(String why) {
        if (current != null && runName != null) {
            write(current, "# run " + runName + " ended (" + why + ") " + LocalDateTime.now());
        }
        current = null;
        runName = null;
    }

    /** A {@code [NpcSpike]} line: server log and the run file. */
    void log(String message) {
        logger.info("[NpcSpike] " + message);
        fileOnly(message);
    }

    /** A line for the run file only (status lines, issued commands): keeps the console quiet. */
    void fileOnly(String message) {
        write(target(), "[" + LocalDateTime.now().format(LINE_TIME) + "] " + message);
    }

    /** The full stats output, as shown in chat. */
    void report(String label, List<String> lines) {
        Path file = target();
        write(file, "[" + LocalDateTime.now().format(LINE_TIME) + "] ===== stats" + (label == null ? "" : " " + label)
            + " =====");
        lines.forEach(line -> write(file, "    " + line));
    }

    /** A CSV row: the run file and results.csv (with the header once, and the run's file name as the last column). */
    void csv(String header, String row) {
        log("CSV " + header);
        log("CSV " + row);
        Path results = folder.resolve("results.csv");
        if (!Files.exists(results)) {
            write(results, header + ",time,run_file");
        }
        write(results, row + "," + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + ","
            + (current == null ? "session" : current.getFileName()));
    }

    private Path target() {
        if (current == null) {
            return folder.resolve(LocalDateTime.now().toLocalDate() + "_session.log");
        }
        return current;
    }

    private void write(Path file, String line) {
        try {
            Files.createDirectories(folder);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!failed) {
                failed = true; // say it once, not for every line
                logger.log(Level.WARNING, "[NpcSpike] could not write " + file, e);
            }
        }
    }

    private static String citizensVersion() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        return citizens == null ? "absent" : citizens.getDescription().getVersion();
    }
}
