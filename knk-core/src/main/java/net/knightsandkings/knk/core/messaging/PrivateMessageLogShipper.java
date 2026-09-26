package net.knightsandkings.knk.core.messaging;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi;

/**
 * Ships private messages to knk-web-api's server-side log in batches (KNG-18 Phase 3,
 * docs/specs/private-messages/DESIGN.md §3.3.8). Bukkit-free.
 * <ul>
 *   <li>{@link #submit} only queues (safe from the main thread). The queue is bounded
 *   ({@code capacity}, default 1000): when full the oldest entry is dropped with a WARN.</li>
 *   <li>One daemon thread sends: every {@code flushInterval}, or as soon as {@code batchSize}
 *   entries are waiting, until the queue is empty.</li>
 *   <li>A failed batch goes back to the front of the queue with the same client message ids
 *   (the API stores each id once) and sending backs off up to a minute. A batch the API refuses
 *   as malformed (400/404/413/422) is dropped, so one bad entry can't block the log.</li>
 *   <li>Spool: on {@link #close()} whatever is still queued is written to {@code spoolFile}
 *   (JSON lines); {@link #start()} reads it back, so an API outage across a restart loses
 *   nothing.</li>
 * </ul>
 */
public final class PrivateMessageLogShipper {

    private static final Logger LOGGER = Logger.getLogger(PrivateMessageLogShipper.class.getName());

    public static final int DEFAULT_CAPACITY = 1000;
    public static final int DEFAULT_BATCH_SIZE = 50;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    private static final long SEND_TIMEOUT_SECONDS = 30;
    private static final long CLOSE_SEND_TIMEOUT_SECONDS = 5;

    private final PrivateMessageLogApi api;
    private final Path spoolFile;
    private final int capacity;
    private final int batchSize;
    private final Duration flushInterval;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Guarded by itself. Oldest first. */
    private final Deque<PrivateMessageLogEntry> queue = new ArrayDeque<>();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;
    /** How long {@link #close()} waits for the sender thread; shorter in tests. */
    private Duration closeWait = Duration.ofSeconds(15);

    // Sender thread only.
    private int consecutiveFailures;
    private Instant nextAttempt = Instant.MIN;

