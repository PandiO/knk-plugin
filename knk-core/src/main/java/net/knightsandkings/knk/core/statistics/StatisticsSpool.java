package net.knightsandkings.knk.core.statistics;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

/**
 * Statistics batches the API couldn't take yet (IMPLEMENTATION_PLAN.md §5.2): one JSON file per
 * batch, {@code <directory>/<batchId>.json} (the plugin uses {@code plugins/KnightsAndKings/statistics-spool/}),
 * written atomically (temp file + move, like the discovery and siege spools), {@code "version": 1}.
 * The batch id travels with the file, so a replay of a batch the API already ingested is answered as
 * a duplicate and applies nothing. Thread-safe (every method is synchronized); no Bukkit types.
 */
public final class StatisticsSpool {

    static final int FILE_VERSION = 1;

    // ---- file format: strings for instants and enums, so it doesn't depend on Jackson modules ----

    record SessionFile(String type, String sessionKey, int userId, String at, String endReason) {}

    record DurationFile(String sessionKey, int userId, String metric, String from, String to) {}

    record ValueFile(int userId, String metric, String context, double value, String occurredAt) {}

    record PvpKillFile(int killerUserId, int victimUserId, String context, String occurredAt) {}

    record BatchFile(int version, String batchId, String sentAt, List<SessionFile> sessions, List<DurationFile> durations,
                     List<ValueFile> counters, List<ValueFile> records, List<PvpKillFile> pvpKills) {}

    private final Path directory;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public StatisticsSpool(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path directory() {
        return directory;
    }

    /** Writes the batch to its own file. False when it couldn't be written (the batch is then lost). */
    public synchronized boolean write(StatisticsBatch batch) {
        Objects.requireNonNull(batch, "batch");
        try {
            Files.createDirectories(directory);
            Path target = file(batch.batchId());
            Path temp = directory.resolve(batch.batchId() + ".json.tmp");
            Files.write(temp, mapper.writeValueAsBytes(toFile(batch)));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "[Statistics] Could not spool batch " + batch.batchId() + " (" + batch.entryCount()
                    + " entries) to " + directory + " - these statistics are lost", e);
            return false;
        }
    }

    /** Every readable spooled batch, oldest {@code sentAt} first. Unreadable files are logged and left in place. */
    public synchronized List<StatisticsBatch> list() {
        List<StatisticsBatch> result = new ArrayList<>();
        for (Path path : files()) {
            try {
                result.add(fromFile(mapper.readValue(path.toFile(), BatchFile.class)));
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "[Statistics] Skipping unreadable spool file " + path, e);
            }
        }
        result.sort(Comparator.comparing(StatisticsBatch::sentAt).thenComparing(b -> b.batchId().toString()));
        return result;
    }

    public synchronized void delete(UUID batchId) {
        try {
            Files.deleteIfExists(file(batchId));
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Statistics] Could not delete spooled batch " + batchId, e);
        }
    }

    public synchronized boolean contains(UUID batchId) {
        return Files.isRegularFile(file(batchId));
    }

    /** Spool files on disk (readable or not). */
    public synchronized int count() {
        return files().size();
    }

    public synchronized boolean isEmpty() {
        return files().isEmpty();
    }

    private List<Path> files() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Statistics] Could not list the spooled batches in " + directory, e);
            return List.of();
        }
    }

    private Path file(UUID batchId) {
        return directory.resolve(batchId + ".json");
    }

    // ---- mapping ----

    static BatchFile toFile(StatisticsBatch batch) {
        return new BatchFile(FILE_VERSION, batch.batchId().toString(), batch.sentAt().toString(),
                batch.sessions().stream().map(s -> new SessionFile(s.type().apiName(), s.sessionKey().toString(), s.userId(),
                        s.at().toString(), s.endReason() == null ? null : s.endReason().name())).toList(),
                batch.durations().stream().map(d -> new DurationFile(d.sessionKey().toString(), d.userId(), d.metric(),
                        d.from().toString(), d.to().toString())).toList(),
                batch.counters().stream().map(StatisticsSpool::valueFile).toList(),
                batch.records().stream().map(StatisticsSpool::valueFile).toList(),
                batch.pvpKills().stream().map(k -> new PvpKillFile(k.killerUserId(), k.victimUserId(), k.context(),
                        k.occurredAt().toString())).toList());
    }

    static StatisticsBatch fromFile(BatchFile file) {
        if (file == null || file.batchId() == null || file.sentAt() == null) {
            throw new IllegalArgumentException("incomplete statistics spool file");
        }
        if (file.version() > FILE_VERSION) {
            throw new IllegalArgumentException("statistics spool file version " + file.version() + " is newer than " + FILE_VERSION);
        }
        return new StatisticsBatch(UUID.fromString(file.batchId()), Instant.parse(file.sentAt()),
                file.sessions() == null ? List.of() : file.sessions().stream().map(s -> new StatisticsBatch.SessionEntry(
                        StatisticsBatch.SessionType.fromApiName(s.type()), UUID.fromString(s.sessionKey()), s.userId(),
                        Instant.parse(s.at()), s.endReason() == null ? null : StatisticsBatch.EndReason.valueOf(s.endReason()))).toList(),
                file.durations() == null ? List.of() : file.durations().stream().map(d -> new StatisticsBatch.DurationEntry(
                        UUID.fromString(d.sessionKey()), d.userId(), d.metric(), Instant.parse(d.from()), Instant.parse(d.to()))).toList(),
                file.counters() == null ? List.of() : file.counters().stream().map(StatisticsSpool::valueEntry).toList(),
                file.records() == null ? List.of() : file.records().stream().map(StatisticsSpool::valueEntry).toList(),
                file.pvpKills() == null ? List.of() : file.pvpKills().stream().map(k -> new StatisticsBatch.PvpKillEntry(
                        k.killerUserId(), k.victimUserId(), k.context(), Instant.parse(k.occurredAt()))).toList());
    }

    private static ValueFile valueFile(StatisticsBatch.ValueEntry v) {
        return new ValueFile(v.userId(), v.metric(), v.context(), v.value(), v.occurredAt().toString());
    }

    private static StatisticsBatch.ValueEntry valueEntry(ValueFile v) {
        return new StatisticsBatch.ValueEntry(v.userId(), v.metric(), v.context(), v.value(), Instant.parse(v.occurredAt()));
    }
}
