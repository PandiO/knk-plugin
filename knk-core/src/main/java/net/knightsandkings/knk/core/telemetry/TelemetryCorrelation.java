package net.knightsandkings.knk.core.telemetry;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The correlation id of the in-game action currently being handled (KNG-34 link 6, DESIGN.md §F.12):
 * set around an action on the thread that starts it, carried to the API client's worker threads by
 * {@link #propagating(Executor)}, and sent as {@code X-Correlation-Id} so the API's failure events
 * and ledger postings can be joined to the player's diagnostic events. Thread-local; null when no
 * action is being correlated.
 */
public final class TelemetryCorrelation {

    public static final String HEADER = "X-Correlation-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TelemetryCorrelation() {
    }

    /** The id of the action on this thread, or null. */
    public static String current() {
        return CURRENT.get();
    }

    /** A new random id (short enough for the API's 64-character columns). */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Runs {@code work} with {@code correlationId} as the current id, restoring the previous one after. */
    public static <T> T with(String correlationId, Supplier<T> work) {
        String previous = CURRENT.get();
        set(correlationId);
        try {
            return work.get();
        } finally {
            set(previous);
        }
    }

    /** {@link #with(String, Supplier)} for work without a result. */
    public static void run(String correlationId, Runnable work) {
        with(correlationId, () -> {
            work.run();
            return null;
        });
    }

    /** Restores the previous correlation id when closed. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Makes {@code correlationId} current until the returned scope is closed (for a scope that spans
     * more than one call on this thread, e.g. a command until the next tick). Close on the same thread.
     */
    public static Scope open(String correlationId) {
        String previous = CURRENT.get();
        set(correlationId);
        return () -> set(previous);
    }

    /**
     * An executor that runs each task with the correlation id that was current when the task was
     * submitted (so {@code CompletableFuture.supplyAsync(call, executor)} started inside
     * {@link #run} sends the header from the worker thread).
     */
    public static Executor propagating(Executor delegate) {
        return task -> {
            String id = CURRENT.get();
            delegate.execute(id == null ? task : () -> run(id, task));
        };
    }

    private static void set(String id) {
        if (id == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(id);
        }
    }
}