    /**
     * @param spoolFile where unsent entries survive a restart; null = no spool
     */
    public PrivateMessageLogShipper(PrivateMessageLogApi api, Path spoolFile, int capacity, int batchSize,
                                    Duration flushInterval, Clock clock) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.spoolFile = spoolFile;
        if (capacity < 1 || batchSize < 1 || batchSize > PrivateMessageLogApi.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("capacity must be >= 1 and batchSize 1.." + PrivateMessageLogApi.MAX_BATCH_SIZE);
        }
        this.capacity = capacity;
        this.batchSize = batchSize;
        this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval must not be null");
        if (flushInterval.isZero() || flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KnK-PrivateMessageLogShipper");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Reads the spool back into the queue, then sends every {@code flushInterval}. */
    public void start() {
        executor.execute(this::loadSpool);
        long millis = flushInterval.toMillis();
        executor.scheduleWithFixedDelay(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Queues one entry; never blocks on the network. */
    public void submit(PrivateMessageLogEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        int size;
        synchronized (queue) {
            queue.addLast(entry);
            size = trimLocked();
        }
        if (size >= batchSize && !closed) {
            try {
                executor.execute(this::flushQuietly);
            } catch (RejectedExecutionException ignored) {
                // Closing - close() spools what is queued.
            }
        }
    }

    /** Entries waiting to be sent (shown by /knk health). */
    public int queueDepth() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** Entries dropped because the queue was full, since start. */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * Sends what it can within a few seconds (unless the API is already failing), then spools the
     * rest. Blocks for at most ~15 s.
     */
    public void close() {
        closed = true;
        try {
            executor.execute(() -> {
                // Spool first: if the final send hangs past the shutdown wait, nothing is lost. A
                // spooled entry that the send then delivers is re-sent next start and the API
                // counts it as a duplicate.
                writeSpool();
                if (consecutiveFailures == 0) {
                    flush(CLOSE_SEND_TIMEOUT_SECONDS);
                    writeSpool();
                }
            });
        } catch (RejectedExecutionException ignored) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(closeWait.toMillis(), TimeUnit.MILLISECONDS)) {
                // A send was still hanging (API unreachable, client pool busy), so the spool task
                // above never ran - shutdownNow() discards it. Interrupt the send (its batch goes
                // back to the queue) and write the spool from here instead.
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
                writeSpool();
                LOGGER.warning("Private message log shipper did not finish within " + closeWait.toSeconds()
                        + "s; unsent entries are in the spool file.");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            writeSpool();
            Thread.currentThread().interrupt();
        }
    }

    /** Tests: don't wait 15 s for a hanging send on close. */
    void closeWait(Duration wait) {
        this.closeWait = Objects.requireNonNull(wait, "wait must not be null");
    }

    /** Runs one send round on the sender thread and waits for it (tests). */
    void flushNow() throws InterruptedException, ExecutionException, TimeoutException {
        Future<?> round = executor.submit(this::flushQuietly);
        round.get(SEND_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
    }

    /** Waits until everything submitted to the sender thread so far has run (tests). */
    void awaitIdle() throws InterruptedException, ExecutionException, TimeoutException {
        executor.submit(() -> { }).get(SEND_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);
    }

    private void flushQuietly() {
        try {
            flush(SEND_TIMEOUT_SECONDS);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Private message log flush failed", e);
        }
    }

    /** Sends batches until the queue is empty or a send fails. Sender thread only. */
    private void flush(long timeoutSeconds) {
        if (clock.instant().isBefore(nextAttempt)) {
            return;
        }
        while (true) {
            List<PrivateMessageLogEntry> batch = new ArrayList<>(batchSize);
            synchronized (queue) {
                while (batch.size() < batchSize && !queue.isEmpty()) {
                    batch.add(queue.pollFirst());
                }
            }
            if (batch.isEmpty()) {
                return;
            }

            try {
                PrivateMessageLogApi.BatchResult result = api.submitBatch(batch).get(timeoutSeconds, TimeUnit.SECONDS);
                consecutiveFailures = 0;
                nextAttempt = Instant.MIN;
                LOGGER.fine(() -> "Private message log batch: " + result.accepted() + " accepted, "
                        + result.duplicates() + " duplicates");
            } catch (InterruptedException e) {
                requeue(batch);
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                ApiException refusal = apiException(e);
                if (refusal != null && isMalformed(refusal.getStatusCode())) {
                    LOGGER.warning("knk-web-api refused a private message log batch of " + batch.size()
                            + " as malformed [" + refusal.getStatusCode() + "]; dropped it: " + refusal.getResponseBody());
                    continue;
                }
                requeue(batch);
                consecutiveFailures++;
                Duration backoff = backoff(consecutiveFailures);
                nextAttempt = clock.instant().plus(backoff);
                LOGGER.warning("Could not send the private message log to knk-web-api (" + describe(e, refusal) + "); "
                        + queueDepth() + " queued, retrying in " + backoff.toSeconds() + "s");
                return;
            }
        }
    }

    Duration backoff(int failures) {
        Duration delay = flushInterval;
        for (int i = 1; i < failures && delay.compareTo(MAX_BACKOFF) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** 400/404/413/422: sending the same batch again can't succeed. 401/403 (wrong key) and 5xx can. */
    private static boolean isMalformed(int status) {
        return status == 400 || status == 404 || status == 413 || status == 422;
    }

    private static ApiException apiException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof ApiException api) {
                return api;
            }
        }
        return null;
    }

    private static String describe(Throwable failure, ApiException refusal) {
        if (refusal != null && refusal.getStatusCode() > 0) {
            return "HTTP " + refusal.getStatusCode();
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }

    private void requeue(List<PrivateMessageLogEntry> batch) {
        synchronized (queue) {
            for (int i = batch.size() - 1; i >= 0; i--) {
                queue.addFirst(batch.get(i));
            }
            trimLocked();
        }
    }

    /** Drops the oldest entries over capacity; returns the size. Caller holds the queue lock. */
    private int trimLocked() {
        while (queue.size() > capacity) {
            queue.pollFirst();
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                LOGGER.warning("Private message log queue is full (" + capacity + "); dropped the oldest entry ("
                        + total + " dropped so far). knk-web-api has been unreachable for a while.");
            }
        }
        return queue.size();
    }

    // ===== Spool =====

    /** One spooled entry, one JSON object per line. */
    record SpoolLine(String clientMessageId, String sentAt, String senderUuid, String senderName,
                     String recipientUuid, String recipientName, String content, String outcome, boolean viaReply) {

        static SpoolLine of(PrivateMessageLogEntry entry) {
            return new SpoolLine(entry.clientMessageId().toString(), entry.sentAt().toString(),
                    entry.senderUuid() == null ? null : entry.senderUuid().toString(), entry.senderName(),
                    entry.recipientUuid() == null ? null : entry.recipientUuid().toString(), entry.recipientName(),
                    entry.content(), entry.outcome().name(), entry.viaReply());
        }

        PrivateMessageLogEntry toEntry() {
            return new PrivateMessageLogEntry(UUID.fromString(clientMessageId), Instant.parse(sentAt),
                    senderUuid == null ? null : UUID.fromString(senderUuid), senderName,
                    recipientUuid == null ? null : UUID.fromString(recipientUuid), recipientName,
                    content, PrivateMessageLogEntry.Outcome.valueOf(outcome), viaReply);
        }
    }

    private void loadSpool() {
        if (spoolFile == null || !Files.isRegularFile(spoolFile)) {
            return;
        }
        List<PrivateMessageLogEntry> loaded = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(spoolFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    loaded.add(mapper.readValue(line, SpoolLine.class).toEntry());
                } catch (IOException | RuntimeException e) {
                    LOGGER.warning("Skipping an unreadable private message spool line: " + e.getMessage());
                }
            }
            Files.deleteIfExists(spoolFile);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read the private message log spool " + spoolFile, e);
            return;
        }
        synchronized (queue) {
            for (int i = loaded.size() - 1; i >= 0; i--) {
                queue.addFirst(loaded.get(i));
            }
            trimLocked();
        }
        if (!loaded.isEmpty()) {
            LOGGER.info("Re-queued " + loaded.size() + " private message(s) from the log spool");
        }
    }

    /** Replaces the spool with the current queue, or deletes it when the queue is empty. */
    private void writeSpool() {
        if (spoolFile == null) {
            return;
        }
        List<PrivateMessageLogEntry> snapshot;
        synchronized (queue) {
            snapshot = new ArrayList<>(queue);
        }
        try {
            if (snapshot.isEmpty()) {
                Files.deleteIfExists(spoolFile);
                return;
            }
            Path parent = spoolFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = spoolFile.resolveSibling(spoolFile.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                for (PrivateMessageLogEntry entry : snapshot) {
                    writer.write(mapper.writeValueAsString(SpoolLine.of(entry)));
                    writer.newLine();
                }
            }
            Files.move(temp, spoolFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not write the private message log spool " + spoolFile
                    + "; " + snapshot.size() + " unsent message(s) lost from the server-side log", e);
        }
    }
}
