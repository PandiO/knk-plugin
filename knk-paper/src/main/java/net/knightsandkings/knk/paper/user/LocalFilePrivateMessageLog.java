package net.knightsandkings.knk.paper.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin-local PM log (docs/specs/private-messages/DESIGN.md §3.3.8, developer decision
 * 2026-09-26): one file per day, {@code <dir>/private-messages-YYYY-MM-DD.log}, one line per
 * message:
 * <pre>2026-09-26T21:04:05+02:00 | DELIVERED | Alice(uuid) -> Bob(uuid) | hi</pre>
 * All file I/O runs on one daemon thread, so the main thread never waits on the disk and lines
 * stay in order. Files older than {@code retentionDays} are deleted on {@link #start()} and daily.
 */
public final class LocalFilePrivateMessageLog implements PrivateMessageLogger {

    private static final Logger LOGGER = Logger.getLogger(LocalFilePrivateMessageLog.class.getName());

    static final String FILE_PREFIX = "private-messages-";
    static final String FILE_SUFFIX = ".log";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path directory;
    private final int retentionDays;
    private final Clock clock;
    private final ZoneId zone;
    private final ScheduledExecutorService executor;

    /** {@code clock}'s zone decides the file dates and timestamps. */
    public LocalFilePrivateMessageLog(Path directory, int retentionDays, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be at least 1 (got " + retentionDays + ")");
        }
        this.retentionDays = retentionDays;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.zone = clock.getZone();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KnK-PrivateMessageLog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Sweeps old files now and then once a day. */
    public void start() {
        executor.scheduleAtFixedRate(this::sweepQuietly, 0, 1, TimeUnit.DAYS);
    }

    @Override
    public void log(Entry entry) {
        try {
            executor.execute(() -> append(entry));
        } catch (RejectedExecutionException ignored) {
            // Shutting down - the message was delivered, only its log line is lost.
        }
    }

    /** Waits until everything submitted so far is written (tests, shutdown). */
    void flush() throws InterruptedException, ExecutionException, TimeoutException {
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warning("Private message log did not finish writing within 5s; some lines may be lost.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    Path fileFor(LocalDate date) {
        return directory.resolve(FILE_PREFIX + date + FILE_SUFFIX);
    }

    static String formatLine(Entry entry, ZoneId zone) {
        return TIMESTAMP.format(entry.sentAt().atZone(zone).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime())
                + " | " + entry.outcome()
                + " | " + party(entry.senderName(), entry.senderUuid())
                + " -> " + party(entry.recipientName(), entry.recipientUuid())
                + " | " + oneLine(entry.text());
    }

    /**
     * Deletes log files dated more than {@code retentionDays} days before today.
     *
     * @return how many files were deleted
     */
    int sweep() throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        LocalDate oldestKept = LocalDate.now(clock.withZone(zone)).minusDays(retentionDays);
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                LocalDate date = dateOf(file.getFileName().toString());
                if (date != null && date.isBefore(oldestKept)) {
                    Files.deleteIfExists(file);
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private void sweepQuietly() {
        try {
            int deleted = sweep();
            if (deleted > 0) {
                LOGGER.info("Deleted " + deleted + " private message log file(s) older than " + retentionDays + " days");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Private message log retention sweep failed", e);
        }
    }

    /**
     * Account erasure (PandiO/knk-workspace#184): removes every line the player sent or received ({@code Name(uuid)} on
     * either side) from the log files, on the log's own thread so it can't interleave with a write. Waits for it.
     *
     * @return how many lines were removed
     */
    public int purge(UUID playerId) throws InterruptedException, ExecutionException, TimeoutException {
        if (playerId == null) {
            return 0;
        }
        return executor.submit(() -> purgeNow(playerId)).get(30, TimeUnit.SECONDS);
    }

    int purgeNow(UUID playerId) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        String marker = "(" + playerId + ")";
        int removed = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                java.util.List<String> kept = lines.stream().filter(line -> !mentions(line, marker)).toList();
                if (kept.size() == lines.size()) {
                    continue;
                }
                removed += lines.size() - kept.size();
                if (kept.isEmpty()) {
                    Files.deleteIfExists(file);
                } else {
                    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(temp, kept, StandardCharsets.UTF_8);
                    Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return removed;
    }

    /** The sender or recipient part of a line ({@code time | outcome | sender -> recipient | text}) names the player. */
    private static boolean mentions(String line, String marker) {
        String[] parts = line.split(" \\| ", 4);
        return parts.length >= 3 ? parts[2].contains(marker) : line.contains(marker);
    }

    private void append(Entry entry) {
        try {
            Files.createDirectories(directory);
            Files.writeString(fileFor(entry.sentAt().atZone(zone).toLocalDate()), formatLine(entry, zone) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write private message log line", e);
        }
    }

    private static LocalDate dateOf(String fileName) {
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) {
            return null;
        }
        String date = fileName.substring(FILE_PREFIX.length(), fileName.length() - FILE_SUFFIX.length());
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String party(String name, UUID uuid) {
        return uuid == null ? name : name + "(" + uuid + ")";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
    }
}
