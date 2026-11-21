package net.knightsandkings.knk.paper.production.harvest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;

/**
 * The harvest files in {@code plugins/<plugin>/production/harvest/} (PandiO/knk-workspace#134): {@code regen.txt} (the
 * regeneration jobs), {@code outbox.txt} (report entries without an answer) and {@code owed.txt} (worker's shares of
 * offline players). One line per record; written to a temporary file and moved into place, so a crash leaves the old
 * file or the new one, never half of it.
 */
final class HarvestStore {

    static final String REGEN = "regen.txt";
    static final String OUTBOX = "outbox.txt";
    static final String OWED = "owed.txt";

    private final Path dir;
    private final Logger logger;

    HarvestStore(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    Path dir() {
        return dir;
    }

    List<String> read(String name) {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Harvest: could not read " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    void write(String name, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            Path tmp = dir.resolve(name + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warning("Harvest: could not write " + dir.resolve(name) + ": " + e.getMessage());
        }
    }
}
