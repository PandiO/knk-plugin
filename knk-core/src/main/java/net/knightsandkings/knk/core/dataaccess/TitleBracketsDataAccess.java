package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cached title-bracket list (InventoryMenu content port CP3/CP8). Brackets are seeded data that
 * practically never change, so the whole list is cached for {@code ttl}: while it is fresh
 * {@link #listAsync()} returns an already-completed future (safe for main-thread menu code), and
 * concurrent misses share one in-flight request. A failed fetch serves the stale list if there
 * is one.
 */
public class TitleBracketsDataAccess {

    private final TitleBracketsQueryApi queryApi;
    private final Duration ttl;
    private final Clock clock;

    private volatile List<TitleBracket> cached;
    private volatile long cachedAtMillis;
    private CompletableFuture<List<TitleBracket>> inFlight;

    public TitleBracketsDataAccess(TitleBracketsQueryApi queryApi, Duration ttl) {
        this(queryApi, ttl, Clock.systemUTC());
    }

    public TitleBracketsDataAccess(TitleBracketsQueryApi queryApi, Duration ttl, Clock clock) {
        this.queryApi = queryApi;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Every bracket, lowest {@code minExperience} first. */
    public synchronized CompletableFuture<List<TitleBracket>> listAsync() {
        List<TitleBracket> current = cached;
        if (current != null && clock.millis() - cachedAtMillis < ttl.toMillis()) {
            return CompletableFuture.completedFuture(current);
        }
        if (inFlight != null) {
            return inFlight;
        }
        CompletableFuture<List<TitleBracket>> request = queryApi.listAll()
                .thenApply(list -> {
                    List<TitleBracket> sorted = list == null ? List.of() : list.stream()
                            .sorted(Comparator.comparingInt(TitleBracket::minExperience))
                            .toList();
                    synchronized (this) {
                        cached = sorted;
                        cachedAtMillis = clock.millis();
                        inFlight = null;
                    }
                    return sorted;
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
        // A future that completed inline (cached HTTP layer, tests) already cleared itself.
        inFlight = request.isDone() ? null : request;
        return request;
    }

    /** The cached list if one was ever fetched (possibly stale), else an empty list. Never does I/O. */
    public List<TitleBracket> cachedOrEmpty() {
        List<TitleBracket> current = cached;
        return current != null ? current : List.of();
    }

    public synchronized void invalidate() {
        cachedAtMillis = 0;
    }
}
