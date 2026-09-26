package net.knightsandkings.knk.core.discovery;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;

/**
 * Discoveries the API couldn't be reached for (docs/specs/domain-discovery DESIGN.md §3.6, D9): one
 * JSON file per player, {@code <directory>/<uuid>.json} (the plugin uses
 * {@code plugins/KnightsAndKings/discovery-spool/}), written atomically (temp file + move, like the
 * siege result spool). Rewards are permanent one-offs, so a discovery made while the API is down is
 * kept and replayed rather than dropped; the server's idempotency makes a replay safe. Adding to a
 * player's file merges with what is already there (one entry per region id, the earliest sighting
 * kept). Thread-safe (every method is synchronized); no Bukkit types.
 */
public final class DiscoverySpool {

    /** One player's spooled discoveries. */
    public record Pending(UUID playerId, int userId, List<PendingDiscovery> entries) {
        public Pending {
            entries = List.copyOf(entries);
        }
    }

    // ---- file format (strings for enums and instants so it doesn't depend on Jackson modules) ----

    record EntryFile(String regionId, String source, String discoveredAt) {}

    record PendingFile(int version, String playerId, int userId, List<EntryFile> entries) {}

    private final Path directory;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DiscoverySpool(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path directory() {
        return directory;
    }

    /**
     * Adds these discoveries to the player's file (creating it). Returns false when it couldn't be
     * written - the discoveries are then lost until the player walks there again.
     */
    public synchronized boolean add(UUID playerId, int userId, Collection<PendingDiscovery> entries) {
        Objects.requireNonNull(playerId, "playerId");
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        Map<String, PendingDiscovery> merged = new LinkedHashMap<>();
        read(playerId).ifPresent(existing -> existing.entries().forEach(e -> merged.put(e.key(), e)));
        for (PendingDiscovery entry : entries) {
            merged.merge(entry.key(), entry, (old, added) -> old.discoveredAt().isAfter(added.discoveredAt()) ? added : old);
        }
        return write(new Pending(playerId, userId, new ArrayList<>(merged.values())));
    }

    /** The player's spooled discoveries, if any. */
    public synchronized Optional<Pending> get(UUID playerId) {
        return read(playerId);
    }

    /** Every readable file, oldest first discovery first. Unreadable files are logged and left in place. */
    public synchronized List<Pending> list() {
        List<Pending> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Discovery] Could not list the spooled discoveries in " + directory, e);
            return result;
        }
        for (Path path : files) {
            try {
                Pending pending = fromFile(mapper.readValue(path.toFile(), PendingFile.class));
                if (!pending.entries().isEmpty()) {
                    result.add(pending);
                }
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "[Discovery] Skipping unreadable spool file " + path, e);
            }
        }
        result.sort(Comparator.comparing(p -> p.entries().stream()
                .map(PendingDiscovery::discoveredAt).min(Comparator.naturalOrder()).orElse(Instant.EPOCH)));
        return result;
    }

    /** Removes these region ids from the player's file; deletes the file when nothing is left. */
    public synchronized void remove(UUID playerId, Collection<String> regionIds) {
        Optional<Pending> existing = read(playerId);
        if (existing.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        regionIds.forEach(id -> keys.add(PendingDiscovery.key(id)));
        List<PendingDiscovery> left = existing.get().entries().stream().filter(e -> !keys.contains(e.key())).toList();
        if (left.isEmpty()) {
            delete(playerId);
        } else {
            write(new Pending(playerId, existing.get().userId(), left));
        }
    }

    public synchronized void delete(UUID playerId) {
        try {
            Files.deleteIfExists(file(playerId));
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Discovery] Could not delete the spool file of " + playerId, e);
        }
    }

    public synchronized boolean isEmpty() {
        return list().isEmpty();
    }

    /** Spooled discoveries across all players. */
    public synchronized int entryCount() {
        return list().stream().mapToInt(p -> p.entries().size()).sum();
    }

    private Optional<Pending> read(UUID playerId) {
        Path path = file(playerId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromFile(mapper.readValue(path.toFile(), PendingFile.class)));
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "[Discovery] Unreadable spool file " + path + " - it is replaced by the next write", e);
            return Optional.empty();
        }
    }

    private boolean write(Pending pending) {
        try {
            Files.createDirectories(directory);
            Path target = file(pending.playerId());
            Path temp = directory.resolve(pending.playerId() + ".json.tmp");
            Files.write(temp, mapper.writeValueAsBytes(toFile(pending)));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "[Discovery] Could not spool " + pending.entries().size() + " discovery(ies) of "
                    + pending.playerId() + " to " + directory + " - they are lost until the player enters again", e);
            return false;
        }
    }

    private Path file(UUID playerId) {
        return directory.resolve(playerId + ".json");
    }

    // ---- mapping ----

    static PendingFile toFile(Pending pending) {
        return new PendingFile(1, pending.playerId().toString(), pending.userId(), pending.entries().stream()
                .map(e -> new EntryFile(e.regionId(), e.source().apiName(), e.discoveredAt().toString()))
                .toList());
    }

    static Pending fromFile(PendingFile file) {
        if (file == null || file.playerId() == null || file.userId() <= 0) {
            throw new IllegalArgumentException("incomplete spool file");
        }
        List<PendingDiscovery> entries = file.entries() == null ? List.of() : file.entries().stream()
                .filter(e -> e.regionId() != null && !e.regionId().isBlank())
                .map(e -> new PendingDiscovery(e.regionId(), DiscoverySource.fromApiName(e.source()),
                        e.discoveredAt() == null ? null : Instant.parse(e.discoveredAt())))
                .toList();
        return new Pending(UUID.fromString(file.playerId()), file.userId(), entries);
    }
}
