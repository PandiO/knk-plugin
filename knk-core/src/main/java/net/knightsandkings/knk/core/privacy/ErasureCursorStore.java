package net.knightsandkings.knk.core.privacy;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The highest erasure id this game server has handled (PandiO/knk-workspace#184, DESIGN §7.4): {@code lastId} on disk
 * ({@code plugins/KnightsAndKings/erasure-feed.json}, {@code {"version":1,"lastId":12}}), so a restart catches up from
 * where it stopped. Holds no personal data. A missing or unreadable file starts at 0 (the whole feed is applied again;
 * every step is idempotent). Thread-safe; writes are atomic (temp file + move).
 */
public final class ErasureCursorStore {

    static final int FORMAT_VERSION = 1;

    private record CursorFile(int version, long lastId) {
    }

    private final Path file;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private long lastId;

    public ErasureCursorStore(Path file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.lastId = read();
    }

    public synchronized long lastId() {
        return lastId;
    }

    /** Moves the position forward to {@code erasureId} (never back) and writes it. */
    public synchronized void advanceTo(long erasureId) {
        if (erasureId <= lastId) {
            return;
        }
        lastId = erasureId;
        write();
    }

    /** Back to 0: the API's feed was reset (another database), so its ids start again. */
    public synchronized void reset() {
        lastId = 0;
        write();
    }

    private long read() {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            CursorFile stored = mapper.readValue(file.toFile(), CursorFile.class);
            return Math.max(0, stored.lastId());
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "[KnK Privacy] Unreadable erasure position " + file.getFileName()
                + "; catching up from the start of the feed", e);
            return 0;
        }
    }

    private void write() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(new CursorFile(FORMAT_VERSION, lastId)));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // The next catch-up starts from the older position and applies the same erasures again (idempotent).
            logger.log(Level.WARNING, "[KnK Privacy] Could not write the erasure position " + file.getFileName(), e);
        }
    }
}
