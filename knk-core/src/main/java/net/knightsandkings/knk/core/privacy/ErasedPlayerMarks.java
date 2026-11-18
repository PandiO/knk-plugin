package net.knightsandkings.knk.core.privacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minecraft accounts whose knk account was erased and that have not joined since (PandiO/knk-workspace#486). An
 * erasure keeps Minecraft's own player files by design ({@code erasure-minecraft-files.txt}), so Bukkit's
 * {@code hasPlayedBefore()} stays true for that player; and an account they get back through an invitation claim or
 * the website exists before their join, so it isn't "created at this join" either. This mark is what makes their next
 * join a first join (first-join welcome, quiet discovery minutes) whichever way the erasure arrived: the push, or the
 * catch-up after a restart.
 * <p>
 * On disk ({@code plugins/KnightsAndKings/erasure-first-joins.json}) so it survives the restart the catch-up follows.
 * It holds no UUID or name: each entry is the SHA-256 of the UUID with the time it was marked, and goes at that
 * player's next join or after {@link #MAX_AGE}. Thread-safe; writes are atomic (temp file + move).
 */
public final class ErasedPlayerMarks {

    /** How long a mark waits for the player's next join. */
    public static final Duration MAX_AGE = Duration.ofDays(90);
    static final int FORMAT_VERSION = 1;

    /** {@code marks}: SHA-256 of the UUID → when it was marked, in epoch milliseconds. */
    private record MarksFile(int version, Map<String, Long> marks) {
    }

    private final Path file;
    private final Clock clock;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, Instant> marks = new LinkedHashMap<>();

    public ErasedPlayerMarks(Path file, Clock clock, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.logger = Objects.requireNonNull(logger, "logger");
        read();
    }

    /** The account of {@code uuid} was erased: its next join is a first join. Returns 1 (a count for the erasure log). */
    public synchronized int mark(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        prune();
        marks.put(key(uuid), clock.instant());
        write();
        return 1;
    }

    /** Whether {@code uuid} is marked (and the mark is not too old). */
    public synchronized boolean isMarked(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Instant at = marks.get(key(uuid));
        return at != null && !expired(at);
    }

    /** The join that uses the mark: true once, and the mark is gone. */
    public synchronized boolean take(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Instant at = marks.remove(key(uuid));
        if (at == null) {
            return false;
        }
        prune();
        write();
        return !expired(at);
    }

    public synchronized int size() {
        return marks.size();
    }

    static String key(UUID uuid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(uuid.toString().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private boolean expired(Instant at) {
        return at.plus(MAX_AGE).isBefore(clock.instant());
    }

    private void prune() {
        marks.values().removeIf(this::expired);
    }

    private Map<String, Long> millis() {
        Map<String, Long> out = new LinkedHashMap<>();
        marks.forEach((k, v) -> out.put(k, v.toEpochMilli()));
        return out;
    }

    private void read() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            MarksFile stored = mapper.readValue(file.toFile(), MarksFile.class);
            if (stored.marks() != null) {
                stored.marks().forEach((k, v) -> {
                    if (k != null && v != null) {
                        marks.put(k, Instant.ofEpochMilli(v));
                    }
                });
            }
            prune();
        } catch (IOException | RuntimeException e) {
            // Erased players then get "Welcome back" once; nothing else depends on this file.
            logger.log(Level.WARNING, "[KnK Privacy] Unreadable " + file.getFileName()
                + "; erased players' next join is not treated as a first join", e);
        }
    }

    private void write() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(new MarksFile(FORMAT_VERSION, millis())));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "[KnK Privacy] Could not write " + file.getFileName(), e);
        }
    }
}
