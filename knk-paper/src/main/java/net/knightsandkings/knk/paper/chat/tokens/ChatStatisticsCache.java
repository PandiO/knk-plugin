package net.knightsandkings.knk.paper.chat.tokens;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;

/**
 * The public statistics view per player for the chat tokens (PandiO/knk-workspace#233): one API read per player at
 * most every {@link #TTL} (the plugin uploads statistics every 60 s, so a fresher read would show nothing new). A read
 * in flight is shared; a failed read is dropped so the next token tries again. Thread-safe: chat arrives on Paper's
 * async chat threads.
 */
public final class ChatStatisticsCache {

    public static final Duration TTL = Duration.ofSeconds(60);
    /** Expired entries are swept once the cache holds this many players (offline names asked about). */
    private static final int SWEEP_AT = 256;

    private record Entry(CompletableFuture<PlayerStatistics> future, long fetchedAt) {
    }

    private final BiFunction<Integer, Integer, CompletableFuture<PlayerStatistics>> read;
    private final LongSupplier nanoTime;
    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    /** @param read (target user id, acting user id or null) → the {@code audience=public} view */
    public ChatStatisticsCache(BiFunction<Integer, Integer, CompletableFuture<PlayerStatistics>> read, LongSupplier nanoTime) {
        this.read = read;
        this.nanoTime = nanoTime;
    }

    public CompletableFuture<PlayerStatistics> get(int userId, Integer actingUserId) {
        long now = nanoTime.getAsLong();
        if (entries.size() >= SWEEP_AT) {
            entries.values().removeIf(e -> e.future().isDone() && expired(e, now));
        }
        Entry entry = entries.compute(userId, (id, current) -> {
            if (current != null && (!current.future().isDone() || !expired(current, now))) {
                return current;
            }
            CompletableFuture<PlayerStatistics> future;
            try {
                future = read.apply(id, actingUserId);
            } catch (RuntimeException e) {
                future = CompletableFuture.failedFuture(e);
            }
            return new Entry(future, now);
        });
        entry.future().whenComplete((stats, error) -> {
            if (error != null) {
                entries.remove(userId, entry);
            }
        });
        return entry.future();
    }

    /** After the player changed a visibility setting: the next token reads again. */
    public void invalidate(int userId) {
        entries.remove(userId);
    }

    private static boolean expired(Entry entry, long now) {
        return entry.future().isCompletedExceptionally() || now - entry.fetchedAt() >= TTL.toNanos();
    }
}
