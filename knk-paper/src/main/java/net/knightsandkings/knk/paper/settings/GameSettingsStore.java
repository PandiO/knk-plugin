package net.knightsandkings.knk.paper.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;

/**
 * The last Game Settings read from the API, on disk (docs/specs/game-settings/DESIGN.md §3.7): the
 * plugin starts with them when the API is down at boot, instead of falling back to vanilla. Each
 * version that replaces another is also kept under {@code game-settings-backups/}, newest
 * {@code historyLimit} only - a local trail of what the Game Settings page changed.
 */
public class GameSettingsStore {

    private static final Logger LOGGER = Logger.getLogger(GameSettingsStore.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    static final String CACHE_FILE = "game-settings-cache.json";
    static final String HISTORY_DIR = "game-settings-backups";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path cacheFile;
    private final Path historyDir;
    private final int historyLimit;

    public GameSettingsStore(Path dataFolder, int historyLimit) {
        this.cacheFile = dataFolder.resolve(CACHE_FILE);
        this.historyDir = dataFolder.resolve(HISTORY_DIR);
        this.historyLimit = Math.max(0, historyLimit);
    }

    /** The cached settings; empty when there is no (readable) file. */
    public Optional<KnkGameSettings> load() {
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(gson.fromJson(Files.readString(cacheFile, StandardCharsets.UTF_8), KnkGameSettings.class));
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK GameSettings] Could not read " + cacheFile + "; starting without cached settings", ex);
            return Optional.empty();
        }
    }

    /**
     * Writes {@code settings} as the cache; the version it replaces goes to the history first.
     * Called only when the settings changed.
     */
    public synchronized void save(KnkGameSettings settings) {
        if (settings == null) {
            return;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            if (historyLimit > 0 && Files.isRegularFile(cacheFile)) {
                Files.createDirectories(historyDir);
                Files.copy(cacheFile, historyDir.resolve("game-settings-" + LocalDateTime.now().format(STAMP) + ".json"),
                    StandardCopyOption.REPLACE_EXISTING);
                prune();
            }
            Path tmp = cacheFile.resolveSibling(CACHE_FILE + ".tmp");
            Files.writeString(tmp, gson.toJson(settings), StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK GameSettings] Could not write " + cacheFile, ex);
        }
    }

    private void prune() throws IOException {
        try (Stream<Path> files = Files.list(historyDir)) {
            List<Path> history = files
                .filter(p -> p.getFileName().toString().startsWith("game-settings-") && p.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
            for (int i = historyLimit; i < history.size(); i++) {
                Files.deleteIfExists(history.get(i));
            }
        }
    }
}
