package net.knightsandkings.knk.core.siege;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ObjectiveResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Match results the API didn't accept in time (siege Phase 6): one JSON file per match,
 * {@code <directory>/<matchId>.json} (the plugin uses {@code siege-vault/pending-results/}), written
 * atomically (temp file + move, like {@code SiegePlayerVault}). {@link SiegeMatchRecorder} writes a
 * file when a {@code complete}/{@code abort} call fails transiently, and replays the files on the next
 * enable (and at the next draw); the server's idempotency makes a replay safe. A later write for the
 * same match replaces the earlier one. Thread-safe (every method is synchronized); no Bukkit types.
 */
public final class SiegeResultSpool {

    /** A spooled call: either a completion or an abort. */
    public sealed interface PendingResult permits PendingComplete, PendingAbort {
        long matchId();
    }

    public record PendingComplete(long matchId, Completion completion) implements PendingResult {
        public PendingComplete {
            Objects.requireNonNull(completion, "completion");
        }
    }

    public record PendingAbort(long matchId, SiegeEndReason reason) implements PendingResult {
        public PendingAbort {
            Objects.requireNonNull(reason, "reason");
        }
    }

    // ---- file format (flat, strings for enums and instants so it doesn't depend on Jackson modules) ----

    record ParticipantFile(int userId, int siegeTeamId, int kills, int deaths, int highestKillStreak, int captures) {}

    record ObjectiveFile(int objectiveId, int finalHolderTeamId, Integer capturedByUserId, String capturedAt) {}

    record PendingFile(
            int version,
            String type,
            long matchId,
            String endReason,
            Integer winningAllianceGroup,
            List<ParticipantFile> participants,
            List<ObjectiveFile> objectives,
            String spooledAt
    ) {}

    private static final String TYPE_COMPLETE = "complete";
    private static final String TYPE_ABORT = "abort";

    private final Path directory;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SiegeResultSpool(Path directory, Logger logger) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public Path directory() {
        return directory;
    }

    /** Writes (or replaces) the file for this match. Returns false when it couldn't be written. */
    public synchronized boolean save(PendingResult result) {
        Objects.requireNonNull(result, "result");
        try {
            Files.createDirectories(directory);
            Path target = file(result.matchId());
            Path temp = directory.resolve(result.matchId() + ".json.tmp");
            Files.write(temp, mapper.writeValueAsBytes(toFile(result)));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "[Siege] Could not spool the result of match " + result.matchId()
                    + " to " + directory + " - it is lost unless the API recorded it", e);
            return false;
        }
    }

    /** Every readable spooled result, oldest match id first. Unreadable files are logged and left in place. */
    public synchronized List<PendingResult> list() {
        List<PendingResult> results = new ArrayList<>();
        if (!Files.isDirectory(directory)) return results;
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Siege] Could not list the pending results in " + directory, e);
            return results;
        }
        for (Path path : files) {
            try {
                results.add(fromFile(mapper.readValue(path.toFile(), PendingFile.class)));
            } catch (IOException | RuntimeException e) {
                logger.log(Level.WARNING, "[Siege] Skipping unreadable pending result " + path, e);
            }
        }
        results.sort(Comparator.comparingLong(PendingResult::matchId));
        return results;
    }

    public synchronized boolean contains(long matchId) {
        return Files.isRegularFile(file(matchId));
    }

    public synchronized void delete(long matchId) {
        try {
            Files.deleteIfExists(file(matchId));
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Siege] Could not delete the pending result of match " + matchId, e);
        }
    }

    public synchronized boolean isEmpty() {
        return list().isEmpty();
    }

    private Path file(long matchId) {
        return directory.resolve(matchId + ".json");
    }

    // ---- mapping ----

    static PendingFile toFile(PendingResult result) {
        String now = Instant.now().toString();
        if (result instanceof PendingAbort abort) {
            return new PendingFile(1, TYPE_ABORT, abort.matchId(), abort.reason().name(), null, List.of(), List.of(), now);
        }
        PendingComplete complete = (PendingComplete) result;
        Completion c = complete.completion();
        return new PendingFile(1, TYPE_COMPLETE, complete.matchId(), c.endReason().name(), c.winningAllianceGroup(),
                c.participants().stream().map(p -> new ParticipantFile(p.userId(), p.siegeTeamId(), p.kills(), p.deaths(),
                        p.highestKillStreak(), p.captures())).toList(),
                c.objectives().stream().map(o -> new ObjectiveFile(o.objectiveId(), o.finalHolderTeamId(), o.capturedByUserId(),
                        o.capturedAt() == null ? null : o.capturedAt().toString())).toList(),
                now);
    }

    static PendingResult fromFile(PendingFile f) {
        if (f == null || f.type() == null || f.endReason() == null) {
            throw new IllegalArgumentException("incomplete pending result");
        }
        SiegeEndReason reason = SiegeEndReason.valueOf(f.endReason());
        if (TYPE_ABORT.equals(f.type())) {
            return new PendingAbort(f.matchId(), reason);
        }
        if (!TYPE_COMPLETE.equals(f.type())) {
            throw new IllegalArgumentException("unknown pending result type " + f.type());
        }
        List<ParticipantResult> participants = f.participants() == null ? List.of() : f.participants().stream()
                .map(p -> new ParticipantResult(p.userId(), p.siegeTeamId(), p.kills(), p.deaths(), p.highestKillStreak(), p.captures()))
                .toList();
        List<ObjectiveResult> objectives = f.objectives() == null ? List.of() : f.objectives().stream()
                .map(o -> new ObjectiveResult(o.objectiveId(), o.finalHolderTeamId(), o.capturedByUserId(),
                        o.capturedAt() == null ? null : Instant.parse(o.capturedAt())))
                .toList();
        return new PendingComplete(f.matchId(), new Completion(reason, f.winningAllianceGroup(), participants, objectives));
    }
}
