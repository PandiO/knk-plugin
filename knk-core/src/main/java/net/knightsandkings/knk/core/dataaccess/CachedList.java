package net.knightsandkings.knk.core.dataaccess;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A whole list fetched from the API and cached for {@code ttl} (InventoryMenu content port: title
 * brackets, permission groups - small, rarely-changing reference lists). While fresh,
 * {@link #getAsync()} returns an already-completed future, so main-thread menu code can call it;
 * concurrent misses share one in-flight request; a failed refresh serves the stale list if there
 * is one.
 */
public final class CachedList<T> {

    private final Supplier<CompletableFuture<List<T>>> loader;
    private final Duration ttl;
    private final Clock clock;

    private volatile List<T> cached;
    private volatile long cachedAtMillis;
    private CompletableFuture<List<T>> inFlight;

    public CachedList(Supplier<CompletableFuture<List<T>>> loader, Duration ttl, Clock clock) {
        this.loader = loader;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized CompletableFuture<List<T>> getAsync() {
        List<T> current = cached;
        if (current != null && clock.millis() - cachedAtMillis < ttl.toMillis()) {
            return CompletableFuture.completedFuture(current);
        }
        if (inFlight != null) {
            return inFlight;
        }
        CompletableFuture<List<T>> request = loader.get()
                .thenApply(list -> {
                    List<T> copy = list == null ? List.of() : List.copyOf(list);
                    synchronized (this) {
                        cached = copy;
                        cachedAtMillis = clock.millis();
                        inFlight = null;
                    }
                    return copy;
                })
                .exceptionally(ex -> {
                    synchronized (this) {
                        inFlight = null;
                    }
                    if (current != null) {
                        return current;
                    }
                    throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
                });
        // A future that completed inline (tests, cached HTTP layer) already cleared itself.
        inFlight = request.isDone() ? null : request;
        return request;
    }

    /** The cached list if one was ever fetched (possibly stale), else empty. Never does I/O. */
    public List<T> cachedOrEmpty() {
        List<T> current = cached;
        return current != null ? current : List.of();
    }

    public synchronized void invalidate() {
        cachedAtMillis = 0;
    }
}
