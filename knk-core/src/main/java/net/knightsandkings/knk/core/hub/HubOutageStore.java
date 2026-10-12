package net.knightsandkings.knk.core.hub;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The hub's outage state on disk, so it holds through a restart without the API (hub DESIGN §12, §13): when the current
 * outage began (the delay timer) and each evacuated player's return record - where they were just before an outage
 * action moved or kicked them. A return record is never overwritten by a later evacuation, so it keeps the original
 * spot, and is removed only by a successful return, a voluntary gameplay entry, or when it can no longer be used.
 * <p>
 * Kept in its own file ({@code hub-outage.json}, next to the KNG-58 {@code offline-security.json}) with the same
 * write rules: temp file plus atomic move, a corrupt file is moved aside. Every change is written at once; the file is
 * small and changes only on outage transitions and evacuations. Thread-safe.
 */
public final class HubOutageStore {

    private static final Logger LOGGER = Logger.getLogger(HubOutageStore.class.getName());
    static final int FORMAT_VERSION = 1;

    /** Where a player was just before an outage action, and when it was saved (it identifies this record). */
    public record ReturnRecord(UUID player, String world, double x, double y, double z, float yaw, float pitch,
                               Instant savedAt) {
    }

    record ReturnFile(String player, String world, double x, double y, double z, float yaw, float pitch, String savedAt) {
    }

    record StoreFile(int version, String outageStartedAt, List<ReturnFile> returns) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<UUID, ReturnRecord> returns = new ConcurrentHashMap<>();
    private volatile Instant outageStartedAt;

    /** @param file the JSON file; null keeps everything in memory only (tests, a disabled store) */
    public HubOutageStore(Path file) {
        this.file = file;
        load();
    }

    public Optional<Instant> outageStartedAt() {
        return Optional.ofNullable(outageStartedAt);
    }

    /** Records (or with null clears) when the current outage began. */
    public synchronized void setOutageStartedAt(Instant startedAt) {
        outageStartedAt = startedAt;
        write();
    }

    public Optional<ReturnRecord> returnRecord(UUID player) {
        return Optional.ofNullable(player == null ? null : returns.get(player));
    }

    /** Saves a return record unless the player already has one (the original spot wins). Returns whether it was saved. */
    public synchronized boolean saveIfAbsent(ReturnRecord record) {
        if (record == null || returns.containsKey(record.player())) {
            return false;
        }
        returns.put(record.player(), record);
        write();
        return true;
    }

    /** Removes the player's record; returns it, if there was one. */
    public synchronized Optional<ReturnRecord> remove(UUID player) {
        ReturnRecord removed = player == null ? null : returns.remove(player);
        if (removed != null) {
            write();
        }
        return Optional.ofNullable(removed);
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            StoreFile stored = mapper.readValue(file.toFile(), StoreFile.class);
            outageStartedAt = stored.outageStartedAt() != null ? Instant.parse(stored.outageStartedAt()) : null;
            if (stored.returns() != null) {
                for (ReturnFile f : stored.returns()) {
                    UUID player = UUID.fromString(f.player());
                    returns.put(player, new ReturnRecord(player, f.world(), f.x(), f.y(), f.z(), f.yaw(), f.pitch(),
                        Instant.parse(f.savedAt())));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[KnK Hub] Could not read " + file + "; starting empty and keeping the file as .corrupt", e);
            outageStartedAt = null;
            returns.clear();
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                LOGGER.log(Level.WARNING, "[KnK Hub] Could not move " + file + " aside", moveError);
            }
        }
    }

    private void write() {
        if (file == null) {
            return;
        }
        List<ReturnFile> files = new ArrayList<>();
        for (ReturnRecord r : returns.values()) {
            files.add(new ReturnFile(r.player().toString(), r.world(), r.x(), r.y(), r.z(), r.yaw(), r.pitch(),
                r.savedAt().toString()));
        }
        Instant started = outageStartedAt;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(new StoreFile(FORMAT_VERSION,
                started != null ? started.toString() : null, files)));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[KnK Hub] Could not write " + file, e);
        }
    }
}
