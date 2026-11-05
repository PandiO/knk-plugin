package net.knightsandkings.knk.core.privacy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.knightsandkings.knk.core.ports.api.PrivacyErasuresApi;

/**
 * The game server's side of a GDPR erasure (realtime S5, PandiO/knk-workspace#184, DESIGN §7.4). For every erased
 * account it (1) kicks the player with a neutral message if they are online, (2) waits a moment so the quit handlers
 * have run and Minecraft has saved the player, then (3) runs the {@link LocalDataEraser} steps: main-thread ones on the
 * main thread, the rest on its own worker thread.
 * <ul>
 *   <li><b>Push</b> ({@link #pushed}): the {@code AccountErased} hub event is applied at once. It does not move the
 *       stored position (an earlier one may have been missed), but it starts a catch-up, which does.</li>
 *   <li><b>Catch-up</b> ({@link #catchUp}): on every hub (re)connect and after a push, {@code GET
 *       api/privacy/erasures?since={lastId}} page by page; each row is applied, then the position on disk moves past it.
 *       The API unreachable: it stops and the next (re)connect tries again. A position above the API's newest id (the
 *       feed was reset: another database) starts again from 0.</li>
 *   <li>An erasure applied once in this run is not applied again (push then catch-up); after a restart the feed from
 *       the stored position is, and every step is idempotent.</li>
 *   <li>A returning player gets a fresh account under the same Minecraft UUID. When this server last knew that UUID
 *       under another user id ({@link #setKnownUserIds}), the erasure is applied without the UUID: no kick, only what
 *       is filed under the erased user id goes. (The API also serves such a row without the UUID.)</li>
 * </ul>
 * Everything runs on one worker thread, in order, so the main thread never waits for the API or the disk. The hourly
 * offline-identity check and the offline store's maximum age (KNG-58) stay as the backstop. Logs name the erasure id
 * only, never a UUID or a name.
 */
public final class AccountErasureService implements AutoCloseable {

    /** Kicks the player of {@code account} if they are online (main thread); completes with whether they were. */
    @FunctionalInterface
    public interface Kicker {
        CompletableFuture<Boolean> kick(ErasedAccount account);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** What one erasure did. */
    public record Report(long erasureId, boolean kicked, Map<String, Integer> erased, List<String> failed) {
        public Report {
            erased = Map.copyOf(erased);
            failed = List.copyOf(failed);
        }

        public int total() {
            return erased.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public static final int PAGE_SIZE = 100;
    static final int REMEMBERED = 1024;
    static final Duration MAIN_THREAD_TIMEOUT = Duration.ofSeconds(30);
    static final Duration API_TIMEOUT = Duration.ofSeconds(60);

    private final LocalDataEraser eraser;
    private final PrivacyErasuresApi api;
    private final ErasureCursorStore cursor;
    private final Kicker kicker;
    private final Executor mainThread;
    private final Executor worker;
    private final ExecutorService ownWorker;
    private final Duration settle;
    private final Sleeper sleeper;
    private final Logger logger;
    private final Set<Long> handled = new LinkedHashSet<>();
    private final AtomicLong applied = new AtomicLong();
    private volatile boolean closed;
    private volatile Function<UUID, Integer> knownUserIds = uuid -> null;

    /**
     * @param worker   where pushes and catch-ups run, one at a time; null = an own daemon thread, stopped by {@link #close}
     * @param settle   wait between a kick and the deletion (quit handlers, Minecraft's own save of the player)
     */
    public AccountErasureService(LocalDataEraser eraser, PrivacyErasuresApi api, ErasureCursorStore cursor, Kicker kicker,
                                 Executor mainThread, Executor worker, Duration settle, Sleeper sleeper, Logger logger) {
        this.eraser = Objects.requireNonNull(eraser, "eraser");
        this.api = Objects.requireNonNull(api, "api");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.kicker = Objects.requireNonNull(kicker, "kicker");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.settle = settle == null || settle.isNegative() ? Duration.ZERO : settle;
        this.sleeper = sleeper == null ? d -> Thread.sleep(d.toMillis()) : sleeper;
        this.logger = Objects.requireNonNull(logger, "logger");
        if (worker == null) {
            this.ownWorker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "KnK-AccountErasure");
                thread.setDaemon(true);
                return thread;
            });
            this.worker = ownWorker;
        } else {
            this.ownWorker = null;
            this.worker = worker;
        }
    }

    /**
     * The user id this server last knew for a UUID (user cache, offline store); null when it knows none. Used to leave a
     * returning player's new account alone.
     */
    public void setKnownUserIds(Function<UUID, Integer> knownUserIds) {
        this.knownUserIds = knownUserIds == null ? uuid -> null : knownUserIds;
    }

