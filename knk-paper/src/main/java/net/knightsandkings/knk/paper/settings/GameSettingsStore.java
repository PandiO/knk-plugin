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
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;

/**
 * The last Game Settings read from the API, on disk (docs/specs/game-settings/DESIGN.md §3.7): the
 * plugin starts with them when the API is down at boot, instead of falling back to vanilla. Each
 * version that replaces another is also kept under {@code game-settings-backups/}, newest
 * {@code historyLimit} only - a local trail of what the Game Settings page changed.
 * <p>
 * Hub DESIGN §16.4 decision 7: the snapshot lives in the KNG-58 offline store ({@code offline-security.json}), with
 * the account and permission state, so settings and the hub survive an API outage and a restart together. The old
 * {@code game-settings-cache.json} is read once when the store has no snapshot yet, then moved to the history. With
 * {@code offline-cache.enabled: false} there is no store and the old file is used as before.
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
    private final OfflineSecurityStore offline;

    public GameSettingsStore(Path dataFolder, int historyLimit) {
        this(dataFolder, historyLimit, null);
    }

    /** @param offline the KNG-58 store that keeps the snapshot (decision 7); null = the old cache file */
    public GameSettingsStore(Path dataFolder, int historyLimit, OfflineSecurityStore offline) {
        this.cacheFile = dataFolder.resolve(CACHE_FILE);
        this.historyDir = dataFolder.resolve(HISTORY_DIR);
        this.historyLimit = Math.max(0, historyLimit);
        this.offline = offline;
    }

    /** The cached settings; empty when there is no (readable) snapshot. */
    public Optional<KnkGameSettings> load() {
        if (offline != null && offline.gameSettingsJson().isPresent()) {
            try {
                return Optional.ofNullable(gson.fromJson(offline.gameSettingsJson().get(), KnkGameSettings.class));
            } catch (RuntimeException ex) {
                LOGGER.log(Level.WARNING, "[KnK GameSettings] The offline store's game settings could not be read; "
                    + "trying " + cacheFile, ex);
            }
        }
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
        if (offline != null) {
            saveToOfflineStore(settings);
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

    private void saveToOfflineStore(KnkGameSettings settings) {
        String json = gson.toJson(settings);
        try {
            String previous = offline.gameSettingsJson().orElse(null);
            if (previous == null && Files.isRegularFile(cacheFile)) {
                previous = Files.readString(cacheFile, StandardCharsets.UTF_8);
            }
            if (historyLimit > 0 && previous != null && !previous.equals(json)) {
                Files.createDirectories(historyDir);
                Files.writeString(historyDir.resolve("game-settings-" + LocalDateTime.now().format(STAMP) + ".json"),
                    previous, StandardCharsets.UTF_8);
                prune();
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK GameSettings] Could not keep the previous game settings in " + historyDir, ex);
        }
        offline.recordGameSettings(json);
        if (!offline.flushIfDirty()) {
            return; // keep the old file until the store holds the snapshot on disk
        }
        try {
            // The snapshot now lives in the offline store (it was copied to the history above).
            Files.deleteIfExists(cacheFile);
        } catch (IOException ex) {
            LOGGER.log(Level.FINE, "[KnK GameSettings] Could not remove the old " + cacheFile, ex);
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