    /** {@code AccountErased} arrived over the hub: apply it, then catch up (which moves the stored position). */
    public void pushed(ErasedAccount account) {
        Objects.requireNonNull(account, "account");
        submit(() -> {
            apply(account);
            if (account.erasureId() > cursor.lastId()) {
                catchUpNow();
            }
        });
    }

    /** A hub (re)connect: apply what the feed has after the stored position. */
    public void catchUp() {
        submit(this::catchUpNow);
    }

    /** The stored position (shown by /knk health). */
    public long lastId() {
        return cursor.lastId();
    }

    /** Erasures applied since start. */
    public long appliedCount() {
        return applied.get();
    }

    @Override
    public void close() {
        closed = true;
        if (ownWorker != null) {
            ownWorker.shutdownNow();
        }
    }

    private void submit(Runnable work) {
        if (closed) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "[KnK Privacy] Account erasure work failed", e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutting down: the next start catches up from the stored position.
        }
    }

    /**
     * Pages through the feed from the stored position (worker thread; blocks). Returns how many erasures it applied.
     */
    int catchUpNow() {
        long since = cursor.lastId();
        boolean restarted = false;
        int count = 0;
        while (!closed) {
            ErasureFeedPage page;
            try {
                page = api.since(since, PAGE_SIZE).get(API_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return count;
            } catch (Exception e) {
                logger.log(Level.FINE, "[KnK Privacy] Erasure catch-up stopped (API unreachable); next hub connect retries", e);
                return count;
            }
            if (page == null) {
                return count;
            }
            if (since > 0 && page.latestId() < since && !restarted) {
                logger.warning("[KnK Privacy] The API's erasure feed ends at #" + page.latestId() + ", before this server's"
                    + " position #" + since + " (another database?): catching up from the start");
                cursor.reset();
                since = 0;
                restarted = true;
                continue;
            }
            for (ErasedAccount account : page.items()) {
                if (apply(account) != null) {
                    count++;
                }
                if (Thread.currentThread().isInterrupted()) {
                    return count; // stopping: not applied, so the position stays before it
                }
                cursor.advanceTo(account.erasureId());
            }
            if (!page.hasMore() || page.lastId() <= since) {
                return count;
            }
            since = page.lastId();
        }
        return count;
    }

    /** Kick, settle, delete (worker thread; blocks). Null when this run already applied that erasure. */
    Report apply(ErasedAccount pushedOrRead) {
        synchronized (handled) {
            if (handled.contains(pushedOrRead.erasureId())) {
                return null;
            }
        }
        ErasedAccount account = withoutReusedUuid(pushedOrRead);
        boolean kicked = false;
        try {
            kicked = Boolean.TRUE.equals(kicker.kick(account).get(MAIN_THREAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[KnK Privacy] Erasure #" + account.erasureId() + ": the kick failed", e);
        }
        if (kicked && !settle.isZero()) {
            try {
                sleeper.sleep(settle);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Map<String, Integer> erased = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        try {
            LocalDataEraser.Outcome main = CompletableFuture
                .supplyAsync(() -> eraser.run(LocalDataEraser.Where.MAIN_THREAD, account), mainThread)
                .get(MAIN_THREAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            erased.putAll(main.erased());
            failed.addAll(main.failed());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            failed.add("main-thread steps");
            logger.log(Level.WARNING, "[KnK Privacy] Erasure #" + account.erasureId() + ": main-thread steps did not run", e);
        }
        LocalDataEraser.Outcome rest = eraser.run(LocalDataEraser.Where.WORKER, account);
        erased.putAll(rest.erased());
        failed.addAll(rest.failed());

        synchronized (handled) {
            handled.add(account.erasureId());
            if (handled.size() > REMEMBERED) {
                handled.remove(handled.iterator().next());
            }
        }
        applied.incrementAndGet();
        Report report = new Report(account.erasureId(), kicked, erased, failed);
        logger.info(describe(report));
        return report;
    }

    /** The account without its UUID when this server knows that UUID under another user id now. */
    ErasedAccount withoutReusedUuid(ErasedAccount account) {
        if (account.formerUuid() == null) {
            return account;
        }
        Integer known;
        try {
            known = knownUserIds.apply(account.formerUuid());
        } catch (RuntimeException e) {
            known = null;
        }
        if (known == null || known == account.userId()) {
            return account;
        }
        logger.info("[KnK Privacy] Erasure #" + account.erasureId() + ": that Minecraft account belongs to a newer knk"
            + " account now; only data filed under the erased account is deleted");
        return new ErasedAccount(account.erasureId(), account.userId(), null);
    }

    static String describe(Report report) {
        String what = report.erased().entrySet().stream().filter(e -> e.getValue() > 0)
            .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
        return "[KnK Privacy] Erasure #" + report.erasureId() + " applied: "
            + (report.kicked() ? "player kicked, " : "")
            + (what.isEmpty() ? "nothing kept locally" : what)
            + (report.failed().isEmpty() ? "" : "; failed: " + String.join(", ", report.failed()));
    }
}
